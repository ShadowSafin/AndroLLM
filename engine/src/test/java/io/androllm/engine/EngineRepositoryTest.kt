package io.androllm.engine

import io.androllm.core.common.isError
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.api.NoOpInferenceEngine
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.StreamChunk
import io.androllm.engine.models.BackendType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DefaultEngineRepository] with a fake engine.
 */
class EngineRepositoryTest {

    private class FakeEngine : InferenceEngine {
        var loaded = false
        var initialized = false
        var cancelled = false
        var generationEmitted = mutableListOf<StreamChunk>()

        override val capabilities = EngineCapabilities(
            name = "Fake",
            version = "1.0",
            backend = BackendType.CPU
        )

        private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
        override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()

        /** When set, the chat stream reports this stop reason instead of a clean finish. */
        var stopReasonOverride: String? = null

        /**
         * When true, every stream completes with ZERO output deltas and a
         * stats record whose stop reason is "" (or [stopReasonOverride]) — the
         * exact shape the native layer returns when the recovery ladder
         * exhausts its retries and falls back to a bare "{}" stats blob.
         */
        var noOutput = false

        private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val engineState: Flow<EngineState> = _engineState.asStateFlow()

        private val _stats = MutableStateFlow<io.androllm.engine.models.EngineStats?>(null)
        override val stats: Flow<io.androllm.engine.models.EngineStats?> = _stats.asStateFlow()

        override suspend fun initialize(config: io.androllm.engine.models.EngineConfig): io.androllm.core.common.Result<Unit> {
            initialized = true
            _engineState.value = EngineState.Unloaded
            return io.androllm.core.common.Result.Success(Unit)
        }

        override fun isLoaded(): Boolean = loaded

        override fun getLoadedModel(): EngineModelInfo? = null

        override suspend fun loadModel(
            model: Model,
            config: io.androllm.engine.models.ModelLoadConfig
        ): io.androllm.core.common.Result<EngineModelInfo> {
            loaded = true
            val info = EngineModelInfo(model.id, model.filePath ?: "", 4096, 32000, BackendType.CPU)
            _engineState.value = EngineState.Ready(info)
            return io.androllm.core.common.Result.Success(info)
        }

        override suspend fun unloadModel(): io.androllm.core.common.Result<Unit> {
            loaded = false
            _engineState.value = EngineState.Unloaded
            return io.androllm.core.common.Result.Success(Unit)
        }

        override fun tokenStream(prompt: String, config: GenerationConfig): Flow<io.androllm.core.common.Result<StreamChunk>> = flow {
            // Mirror the native engine: stats are reported on the RAW path too
            // (nativeGenerate writes _stats), so the repository can detect a
            // corrupted run here just as on the chat path.
            stopReasonOverride?.let {
                _stats.value = io.androllm.engine.models.EngineStats(
                    promptTokens = 1,
                    generatedTokens = 1,
                    stopReason = it
                )
            }
            if (noOutput) {
                _stats.value = io.androllm.engine.models.EngineStats(
                    promptTokens = 1,
                    generatedTokens = 0,
                    stopReason = stopReasonOverride ?: ""
                )
                emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 0)))
                return@flow
            }
            emit(io.androllm.core.common.Result.Success(StreamChunk("hello ", false)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("world", false)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 2)))
        }

        override suspend fun buildChatPrompt(
            messages: List<io.androllm.engine.models.ChatPromptMessage>,
            addAssistant: Boolean
        ): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("<|im_start|>user\n${messages.last().content}<|im_end|>\n<|im_start|>assistant\n")

        override suspend fun generate(prompt: String, config: GenerationConfig): io.androllm.core.common.Result<String> {
            if (noOutput) return io.androllm.core.common.Result.Success("")
            return io.androllm.core.common.Result.Success("hello world")
        }

        override fun generateChatStream(
            messages: List<io.androllm.engine.models.ChatPromptMessage>,
            addAssistant: Boolean,
            config: GenerationConfig
        ): Flow<io.androllm.core.common.Result<StreamChunk>> = flow {
            stopReasonOverride?.let {
                _stats.value = io.androllm.engine.models.EngineStats(
                    promptTokens = 1,
                    generatedTokens = 1,
                    stopReason = it
                )
            }
            if (noOutput) {
                _stats.value = io.androllm.engine.models.EngineStats(
                    promptTokens = 1,
                    generatedTokens = 0,
                    stopReason = stopReasonOverride ?: ""
                )
                emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 0)))
                return@flow
            }
            emit(io.androllm.core.common.Result.Success(StreamChunk("hello ", false)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("world", false)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 2)))
        }

        override fun cancel(): io.androllm.core.common.Result<Unit> {
            cancelled = true
            return io.androllm.core.common.Result.Success(Unit)
        }

        override fun benchmark(iterations: Int): Flow<io.androllm.core.common.Result<io.androllm.engine.models.BenchmarkResult>> =
            flow { }

        override suspend fun resetChat(): io.androllm.core.common.Result<Unit> =
            io.androllm.core.common.Result.Success(Unit)

        override suspend fun getDebugInfo(): io.androllm.core.common.Result<io.androllm.engine.models.EngineDebugInfo?> =
            io.androllm.core.common.Result.Success(null)

        override fun release() {
            loaded = false
        }
    }

    @Test
    fun `initialize delegates to engine`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        val result = repository.initialize()
        assertTrue(result.isSuccess())
        assertTrue(engine.initialized)
    }

    @Test
    fun `loadModel updates engine state`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()

        val model = Model(id = "m1", name = "M", filePath = "/tmp/m.gguf")
        val result = repository.loadModel(model)
        assertTrue(result.isSuccess())

        val state = repository.engineState.first { it is EngineState.Ready }
        assertEquals(model.id, (state as EngineState.Ready).model.id)
    }

    @Test
    fun `generate fails when no model is loaded`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        val result = repository.generate("Hi")
        assertTrue(result.isError())
        assertTrue(repository.generationState.value is GenerationState.Failed)
    }

    @Test
    fun `generate streams into generation state`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"))

        val result = repository.generate("Hi")
        assertTrue(result.isSuccess())

        val completed = repository.generationState.value as GenerationState.Completed
        assertEquals("hello world", completed.text)
    }

    @Test
    fun `generateChat streams into generation state`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"))

        val messages = listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hi"))
        val result = repository.generateChat(messages, addAssistant = true)
        assertTrue("generateChat failed: $result", result.isSuccess())

        val completed = repository.generationState.value as GenerationState.Completed
        assertEquals("hello world", completed.text)
    }

    @Test
    fun `generateChat fails when no model is loaded`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        val result = repository.generateChat(listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hi")))
        assertTrue(result.isError())
        assertTrue(repository.generationState.value is GenerationState.Failed)
    }

    @Test
    fun `generateChat surfaces decode_error as failure`() = runTest {
        val engine = FakeEngine()
        // The native multi-turn path rolls a decode error back and reports it
        // via stats; the repository must surface it as Failed (never persist
        // the partial as a full assistant response).
        engine.stopReasonOverride = "decode_error"
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"))

        val result = repository.generateChat(
            listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hi"))
        )
        assertTrue(result.isError())
        assertTrue(repository.generationState.value is GenerationState.Failed)
    }

    @Test
    fun `generateChat surfaces corrupted stop reason as failure`() = runTest {
        val engine = FakeEngine()
        // REGRESSION: the native bridge reports NaN/INF-logit runs with the
        // canonical stop reason "corrupted" (not "decode_error"). The old
        // check only matched "decode_error", so a corrupted run was published
        // as Completed and its partial garbage persisted as an assistant
        // message — poisoning the next prompt's context (gibberish turn 2+).
        engine.stopReasonOverride = "corrupted"
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"))

        val result = repository.generateChat(
            listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hi"))
        )
        assertTrue("corrupted run must fail, not complete: $result", result.isError())
        val failed = repository.generationState.value as GenerationState.Failed
        assertTrue("partial text must be surfaced for diagnostics", failed.partialText.isNotEmpty())
    }

    @Test
    fun `generateChat surfaces corrupted stop reason as failure on the raw path too`() = runTest {
        val engine = FakeEngine()
        engine.stopReasonOverride = "corrupted"
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"))

        val result = repository.generate("Hi")
        assertTrue("corrupted raw run must fail: $result", result.isError())
        assertTrue(repository.generationState.value is GenerationState.Failed)
    }

    @Test
    fun `cancelGeneration cancels the engine`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        val result = repository.cancelGeneration()
        assertTrue(result.isSuccess())
        assertTrue(engine.cancelled)
        assertTrue(repository.generationState.value is GenerationState.Cancelled)
    }

    @Test
    fun `unloadModel clears state`() = runTest {
        val engine = FakeEngine()
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"))
        repository.unloadModel()
        assertFalse(engine.loaded)
    }

    // ── Zero-output terminal guard (regression: silent empty Completed) ──────

    @Test
    fun `generateChat with zero output tokens surfaces a visible failure instead of completing blank`() = runTest {
        // REGRESSION: when the native recovery ladder exhausts its retries it
        // returns a bare "{}" stats blob — decoded into an EngineStats with an
        // EMPTY stopReason. The old code published Completed(""), so the chat
        // showed no response and no error (the "stuck generating" symptom) and
        // the next send re-entered the same failing cycle.
        val engine = FakeEngine().apply { noOutput = true }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        // The load-time coherence probe would reject a zero-output model — skip
        // it; this test targets the generation guard, not the load probe.
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generateChat(
            listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hello"))
        )

        assertTrue("zero-token run must fail, not complete: $result", result.isError())
        val failed = repository.generationState.value as GenerationState.Failed
        assertTrue("failure must explain the empty run: ${failed.message}", failed.message.contains("no tokens"))
        assertFalse("zero-token run must never publish Completed", repository.generationState.value is GenerationState.Completed)
    }

    @Test
    fun `generate with zero output tokens surfaces a visible failure instead of completing blank`() = runTest {
        val engine = FakeEngine().apply { noOutput = true }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generate("Hello")

        assertTrue("zero-token raw run must fail: $result", result.isError())
        val failed = repository.generationState.value as GenerationState.Failed
        assertTrue("failure must explain the empty run: ${failed.message}", failed.message.contains("no tokens"))
        assertFalse(repository.generationState.value is GenerationState.Completed)
    }

    @Test
    fun `generateQuiet with zero output tokens fails instead of returning blank`() = runTest {
        val engine = FakeEngine().apply { noOutput = true }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generateQuiet("extract")

        assertTrue("zero-token quiet run must fail: $result", result.isError())
    }

    @Test
    fun `generateChat with zero tokens ending on EOS completes cleanly`() = runTest {
        // A model that hits EOS on the very first sample is a LEGITIMATE empty
        // completion — the guard must not turn it into a failure.
        val engine = FakeEngine().apply {
            noOutput = true
            stopReasonOverride = "eos"
        }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generateChat(
            listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hello"))
        )

        assertTrue("immediate-EOS run must still complete: $result", result.isSuccess())
        val completed = repository.generationState.value as GenerationState.Completed
        assertEquals("", completed.text)
    }

    @Test
    fun `generateChat with zero tokens ending on EOG completes cleanly`() = runTest {
        // REGRESSION (native stop-reason mismatch): the native bridge reports
        // end-of-generation as "eog" (llama.cpp convention), not "eos". An
        // immediate-EOG empty completion is just as legitimate as EOS and must
        // complete cleanly — previously the zero-output guard rejected it and
        // the chat showed a spurious failure ("The model produced no tokens")
        // on every first-turn empty response.
        val engine = FakeEngine().apply {
            noOutput = true
            stopReasonOverride = "eog"
        }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generateChat(
            listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hello"))
        )

        assertTrue("immediate-EOG run must still complete: $result", result.isSuccess())
        val completed = repository.generationState.value as GenerationState.Completed
        assertEquals("", completed.text)
    }

    @Test
    fun `generate with zero tokens ending on EOG completes cleanly`() = runTest {
        val engine = FakeEngine().apply {
            noOutput = true
            stopReasonOverride = "eog"
        }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generate("Hello")

        assertTrue("immediate-EOG raw run must still complete: $result", result.isSuccess())
        val completed = repository.generationState.value as GenerationState.Completed
        assertEquals("", completed.text)
    }

    @Test
    fun `generateChat with a no-progress stop reason fails with the no-progress message`() = runTest {
        // REGRESSION (no-progress watchdog contract): when the native layer
        // reports "no_progress" (it decoded many iterations without producing
        // output) the run must surface a visible no-progress failure — never a
        // blank Completed that looks like the UI looping forever.
        val engine = FakeEngine().apply {
            noOutput = true
            stopReasonOverride = "no_progress"
        }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generateChat(
            listOf(io.androllm.engine.models.ChatPromptMessage(role = "user", content = "Hello"))
        )

        assertTrue("no-progress run must fail: $result", result.isError())
        val failed = repository.generationState.value as GenerationState.Failed
        assertTrue("must name the loop: ${failed.message}", failed.message.contains("No-progress"))
        assertFalse(repository.generationState.value is GenerationState.Completed)
    }

    @Test
    fun `generate with a no-progress stop reason fails with the no-progress message`() = runTest {
        val engine = FakeEngine().apply {
            noOutput = true
            stopReasonOverride = "no_progress"
        }
        val repository = DefaultEngineRepository(engine)
        repository.initialize()
        repository.loadModel(
            Model(id = "m1", name = "M", filePath = "/tmp/m.gguf"),
            io.androllm.engine.models.ModelLoadConfig(runSelfTest = false)
        )

        val result = repository.generate("Hello")

        assertTrue("no-progress raw run must fail: $result", result.isError())
        val failed = repository.generationState.value as GenerationState.Failed
        assertTrue("must name the loop: ${failed.message}", failed.message.contains("No-progress"))
        assertFalse(repository.generationState.value is GenerationState.Completed)
    }
}
