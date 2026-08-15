package io.androllm.core.models.gguf

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One tensor entry from the GGUF tensor index.
 */
data class GgufTensor(
    val name: String,
    val dimensions: LongArray,
    val typeId: Int
) {
    val type: GgufType? get() = GgufType.byId(typeId)
}

/**
 * Fully parsed GGUF file: header, metadata KV section, and the tensor index.
 */
data class GgufRead(
    val version: Int,
    val tensorCount: Long,
    val kv: Map<String, String>,
    val tensors: List<GgufTensor>,
    val fileSize: Long
) {
    /** `general.architecture`, or "unknown" when absent. */
    val architecture: String get() = kv["general.architecture"] ?: "unknown"

    fun int(key: String): Long? = kv[key]?.toLongOrNull()
}

/**
 * Pure-JVM reader for GGUF v2/v3 model files (the format the vendored
 * llama.cpp loads). Used by the model catalog to derive architecture,
 * quantization, tokenizer, chat template and MoE geometry straight from the
 * file bytes — never from the filename. Replaces the removed
 * `core:localruntime` GGUF parser.
 */
object GgufReader {

    const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian

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

    /** Hard cap for a single metadata value read (16 MB). */
    private const val MAX_VALUE_BYTES = 1L shl 24

    /** Hard cap for metadata entries and tensor index entries. */
    private const val MAX_ENTRIES = 200_000L

    /**
     * Huge tokenizer payloads are never useful to the catalog; skipping them
     * keeps metadata reads fast even on multi-GB vocabularies.
     */
    private val SKIP_KEYS = setOf(
        "tokenizer.ggml.tokens",
        "tokenizer.ggml.scores",
        "tokenizer.ggml.token_type",
        "tokenizer.ggml.merges"
    )

    /**
     * Parses [file]. Throws [IOException] (or its subclass
     * [InvalidGgufException]) when the file is not a readable GGUF.
     */
    fun parse(file: File): GgufRead {
        if (!file.exists() || !file.canRead() || file.length() < 24) {
            throw IOException("Not a readable GGUF file: ${file.path}")
        }
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteBuffer.wrap(ByteArray(24)).order(ByteOrder.LITTLE_ENDIAN)
            raf.readFully(header.array())
            val magic = header.int
            if (magic != GGUF_MAGIC) {
                throw InvalidGgufException("Invalid GGUF magic: 0x${magic.toString(16).uppercase()}")
            }
            val version = header.int
            if (version < 2 || version > 3) {
                throw InvalidGgufException("Unsupported GGUF version $version (supported: 2, 3)")
            }
            val tensorCount = header.long
            val metadataCount = header.long
            if (metadataCount < 0 || metadataCount > MAX_ENTRIES) {
                throw InvalidGgufException("Suspicious metadata entry count: $metadataCount")
            }
            val kv = readMetadata(raf, metadataCount)
            val tensors = readTensorIndex(raf, tensorCount)
            return GgufRead(
                version = version,
                tensorCount = tensorCount,
                kv = kv,
                tensors = tensors,
                fileSize = file.length()
            )
        }
    }

    private fun readMetadata(raf: RandomAccessFile, count: Long): Map<String, String> {
        val out = HashMap<String, String>()
        var remaining = count
        while (remaining-- > 0) {
            val key = readString(raf) ?: break
            val type = readU32(raf)?.toInt() ?: break
            if (key in SKIP_KEYS) {
                if (!skipValue(raf, type)) break
                continue
            }
            val value = readValue(raf, type) ?: break
            out[key] = value
        }
        return out
    }

    private fun readTensorIndex(raf: RandomAccessFile, count: Long): List<GgufTensor> {
        if (count < 0 || count > MAX_ENTRIES) {
            throw InvalidGgufException("Suspicious tensor entry count: $count")
        }
        val out = ArrayList<GgufTensor>(minOf(count, 100_000L).toInt())
        var remaining = count
        while (remaining-- > 0) {
            val name = readString(raf) ?: break
            val nDims = readU32(raf)?.toInt() ?: break
            if (nDims < 0 || nDims > 8) throw InvalidGgufException("Invalid tensor dimension count: $nDims")
            val dims = LongArray(nDims)
            for (i in 0 until nDims) {
                dims[i] = readU64(raf) ?: break
            }
            val typeId = readU32(raf)?.toInt() ?: break
            val offset = readU64(raf) ?: break
            out += GgufTensor(name = name, dimensions = dims, typeId = typeId)
        }
        return out
    }

    private fun readBytes(raf: RandomAccessFile, n: Long): ByteArray? {
        if (n < 0 || n > MAX_VALUE_BYTES) return null
        val b = ByteArray(n.toInt())
        raf.readFully(b)
        return b
    }

    private fun readU32(raf: RandomAccessFile): Long? =
        readBytes(raf, 4)?.let { b ->
            (ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
        }

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
        VALUE_UINT16 -> readBytes(raf, 2)?.let { (ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF).toString() }
        VALUE_INT16 -> readBytes(raf, 2)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt().toString() }
        VALUE_UINT32 -> readU32(raf)?.toString()
        VALUE_INT32 -> readBytes(raf, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int.toLong().toString() }
        VALUE_FLOAT32 -> readBytes(raf, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float.toString() }
        VALUE_BOOL -> readBytes(raf, 1)?.let { if (it[0].toInt() != 0) "true" else "false" }
        VALUE_STRING -> readString(raf)
        VALUE_UINT64 -> readU64(raf)?.toString()
        VALUE_INT64 -> readBytes(raf, 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long.toString() }
        VALUE_FLOAT64 -> readBytes(raf, 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).double.toString() }
        VALUE_ARRAY -> {
            val elementType = readU32(raf)?.toInt() ?: return null
            val elementCount = readU64(raf) ?: return null
            val items = ArrayList<String>(minOf(elementCount, 256L).toInt())
            for (i in 0 until elementCount) {
                val item = readValue(raf, elementType) ?: break
                items += item
                if (items.size >= 256) break
            }
            items.joinToString(",")
        }
        else -> {
            skipValue(raf, type)
            null
        }
    }

    /** Skips a value of [type] without reading it (used for huge payloads). */
    private fun skipValue(raf: RandomAccessFile, type: Int): Boolean {
        return when (type) {
            VALUE_UINT8, VALUE_INT8, VALUE_BOOL -> readBytes(raf, 1) != null
            VALUE_UINT16, VALUE_INT16 -> readBytes(raf, 2) != null
            VALUE_UINT32, VALUE_INT32, VALUE_FLOAT32 -> readBytes(raf, 4) != null
            VALUE_UINT64, VALUE_INT64, VALUE_FLOAT64 -> readBytes(raf, 8) != null
            VALUE_STRING -> {
                val len = readU64(raf) ?: return false
                readBytes(raf, len) != null
            }
            VALUE_ARRAY -> {
                val elementType = readU32(raf)?.toInt() ?: return false
                val elementCount = readU64(raf) ?: return false
                var remaining = elementCount
                while (remaining-- > 0) {
                    if (!skipValue(raf, elementType)) return false
                }
                true
            }
            else -> false
        }
    }
}

/**
 * Thrown when a file has the wrong magic/version for a GGUF.
 */
class InvalidGgufException(message: String) : IOException(message)
