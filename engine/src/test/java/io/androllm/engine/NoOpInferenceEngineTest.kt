package io.androllm.engine

import io.androllm.core.common.getOrThrow
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.NoOpInferenceEngine
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the placeholder inference engine.
 */
class NoOpInferenceEngineTest {

    private val engine = NoOpInferenceEngine()

    @Test
    fun `engine starts unloaded`() {
        assertFalse(engine.isLoaded())
        assertNull(engine.getLoadedModel())
    }

    @Test
    fun `initialize emits loading state`() = runTest {
        val result = engine.initialize(EngineConfig())
        assertTrue(result.isSuccess())
    }

    @Test
    fun `loadModel loads the model`() = runTest {
        val model = Model(id = "model-1", name = "Test", filePath = "/tmp/model.gguf")
        val result = engine.loadModel(model, ModelLoadConfig())
        assertTrue(result.isSuccess())
        assertTrue(engine.isLoaded())
        assertEquals("model-1", engine.getLoadedModel()?.id)
    }

    @Test
    fun `unloadModel clears state`() = runTest {
        val model = Model(id = "model-1", name = "Test", filePath = "/tmp/model.gguf")
        engine.loadModel(model, ModelLoadConfig())
        val result = engine.unloadModel()
        assertTrue(result.isSuccess())
        assertFalse(engine.isLoaded())
        assertNull(engine.getLoadedModel())
    }

    @Test
    fun `tokenStream emits a complete sequence`() = runTest {
        val chunks = engine.tokenStream("Hello", GenerationConfig()).toList()
        assertTrue(chunks.size >= 4)
        assertTrue(chunks.last().getOrThrow().finished)
    }

    @Test
    fun `generate returns placeholder text`() = runTest {
        val result = engine.generate("Hello", GenerationConfig())
        assertTrue(result.isSuccess())
        assertTrue(result.getOrThrow().contains("placeholder"))
    }

    @Test
    fun `capabilities are populated`() {
        assertEquals("AndroLLM Engine (Placeholder)", engine.capabilities.name)
        assertTrue(engine.capabilities.supportedFormats.contains("gguf"))
        assertNotNull(engine.engineState)
    }
}
