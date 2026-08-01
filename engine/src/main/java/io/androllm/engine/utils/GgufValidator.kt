package io.androllm.engine.utils

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Result of a GGUF header and integrity validation check.
 */
data class GgufValidationResult(
    val isValid: Boolean,
    val version: Int = 0,
    val tensorCount: Long = 0,
    val metadataCount: Long = 0,
    val architecture: String = "unknown",
    val errorMessage: String? = null
)

/**
 * Binary validator for GGUF model files.
 */
object GgufValidator {

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" in ASCII bytes

    /**
     * Validates whether the specified file is a valid, readable GGUF model.
     */
    fun validateHeader(filePath: String): GgufValidationResult {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            return GgufValidationResult(
                isValid = false,
                errorMessage = "File does not exist or is unreadable: $filePath"
            )
        }

        if (file.length() < 24) {
            return GgufValidationResult(
                isValid = false,
                errorMessage = "File is too small to be a valid GGUF binary (${file.length()} bytes)"
            )
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val headerBytes = ByteArray(24)
                raf.readFully(headerBytes)

                val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

                // Read magic
                val magic = buffer.int
                if (magic != GGUF_MAGIC) {
                    val hexMagic = String.format("0x%08X", magic)
                    return GgufValidationResult(
                        isValid = false,
                        errorMessage = "Invalid GGUF magic header: $hexMagic (Expected 0x46554747)"
                    )
                }

                // Read version
                val version = buffer.int
                if (version < 2 || version > 3) {
                    return GgufValidationResult(
                        isValid = false,
                        version = version,
                        errorMessage = "Unsupported GGUF version $version (Supported: v2, v3)"
                    )
                }

                val tensorCount = buffer.long
                val metadataCount = buffer.long

                GgufValidationResult(
                    isValid = true,
                    version = version,
                    tensorCount = tensorCount,
                    metadataCount = metadataCount,
                    architecture = "llama"
                )
            }
        } catch (e: Exception) {
            GgufValidationResult(
                isValid = false,
                errorMessage = "Error reading GGUF header: ${e.message}"
            )
        }
    }

    /**
     * Calculates SHA256 checksum of a file.
     */
    fun calculateSha256(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
