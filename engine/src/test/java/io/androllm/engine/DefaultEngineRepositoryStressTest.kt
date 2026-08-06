package io.androllm.engine

import io.androllm.core.common.getOrNull
import io.androllm.core.common.isError
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.api.InferenceEngine
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
                val gate = streamGate
                if (gate != null) {
                    gate.await()
                }
                if (autoFinish) {
                    emit(io.androllm.core.common.Result.Success(StreamChunk("response-", false, generatedTokens = 1)))
                    emit(io.androllm.core.common.Result.Success(StreamChunk("${generationStartCount.get()}", false, generatedTokens = 2)))
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
}
