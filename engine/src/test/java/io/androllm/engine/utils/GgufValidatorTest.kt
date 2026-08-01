package io.androllm.engine.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `validateHeader fails on missing file`() {
        val result = GgufValidator.validateHeader("non_existent_file.gguf")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("does not exist") == true)
    }

    @Test
    fun `validateHeader succeeds on valid GGUF binary header`() {
        val ggufFile = tempFolder.newFile("test_model.gguf")

        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46554747) // Magic "GGUF"
        buffer.putInt(3)          // Version 3
        buffer.putLong(100L)      // Tensor count
        buffer.putLong(15L)       // Metadata count

        ggufFile.writeBytes(buffer.array())

        val result = GgufValidator.validateHeader(ggufFile.absolutePath)
        assertTrue(result.isValid)
        assertEquals(3, result.version)
        assertEquals(100L, result.tensorCount)
        assertEquals(15L, result.metadataCount)
    }

    @Test
    fun `validateHeader fails on invalid magic bytes`() {
        val invalidFile = tempFolder.newFile("invalid.gguf")
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x12345678) // Invalid magic
        buffer.putInt(3)
        buffer.putLong(10L)
        buffer.putLong(5L)

        invalidFile.writeBytes(buffer.array())

        val result = GgufValidator.validateHeader(invalidFile.absolutePath)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("Invalid GGUF magic header") == true)
    }
}
