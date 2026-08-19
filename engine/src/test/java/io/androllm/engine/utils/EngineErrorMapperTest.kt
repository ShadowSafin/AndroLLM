package io.androllm.engine.utils

import io.androllm.engine.compat.ModelCompatibilityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [EngineErrorMapper] — the mapping of raw load exceptions
 * to human-readable [LoadFailure]s (message, stage, suggestion, retryable) so
 * the engine never surfaces an opaque native error and the UI knows whether a
 * Retry button can help.
 */
class EngineErrorMapperTest {

    @Test
    fun `compatibility exception keeps its actionable message`() {
        val e = ModelCompatibilityException(
            "Model 'x' is missing required tokenizer file(s) next to model.litertlm"
        )
        val failure = EngineErrorMapper.map(e, "Test Model")
        assertEquals("compatibility", failure.stage)
        assertEquals(e.message, failure.message)
        assertFalse(failure.retryable)
    }

    @Test
    fun `native unsupported-file-format error is mapped to initialize with suggestion`() {
        val e = IllegalStateException("INVALID_ARGUMENT: Unsupported file format")
        val failure = EngineErrorMapper.map(e, "Test Model")
        assertEquals("initialize", failure.stage)
        assertTrue(failure.retryable)
        assertTrue(failure.suggestion!!.contains("Re-download"))
    }

    @Test
    fun `native malformed-model error is mapped to initialize`() {
        val failure = EngineErrorMapper.map(IllegalStateException("Model file is corrupt"))
        assertEquals("initialize", failure.stage)
    }

    @Test
    fun `out of memory is mapped with a smaller-model suggestion`() {
        val failure = EngineErrorMapper.map(IllegalStateException("Failed to allocate memory: not enough memory"))
        assertEquals("initialize", failure.stage)
        assertTrue(failure.suggestion!!.contains("smaller model"))
        assertTrue(failure.retryable)
    }

    @Test
    fun `missing file is mapped to validate and not retryable`() {
        val failure = EngineErrorMapper.map(java.io.FileNotFoundException("Model file not found: /x"))
        assertEquals("validate", failure.stage)
        assertFalse(failure.retryable)
    }

    @Test
    fun `not-a-litert-model is mapped to validate with redownload suggestion`() {
        val failure = EngineErrorMapper.map(
            IllegalStateException("Not a LiteRT model: expected a .litertlm container (magic \"LITERTLM\")")
        )
        assertEquals("validate", failure.stage)
        assertTrue(failure.suggestion!!.contains("Re-download"))
    }

    @Test
    fun `tflite artifact for chat is mapped to validate and not retryable`() {
        val failure = EngineErrorMapper.map(
            IllegalStateException("Artifact is a 'tflite' file but this engine requires 'litertlm'")
        )
        assertEquals("validate", failure.stage)
        assertFalse(failure.retryable)
        assertTrue(failure.suggestion!!.contains(".litertlm"))
    }

    @Test
    fun `size mismatch is mapped to validate and retryable`() {
        val failure = EngineErrorMapper.map(
            IllegalStateException("File size mismatch: expected 100 bytes but the file is 50 bytes")
        )
        assertEquals("validate", failure.stage)
        assertTrue(failure.retryable)
    }

    @Test
    fun `checksum mismatch is mapped to validate and retryable`() {
        val failure = EngineErrorMapper.map(IllegalStateException("SHA-256 checksum mismatch"))
        assertEquals("validate", failure.stage)
        assertTrue(failure.retryable)
    }

    @Test
    fun `own check validation errors keep the human message`() {
        val failure = EngineErrorMapper.map(IllegalStateException("Model file is empty: /x"))
        assertEquals("validate", failure.stage)
        assertEquals("Model file is empty: /x", failure.message)
        assertNull(failure.suggestion)
        assertTrue(failure.retryable)
    }

    @Test
    fun `unknown exception degrades to generic load stage`() {
        val failure = EngineErrorMapper.map(RuntimeException("Something unexpected"))
        assertEquals("load", failure.stage)
        assertEquals("Something unexpected", failure.message)
        assertTrue(failure.retryable)
    }

    @Test
    fun `exception without message uses the class name`() {
        val failure = EngineErrorMapper.map(RuntimeException())
        assertTrue(failure.message.contains("RuntimeException"))
    }

    @Test
    fun `model name is woven into suggestions`() {
        val failure = EngineErrorMapper.map(
            IllegalStateException("INVALID_ARGUMENT: Unsupported file format"),
            "Qwen2-0.5B"
        )
        assertTrue(failure.suggestion!!.contains("'Qwen2-0.5B'"))
    }
}