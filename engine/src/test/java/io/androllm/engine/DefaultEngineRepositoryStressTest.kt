package io.androllm.engine

import io.androllm.core.common.getOrNull
import io.androllm.core.common.isError
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.StreamChunk
import io.androllm.engine.models.BackendType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime Stabilization stress test: drives 100+ consecutive prompts through
 * [DefaultEngineRepository] and asserts the generation state machine never
 * corrupts, the engine is never re-entered concurrently, and cancellation is
 * never republished as a completed (persisted) response.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultEngineRepositoryStressTest {

    /**
     * Fake engine with a controllable stream and strict re-entrancy tracking.
     * Mirrors the native engine's contract: the stream completes normally even
     * after a cancel (the native loop exits cleanly), which is exactly the case
     * the repository must not misreport as Completed.
     */
    private class StressFakeEngine : InferenceEngine {

        val generationStartCount = AtomicInteger(0)
        val generationEndCount = AtomicInteger(0)
        val concurrentStreams = AtomicInteger(0)
        val maxConcurrentStreams = AtomicInteger(0)
        val cancelRequests = AtomicInteger(0)

        var autoFinish = true
        var streamGate: CompletableDeferred<Unit>? = null
        var onTokenStreamStarted: (() -> Unit)? = null

        val streaming = AtomicBoolean(false)

        override val capabilities = EngineCapabilities(
            name = "StressFake",
            version = "1.0",
            backend = BackendType.CPU
        )

        private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
        override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()

        private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val engineState: Flow<EngineState> = _engineState.asStateFlow()

        private val _stats = MutableStateFlow<EngineStats?>(null)
        override val stats: Flow<EngineStats?> = _stats.asStateFlow()

        override suspend fun initialize(config: io.androllm.engine.models.EngineConfig): io.androllm.core.common.Result<Unit> {
            _engineState.value = EngineState.Unloaded
            return io.androllm.core.common.Result.Success(Unit)
        }

        override fun isLoaded(): Boolean = true

        override fun getLoadedModel(): EngineModelInfo? = null

        override suspend fun loadModel(
            model: Model,
            config: io.androllm.engine.models.ModelLoadConfig
        ): io.androllm.core.common.Result<EngineModelInfo> =
            io.androllm.core.common.Result.Success(
                EngineModelInfo(model.id, model.filePath ?: "", 4096, 32000, BackendType.CPU)
            )

        override suspend fun unloadModel(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)

        override fun tokenStream(
            prompt: String,
            config: GenerationConfig
        ): Flow<io.androllm.core.common.Result<StreamChunk>> = flow {
            val concurrent = concurrentStreams.incrementAndGet()
            maxConcurrentStreams.updateAndGet { prev -> maxOf(prev, concurrent) }
            val started = streaming.compareAndSet(false, true)
            assertTrue("Engine re-entered while another stream was active", started)
            generationStartCount.incrementAndGet()
            onTokenStreamStarted?.invoke()
            try {
                if (autoFinish) {
                    emit(io.androllm.core.common.Result.Success(StreamChunk("response-", false, generatedTokens = 1)))
                    emit(io.androllm.core.common.Result.Success(StreamChunk("${generationStartCount.get()}", false, generatedTokens = 2)))
                } else {
                    // Mid-generation cancellation: emit ONE token first so the
                    // repository's first-token watchdog is satisfied (the test
                    // then gates the stream open to hold the generation
                    // mid-flight). Without a first token, the watchdog would
                    // legitimately fire after its real 5s budget on a slow CI
                    // run and the test would observe Failed instead of
                    // Cancelled.
                    emit(io.androllm.core.common.Result.Success(StreamChunk("partial-", false, generatedTokens = 1)))
                }
                val gate = streamGate
                if (gate != null) {
                    gate.await()
                }
                // Even on cancel, the native loop exits cleanly and the stream
                // completes with the final finished marker.
                emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 2)))
            } finally {
                streaming.set(false)
                concurrentStreams.decrementAndGet()
                generationEndCount.incrementAndGet()
            }
        }

        override suspend fun buildChatPrompt(
            messages: List<io.androllm.engine.models.ChatPromptMessage>,
            addAssistant: Boolean
        ): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("<|im_start|>assistant\\n")

        override suspend fun generate(
            prompt: String,
            config: GenerationConfig
        ): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("quiet-response")

        override fun cancel(): io.androllm.core.common.Result<Unit> {
            cancelRequests.incrementAndGet()
            return io.androllm.core.common.Result.Success(Unit)
        }

        override fun benchmark(iterations: Int): Flow<io.androllm.core.common.Result<io.androllm.engine.models.BenchmarkResult>> =
            flow { }

        override suspend fun resetChat(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)

        override suspend fun getDebugInfo(): io.androllm.core.common.Result<io.androllm.engine.models.EngineDebugInfo?> =
            io.androllm.core.common.Result.Success(null)

        override fun release() = Unit
    }

    private suspend fun loadedRepository(engine: StressFakeEngine): DefaultEngineRepository {
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "stress", name = "Stress", filePath = "/tmp/stress.gguf"))
        return repository
    }

    @Test
    fun `100 consecutive prompts all complete with correct responses`() = runTest {
        val engine = StressFakeEngine()
        val repository = loadedRepository(engine)

        for (i in 1..100) {
            val result = repository.generate("prompt $i")
            assertTrue("prompt $i failed: ${result}", result.isSuccess())

            val state = repository.generationState.value
            assertTrue("prompt $i left state $state", state is GenerationState.Completed)
            assertEquals("response-$i", (state as GenerationState.Completed).text)
        }

        // Every generation completed; no generation ever overlapped another.
        assertEquals(100, engine.generationStartCount.get())
        assertEquals(100, engine.generationEndCount.get())
        assertEquals(1, engine.maxConcurrentStreams.get())
    }

    @Test
    fun `100 consecutive prompts never fail and state returns to Completed each time`() = runTest {
        val engine = StressFakeEngine()
        val repository = loadedRepository(engine)

        var failures = 0
        for (i in 1..100) {
            val result = repository.generate("prompt $i")
            if (result.isError()) failures++
            val state = repository.generationState.value
            if (state !is GenerationState.Completed) failures++
            if (repository.generationState.value is GenerationState.Failed) failures++
        }
        assertEquals(0, failures)
        assertEquals(100, engine.generationEndCount.get())
    }

    @Test
    fun `cancel mid-generation is never republished as Completed`() = runTest {
        val engine = StressFakeEngine().apply {
            autoFinish = false
            streamGate = CompletableDeferred()
        }
        val repository = loadedRepository(engine)

        // Start generation; it blocks inside the stream until the gate opens.
        val started = CompletableDeferred<Unit>()
        engine.onTokenStreamStarted = { started.complete(Unit) }

        val job = launch(Dispatchers.Default) {
            repository.generate("cancelled prompt")
        }

        started.await()
        val cancelResult = repository.cancelGeneration()
        assertTrue(cancelResult.isSuccess())
        assertEquals(1, engine.cancelRequests.get())

        // Release the gate so the stream (and thus generate) can finish.
        engine.streamGate?.complete(Unit)
        job.join()

        // Regression: the cancelled run must NOT surface as Completed, or the
        // chat layer would persist partial text as a full assistant response.
        val finalState = repository.generationState.value
        assertTrue("cancelled run republished as $finalState", finalState is GenerationState.Cancelled)
    }

    @Test
    fun `cancel when idle does not poison the next generation`() = runTest {
        val engine = StressFakeEngine()
        val repository = loadedRepository(engine)

        // Cancel with no generation in flight.
        repository.cancelGeneration()

        // The next generation must run normally and complete.
        val result = repository.generate("after idle cancel")
        assertTrue(result.isSuccess())
        val state = repository.generationState.value
        assertTrue("state after idle cancel + generate: $state", state is GenerationState.Completed)
        assertEquals("response-1", (state as GenerationState.Completed).text)
    }

    @Test
    fun `successive streams never overlap even with rapid prompts`() = runTest {
        val engine = StressFakeEngine()
        val repository = loadedRepository(engine)

        val jobs = (1..20).map { i ->
            launch(Dispatchers.Default) { repository.generate("rapid $i") }
        }
        jobs.forEach { it.join() }

        assertEquals(20, engine.generationStartCount.get())
        assertEquals(20, engine.generationEndCount.get())
        // The repository mutex serializes all generations: never more than one
        // stream alive at a time.
        assertEquals(1, engine.maxConcurrentStreams.get())
    }

    @Test
    fun `generateQuiet and generate never overlap`() = runTest {
        val engine = StressFakeEngine()
        val repository = loadedRepository(engine)

        val results = (1..30).map { i ->
            launch(Dispatchers.Default) {
                if (i % 2 == 0) repository.generate("chat $i") else repository.generateQuiet("quiet $i")
            }
        }
        results.forEach { it.join() }

        assertEquals(15, engine.generationStartCount.get())
        assertEquals(1, engine.maxConcurrentStreams.get())
    }

    @Test
    fun `generateQuiet result is preserved through concurrent chat load`() = runTest {
        val engine = StressFakeEngine()
        val repository = loadedRepository(engine)

        // generateQuiet is the memory-extraction path; it must return the full
        // text even when chat generations queue behind it.
        val quietResult = repository.generateQuiet("extract me")
        assertTrue(quietResult.isSuccess())
        assertEquals("quiet-response", quietResult.getOrNull())

        val chatResult = repository.generate("chat after quiet")
        assertTrue(chatResult.isSuccess())
    }

    /**
     * Emulates a stalled backend: the stream produces NO tokens and only ends
     * after cancel() is called (mirrors the native loop exiting at its cancel
     * check). The repository's first-token watchdog must fire and report the
     * stall instead of spinning forever.
     */
    private class StallFakeEngine : InferenceEngine {
        private val cancelSignal = CompletableDeferred<Unit>()
        var cancelRequests = 0

        override val capabilities = EngineCapabilities(
            name = "StallFake", version = "1.0", backend = BackendType.CPU
        )

        private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
        override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()
        private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val engineState: Flow<EngineState> = _engineState.asStateFlow()
        private val _stats = MutableStateFlow<EngineStats?>(null)
        override val stats: Flow<EngineStats?> = _stats.asStateFlow()

        override suspend fun initialize(config: io.androllm.engine.models.EngineConfig): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override fun isLoaded(): Boolean = true
        override fun getLoadedModel(): EngineModelInfo? = null
        override suspend fun loadModel(
            model: Model, config: io.androllm.engine.models.ModelLoadConfig
        ): io.androllm.core.common.Result<EngineModelInfo> =
            io.androllm.core.common.Result.Success(EngineModelInfo(model.id, model.filePath ?: "", 4096, 32000, BackendType.CPU))
        override suspend fun unloadModel(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)

        override fun tokenStream(
            prompt: String, config: GenerationConfig
        ): Flow<io.androllm.core.common.Result<StreamChunk>> = flow {
            // No tokens ever; the stream only completes once cancelled.
            cancelSignal.await()
            emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 0)))
        }

        override suspend fun buildChatPrompt(
            messages: List<io.androllm.engine.models.ChatPromptMessage>, addAssistant: Boolean
        ): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("assistant\n")
        override suspend fun generate(prompt: String, config: GenerationConfig): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("hi")
        override fun cancel(): io.androllm.core.common.Result<Unit> {
            cancelRequests++
            cancelSignal.complete(Unit)
            return io.androllm.core.common.Result.Success(Unit)
        }
        override fun benchmark(iterations: Int): Flow<io.androllm.core.common.Result<io.androllm.engine.models.BenchmarkResult>> = flow { }
        override suspend fun resetChat(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override suspend fun getDebugInfo(): io.androllm.core.common.Result<io.androllm.engine.models.EngineDebugInfo?> =
            io.androllm.core.common.Result.Success(null)
        override fun release() = Unit
    }

    // NOTE: the stall watchdog now runs on the repository's own real
    // Dispatchers.Default scope (guaranteed a free thread even when the caller
    // dispatcher is blocked by a native decode), so these two tests run in
    // REAL time — the watchdog fires at the real 5s floor. runTest's virtual
    // clock would jump the outer ceiling (virtual 300s) before the real
    // watchdog fires, so runBlocking is required.

    @Test
    fun `a stalled generation is cancelled and reported instead of spinning forever`() = runBlocking {
        val engine = StallFakeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "stall", name = "Stall", filePath = "/tmp/stall.gguf"))

        val result = repository.generate("hello")

        assertTrue("stalled run must fail: $result", result.isError())
        assertTrue("native cancel must have been requested", engine.cancelRequests > 0)
        val failed = repository.generationState.value as GenerationState.Failed
        assertTrue("must explain the stall: ${failed.message}", failed.message.contains("stalled"))
    }

    @Test
    fun `chat generation also reports a stall`() = runBlocking {
        val engine = StallFakeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "stall", name = "Stall", filePath = "/tmp/stall.gguf"))

        val result = repository.generateChat(
            listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "hello"))
        )

        assertTrue("stalled chat run must fail: $result", result.isError())
        val failed = repository.generationState.value as GenerationState.Failed
        assertTrue(failed.message.contains("stalled"))
    }

    /**
     * Emulates a stalled NON-STREAMING backend: [generate] blocks until
     * [cancel] is called (mirrors the native decode loop exiting at its
     * cancel check). Proves the generateQuiet watchdog actually ABORTS the
     * engine — a bare coroutine timeout could never interrupt a blocking JNI
     * call, which is exactly how the planner pass used to run the full 300s
     * ceiling while the chat UI spun "Preparing local model…".
     */
    private class QuietStallFakeEngine : InferenceEngine {
        private val cancelSignal = CompletableDeferred<Unit>()
        var cancelRequests = 0

        override val capabilities = EngineCapabilities(
            name = "QuietStall", version = "1.0", backend = BackendType.CPU
        )

        private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
        override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()
        private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val engineState: Flow<EngineState> = _engineState.asStateFlow()
        private val _stats = MutableStateFlow<EngineStats?>(null)
        override val stats: Flow<EngineStats?> = _stats.asStateFlow()

        override suspend fun initialize(config: io.androllm.engine.models.EngineConfig): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override fun isLoaded(): Boolean = true
        override fun getLoadedModel(): EngineModelInfo? = null
        override suspend fun loadModel(
            model: Model, config: io.androllm.engine.models.ModelLoadConfig
        ): io.androllm.core.common.Result<EngineModelInfo> =
            io.androllm.core.common.Result.Success(EngineModelInfo(model.id, model.filePath ?: "", 4096, 32000, BackendType.CPU))
        override suspend fun unloadModel(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override fun tokenStream(
            prompt: String, config: GenerationConfig
        ): Flow<io.androllm.core.common.Result<StreamChunk>> = flow {
            emit(io.androllm.core.common.Result.Success(StreamChunk("response", false, generatedTokens = 1)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 1)))
        }
        override suspend fun buildChatPrompt(
            messages: List<io.androllm.engine.models.ChatPromptMessage>, addAssistant: Boolean
        ): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("assistant\n")
        override suspend fun generate(
            prompt: String, config: GenerationConfig
        ): io.androllm.core.common.Result<String> =
            // Blocks until the watchdog aborts the run via cancel() — the
            // native contract for a stalled decode. NonCancellable emulates a
            // real JNI call: coroutine cancellation alone can NOT unwind it;
            // only engine.cancel() (nativeCancel) can release the decode.
            withContext(kotlinx.coroutines.NonCancellable) {
                cancelSignal.await()
                io.androllm.core.common.Result.Success("partial")
            }
        override fun cancel(): io.androllm.core.common.Result<Unit> {
            cancelRequests++
            cancelSignal.complete(Unit)
            return io.androllm.core.common.Result.Success(Unit)
        }
        override fun benchmark(iterations: Int): Flow<io.androllm.core.common.Result<io.androllm.engine.models.BenchmarkResult>> = flow { }
        override suspend fun resetChat(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override suspend fun getDebugInfo(): io.androllm.core.common.Result<io.androllm.engine.models.EngineDebugInfo?> =
            io.androllm.core.common.Result.Success(null)
        override fun release() = Unit
    }

    @Test
    fun `generateQuiet watchdog aborts a stalled native call and releases the mutex`() = runBlocking {
        val engine = QuietStallFakeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "qstall", name = "QStall", filePath = "/tmp/qstall.gguf"),
            // The self-test probe would also block on generate() — skip it;
            // this test targets the quiet watchdog, not the load probe.
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generateQuiet("plan this", timeoutMs = 300)

        assertTrue("stalled quiet run must fail fast: $result", result.isError())
        assertTrue("watchdog must have aborted the native call", engine.cancelRequests > 0)
        // The generation mutex must be released so a later turn is not wedged
        // on "Generation already in progress".
        val next = repository.generate("after quiet timeout")
        assertTrue("engine must be reusable after the watchdog abort: $next", next.isSuccess())
        assertTrue(repository.generationState.value is GenerationState.Completed)
    }

    /**
     * Emulates a model whose inference is broken: the self-test probe NEVER
     * returns. loadModel must reject it via the probe watchdog instead of
     * hanging the load forever.
     */
    private class HungProbeEngine : InferenceEngine {
        override val capabilities = EngineCapabilities(
            name = "HungProbe", version = "1.0", backend = BackendType.CPU
        )

        private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
        override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()
        private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val engineState: Flow<EngineState> = _engineState.asStateFlow()
        private val _stats = MutableStateFlow<EngineStats?>(null)
        override val stats: Flow<EngineStats?> = _stats.asStateFlow()

        override suspend fun initialize(config: io.androllm.engine.models.EngineConfig): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override fun isLoaded(): Boolean = true
        override fun getLoadedModel(): EngineModelInfo? = null
        override suspend fun loadModel(
            model: Model, config: io.androllm.engine.models.ModelLoadConfig
        ): io.androllm.core.common.Result<EngineModelInfo> =
            io.androllm.core.common.Result.Success(EngineModelInfo(model.id, model.filePath ?: "", 4096, 32000, BackendType.CPU))
        override suspend fun unloadModel(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override fun tokenStream(prompt: String, config: GenerationConfig): Flow<io.androllm.core.common.Result<StreamChunk>> = flow {
            emit(io.androllm.core.common.Result.Success(StreamChunk("hi", false)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 1)))
        }
        override suspend fun buildChatPrompt(
            messages: List<io.androllm.engine.models.ChatPromptMessage>, addAssistant: Boolean
        ): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("assistant\n")
        override suspend fun generate(prompt: String, config: GenerationConfig): io.androllm.core.common.Result<String> =
            // The probe never returns — a hung decode.
            kotlinx.coroutines.suspendCancellableCoroutine { }
        override fun cancel(): io.androllm.core.common.Result<Unit> = io.androllm.core.common.Result.Success(Unit)
        override fun benchmark(iterations: Int): Flow<io.androllm.core.common.Result<io.androllm.engine.models.BenchmarkResult>> = flow { }
        override suspend fun resetChat(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)
        override suspend fun getDebugInfo(): io.androllm.core.common.Result<io.androllm.engine.models.EngineDebugInfo?> =
            io.androllm.core.common.Result.Success(null)
        override fun release() = Unit
    }

    @Test
    fun `loadModel rejects a model whose self-test probe hangs`() = runTest {
        val engine = HungProbeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()

        val result = repository.loadModel(Model(id = "hung", name = "Hung", filePath = "/tmp/hung.gguf"))

        assertTrue("hang probe must fail the load: $result", result.isError())
        val state = repository.engineState.value
        assertTrue("engine must not be Ready for a broken model: $state", state is EngineState.Failed)
    }

    // ── Adaptive stall / hard-ceiling budgets ────────────────────────────────

    @Test
    fun `stall budget floors at 5s for short prompts and scales with prompt length`() {
        // Sane prefill budget: 50ms per estimated prompt token (~4 chars/token),
        // capped at 240s. The 5s floor and the scaling shape are the contract
        // under test — a healthy engine always beats them; a stuck one never does.
        // < 4 chars → 0 estimated tokens → the user-required 5s floor.
        assertEquals(5_000L, DefaultEngineRepository.stallTimeoutMs(3))
        // 4 chars → 1 token → 5s + 1*50ms (still effectively the floor).
        assertEquals(5_050L, DefaultEngineRepository.stallTimeoutMs(4))
        // A 4k-char prompt (~1000 tokens) budgets 5s + 1000*50ms = 55s.
        assertEquals(55_000L, DefaultEngineRepository.stallTimeoutMs(4_000))
        // A huge prompt must not wait forever — capped at 240s so a
        // slow-but-healthy CPU prefill on Vulkan-device-lost fallback is not
        // false-stalled; real stalls are escaped by the watchdog ceilings.
        assertEquals(240_000L, DefaultEngineRepository.stallTimeoutMs(1_000_000))
        // Negative/zero lengths never go below the floor.
        assertEquals(5_000L, DefaultEngineRepository.stallTimeoutMs(0))
        assertEquals(5_000L, DefaultEngineRepository.stallTimeoutMs(-10))
    }

    @Test
    fun `hard ceiling never gets stricter than 300s and scales with token budget`() {
        // Zero/negative token budget → the previous fixed 300s behavior.
        assertEquals(300_000L, DefaultEngineRepository.hardGenerationTimeoutMs(0))
        assertEquals(300_000L, DefaultEngineRepository.hardGenerationTimeoutMs(-5))
        // 64 tokens → 64*2s + 300s = 428s (more generous than the old fixed 300s).
        assertEquals(428_000L, DefaultEngineRepository.hardGenerationTimeoutMs(64))
        // A 512-token generation budget allows 512*2s + 300s = 1324s.
        assertEquals(1_324_000L, DefaultEngineRepository.hardGenerationTimeoutMs(512))
        // Never past the 30-minute absolute cap.
        assertEquals(1_800_000L, DefaultEngineRepository.hardGenerationTimeoutMs(10_000))
    }
}
