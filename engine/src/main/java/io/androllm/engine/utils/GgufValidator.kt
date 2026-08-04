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
    val contextLength: Long = 0,
    val license: String = "",
    val generalName: String = "",
    val fileType: String = "",
    val errorMessage: String? = null
)

/**
 * Binary validator for GGUF model files.
 *
 * Reads the GGUF v2/v3 header plus the metadata KV section (used to enrich
 * model records with architecture, context length, license, and file type).
 */
object GgufValidator {

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" in ASCII bytes

    private const val VALUE_UINT8 = 0
    private const val VALUE_INT8 = 1
    private const val VALUE_UINT16 = 2
    private const val VALUE_INT16 = 3
    private const val VALUE_UINT32 = 4
    private const val VALUE_INT32 = 5
    private const val VALUE_FLOAT32 = 6
    private const val VALUE_BOOL = 7
    private const val VALUE_STRING = 8
    private const val VALUE_ARRAY = 9
    private const val VALUE_UINT64 = 10
    private const val VALUE_INT64 = 11
    private const val VALUE_FLOAT64 = 12

    private const val MAX_READ_BYTES = 1 shl 24 // 16 MB cap for string values
    private const val MAX_METADATA_ENTRIES = 100_000L

    // Mirrors llama_ftype in llama.h (values used by the current vendored tree)
    private val FTYPE_NAMES = mapOf(
        0 to "ALL_F32", 1 to "MOSTLY_F16", 2 to "MOSTLY_Q4_0", 3 to "MOSTLY_Q4_1",
        7 to "MOSTLY_Q8_0", 8 to "MOSTLY_Q5_0", 9 to "MOSTLY_Q5_1",
        10 to "MOSTLY_Q2_K", 11 to "MOSTLY_Q3_K_S", 12 to "MOSTLY_Q3_K_M", 13 to "MOSTLY_Q3_K_L",
        14 to "MOSTLY_Q4_K_S", 15 to "MOSTLY_Q4_K_M", 16 to "MOSTLY_Q5_K_S", 17 to "MOSTLY_Q5_K_M",
        18 to "MOSTLY_Q6_K", 19 to "MOSTLY_IQ2_XXS", 20 to "MOSTLY_IQ2_XS", 21 to "MOSTLY_Q2_K_S",
        22 to "MOSTLY_IQ3_XS", 23 to "MOSTLY_IQ3_XXS", 24 to "MOSTLY_IQ1_S", 25 to "MOSTLY_IQ4_NL",
        26 to "MOSTLY_IQ3_S", 27 to "MOSTLY_IQ3_M", 28 to "MOSTLY_IQ2_S", 29 to "MOSTLY_IQ2_M",
        30 to "MOSTLY_IQ4_XS", 31 to "MOSTLY_IQ1_M", 32 to "MOSTLY_BF16",
        36 to "MOSTLY_TQ1_0", 37 to "MOSTLY_TQ2_0", 38 to "MOSTLY_MXFP4_MOE", 39 to "MOSTLY_NVFP4",
        40 to "MOSTLY_Q1_0", 41 to "MOSTLY_Q2_0",
        1024 to "GUESSED"
    )

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

                val metadata = readMetadata(raf, metadataCount)
                val architecture = metadata["general.architecture"] ?: "unknown"
                val contextLength = metadata["$architecture.context_length"]
                    ?: metadata["llama.context_length"]

                GgufValidationResult(
                    isValid = true,
                    version = version,
                    tensorCount = tensorCount,
                    metadataCount = metadataCount,
                    architecture = architecture,
                    contextLength = contextLength?.toLongOrNull() ?: 0,
                    license = metadata["general.license"] ?: "",
                    generalName = metadata["general.name"] ?: "",
                    fileType = metadata["general.file_type"]?.toIntOrNull()?.let(::ftypeName) ?: ""
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

    private fun ftypeName(code: Int): String = FTYPE_NAMES[code] ?: "UNKNOWN_$code"

    /**
     * Best-effort parse of the metadata KV section. Returns whatever was
     * read before any malformed entry or I/O error; never throws.
     */
    private fun readMetadata(raf: RandomAccessFile, count: Long): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var remaining = minOf(count, MAX_METADATA_ENTRIES)
        while (remaining > 0) {
            remaining--
            val key = try { readString(raf) } catch (e: Exception) { return out }
            if (key == null) return out
            val type = try { readU32(raf) } catch (e: Exception) { return out }
            if (type == null) return out
            val value = try { readValue(raf, type.toInt()) } catch (e: Exception) { return out }
            if (value == null) return out
            out[key] = value
        }
        return out
    }

    private fun readBytes(raf: RandomAccessFile, n: Long): ByteArray? {
        if (n < 0 || n > MAX_READ_BYTES) return null
        val b = ByteArray(n.toInt())
        raf.readFully(b)
        return b
    }

    private fun readU32(raf: RandomAccessFile): Long? =
        readBytes(raf, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL }

    private fun readU64(raf: RandomAccessFile): Long? =
        readBytes(raf, 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long }

    private fun readString(raf: RandomAccessFile): String? {
        val len = readU64(raf) ?: return null
        val bytes = readBytes(raf, len) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun readValue(raf: RandomAccessFile, type: Int): String? = when (type) {
        VALUE_UINT8 -> readBytes(raf, 1)?.let { (it[0].toInt() and 0xFF).toString() }
        VALUE_INT8 -> readBytes(raf, 1)?.let { it[0].toInt().toString() }
        VALUE_UINT16 -> readBytes(raf, 2)?.let { (ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xFFFF).toString() }
        VALUE_INT16 -> readBytes(raf, 2)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt().toString() }
        VALUE_UINT32 -> readBytes(raf, 4)?.let { (ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL).toString() }
        VALUE_INT32 -> readBytes(raf, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong().toString() }
        VALUE_FLOAT32 -> readBytes(raf, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getFloat().toString() }
        VALUE_BOOL -> readBytes(raf, 1)?.let { if (it[0].toInt() != 0) "true" else "false" }
        VALUE_STRING -> readString(raf)
        VALUE_UINT64 -> readU64(raf)?.toString()
        VALUE_INT64 -> readBytes(raf, 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long.toString() }
        VALUE_FLOAT64 -> readBytes(raf, 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getDouble().toString() }
        VALUE_ARRAY -> {
            val elementType = readU32(raf) ?: return null
            val elementCount = readU64(raf) ?: return null
            val items = mutableListOf<String>()
            for (i in 0 until elementCount) {
                if (items.size >= 256) break
                val item = readValue(raf, elementType.toInt()) ?: return items.joinToString(",")
                items.add(item)
            }
            items.joinToString(",")
        }
        else -> null
    }
}
