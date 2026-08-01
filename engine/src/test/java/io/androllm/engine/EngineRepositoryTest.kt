package io.androllm.engine

import io.androllm.core.common.isError
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.api.NoOpInferenceEngine
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
            emit(io.androllm.core.common.Result.Success(StreamChunk("hello ", false)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("world", false)))
            emit(io.androllm.core.common.Result.Success(StreamChunk("", true, generatedTokens = 2)))
        }

        override suspend fun buildChatPrompt(
            messages: List<io.androllm.engine.models.ChatPromptMessage>,
            addAssistant: Boolean
        ): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("<|im_start|>user\n${messages.last().content}<|im_end|>\n<|im_start|>assistant\n")

        override suspend fun generate(prompt: String, config: GenerationConfig): io.androllm.core.common.Result<String> =
            io.androllm.core.common.Result.Success("hello world")

        override fun cancel(): io.androllm.core.common.Result<Unit> {
            cancelled = true
            return io.androllm.core.common.Result.Success(Unit)
        }

        override fun benchmark(iterations: Int): Flow<io.androllm.core.common.Result<io.androllm.engine.models.BenchmarkResult>> =
            flow { }

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
}
