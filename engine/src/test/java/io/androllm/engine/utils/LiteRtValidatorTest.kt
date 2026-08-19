package io.androllm.engine.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contract tests for [LiteRtValidator] — the pre-load gate that rejects
 * wrong/corrupted artifacts BEFORE the native runtime spends minutes on them:
 * container magic, format gating, size and checksum verification.
 */
class LiteRtValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFile(name: String, bytes: ByteArray): File =
        tmp.newFolder().let { dir ->
            File(dir, name).apply { writeBytes(bytes) }
        }

    private fun litertlmHeader(version: Int = 1): ByteArray = ByteArray(12).apply {
        this[0] = 'L'.code.toByte()
        this[1] = 'I'.code.toByte()
        this[2] = 'T'.code.toByte()
        this[3] = 'E'.code.toByte()
        this[4] = 'R'.code.toByte()
        this[5] = 'T'.code.toByte()
        this[6] = 'L'.code.toByte()
        this[7] = 'M'.code.toByte()
        this[8] = (version and 0xFF).toByte()
        this[9] = ((version shr 8) and 0xFF).toByte()
        this[10] = ((version shr 16) and 0xFF).toByte()
        this[11] = ((version shr 24) and 0xFF).toByte()
    }

    private fun tfliteHeader(): ByteArray = ByteArray(12).apply {
        // TFLite flatbuffer file identifier lives at offset 4..7.
        this[4] = 'T'.code.toByte()
        this[5] = 'F'.code.toByte()
        this[6] = 'L'.code.toByte()
        this[7] = '3'.code.toByte()
    }

    @Test
    fun `valid litertlm header passes`() {
        val file = writeFile("model.litertlm", litertlmHeader(1) + ByteArray(64))
        val result = LiteRtValidator.validateHeader(file.absolutePath)
        assertTrue(result.isValid)
        assertEquals("litertlm", result.format)
        assertEquals(1, result.version)
    }

    @Test
    fun `newer litertlm container version passes`() {
        val file = writeFile("model.litertlm", litertlmHeader(2) + ByteArray(64))
        assertTrue(LiteRtValidator.validateHeader(file.absolutePath).isValid)
    }

    @Test
    fun `version zero is rejected`() {
        val file = writeFile("model.litertlm", litertlmHeader(0) + ByteArray(64))
        val result = LiteRtValidator.validateHeader(file.absolutePath)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("version"))
    }

    @Test
    fun `tflite header passes as tflite`() {
        val file = writeFile("embed.tflite", tfliteHeader() + ByteArray(64))
        val result = LiteRtValidator.validateHeader(file.absolutePath)
        assertTrue(result.isValid)
        assertEquals("tflite", result.format)
    }

    @Test
    fun `gguf magic is rejected`() {
        // GGUF files start with the ASCII "GGUF" magic — the legacy format the
        // LiteRT runtime cannot load. Must be rejected at the header, not after
        // minutes of native initialization.
        val gguf = "GGUF\u0003\u0000\u0000\u0000".toByteArray() + ByteArray(64)
        val file = writeFile("model.gguf", gguf)
        val result = LiteRtValidator.validateHeader(file.absolutePath)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("GGUF"))
    }

    @Test
    fun `random bytes are rejected`() {
        val file = writeFile("junk.bin", ByteArray(128) { it.toByte() })
        val result = LiteRtValidator.validateHeader(file.absolutePath)
        assertFalse(result.isValid)
    }

    @Test
    fun `truncated file is rejected`() {
        val file = writeFile("tiny.bin", ByteArray(6) { 'L'.code.toByte() })
        assertFalse(LiteRtValidator.validateHeader(file.absolutePath).isValid)
    }

    @Test
    fun `missing file is rejected`() {
        val result = LiteRtValidator.validateHeader("does/not/exist.litertlm")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("not found"))
    }

    @Test
    fun `empty file is rejected`() {
        val file = writeFile("empty.litertlm", ByteArray(0))
        assertFalse(LiteRtValidator.validateHeader(file.absolutePath).isValid)
    }

    @Test
    fun `validateForLoad rejects tflite for the chat engine`() {
        val file = writeFile("embed.tflite", tfliteHeader() + ByteArray(64))
        val result = LiteRtValidator.validateForLoad(
            path = file.absolutePath,
            expectedFormat = "litertlm"
        )
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("litertlm"))
    }

    @Test
    fun `validateForLoad accepts tflite for the embedding engine`() {
        val file = writeFile("embed.tflite", tfliteHeader() + ByteArray(64))
        val result = LiteRtValidator.validateForLoad(
            path = file.absolutePath,
            expectedFormat = "tflite"
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `validateForLoad rejects size mismatch`() {
        val file = writeFile("model.litertlm", litertlmHeader(1) + ByteArray(64))
        val result = LiteRtValidator.validateForLoad(
            path = file.absolutePath,
            expectedFormat = "litertlm",
            expectedSizeBytes = file.length() + 1
        )
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("size mismatch"))
    }

    @Test
    fun `validateForLoad passes exact size`() {
        val file = writeFile("model.litertlm", litertlmHeader(1) + ByteArray(64))
        val result = LiteRtValidator.validateForLoad(
            path = file.absolutePath,
            expectedFormat = "litertlm",
            expectedSizeBytes = file.length()
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `validateForLoad passes matching sha256`() {
        val content = litertlmHeader(1) + ByteArray(256) { (it * 7).toByte() }
        val file = writeFile("model.litertlm", content)
        val sha = checkNotNull(LiteRtValidator.calculateSha256(file.absolutePath))
        val result = LiteRtValidator.validateForLoad(
            path = file.absolutePath,
            expectedFormat = "litertlm",
            expectedSha256 = sha
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `validateForLoad rejects sha256 mismatch`() {
        val file = writeFile("model.litertlm", litertlmHeader(1) + ByteArray(256))
        val result = LiteRtValidator.validateForLoad(
            path = file.absolutePath,
            expectedFormat = "litertlm",
            expectedSha256 = "0".repeat(64)
        )
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("checksum"))
    }

    @Test
    fun `verifySize accepts exact length and rejects truncation`() {
        val file = writeFile("model.litertlm", litertlmHeader(1) + ByteArray(64))
        assertTrue(LiteRtValidator.verifySize(file.absolutePath, file.length()))
        assertFalse(LiteRtValidator.verifySize(file.absolutePath, file.length() - 10))
        assertFalse(LiteRtValidator.verifySize(file.absolutePath, file.length() + 10))
        assertFalse(LiteRtValidator.verifySize(file.absolutePath, 0))
    }
}