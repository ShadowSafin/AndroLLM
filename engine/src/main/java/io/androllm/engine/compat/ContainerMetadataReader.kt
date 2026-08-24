package io.androllm.engine.compat

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads the metadata and tokenizer sections of a `.litertlm` container file.
 *
 * File layout (litertlm_header_schema.fbs / litertlm_core.py):
 *
 *   offset 0    : "LITERTLM" magic (8 ASCII bytes)
 *   offset 8    : major version (u32 LE), minor (u32 LE), patch (u32 LE)
 *   offset 24   : header end location (u64 LE — absolute file offset of the
 *                 end of the flatbuffer header; informational)
 *   offset 32   : FlatBuffers LiteRTLMMetaData root (sections list)
 *
 * Sections are 16 KiB aligned; each section's [begin, end) offsets are
 * absolute file offsets. This reader extracts:
 *   - the LlmMetadataProto section (family, template, stop tokens, sampler),
 *   - the HF_Tokenizer_Zlib section (tokenizer.json, zlib-compressed),
 *   - the SP_Tokenizer section (tokenizer.model, raw).
 *
 * Pure JVM/Android (no native calls), bounds-checked, unit-tested with
 * synthetic containers built by the test suite.
 */
object ContainerMetadataReader {

    /** The 8-byte ASCII container magic. */
    const val MAGIC = "LITERTLM"

    /** Flatbuffers root offset inside the file. */
    private const val HEADER_BEGIN_BYTE_OFFSET = 32

    /** Container format version (1.x.y) — minor bumps add sections. */
    private const val SUPPORTED_MAJOR = 1

    /**
     * Parsed metadata cache: avoids re-reading the container header on
     * repeated loads of the same file (e.g. backend switching, self-test
     * retries). Keyed by canonical file path. Entries are evicted when the
     * model is unloaded to prevent stale metadata.
     *
     * Uses a synchronized LinkedHashMap for LRU eviction without circular
     * references.
     */
    private val metadataCache = object : LinkedHashMap<String, ContainerContents>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ContainerContents>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private const val MAX_CACHE_SIZE = 4

    data class ContainerContents(
        val metadata: ContainerMetadata,
        val hfTokenizer: EmbeddedTokenizer?,
        val spTokenizer: EmbeddedTokenizer?
    ) {
        val embeddedTokenizer: EmbeddedTokenizer?
            get() = hfTokenizer ?: spTokenizer

        val hasAnyTokenizer: Boolean get() = hfTokenizer != null || spTokenizer != null
    }

    /**
     * Reads and parses [file]. Throws [ModelCompatibilityException] with an
     * exact message when the file is not a valid container or its metadata is
     * unreadable.
     *
     * Only the flatbuffer header and the metadata/tokenizer section ranges are
     * read into memory; the (potentially hundreds of MB) model payload section
     * is never touched.
     */
    fun read(file: File): ContainerContents {
        // Check cache first (avoids re-parsing the header for repeated loads)
        val canonicalPath = file.canonicalPath
        metadataCache[canonicalPath]?.let { return it }

        if (!file.exists()) {
            throw ModelCompatibilityException("Model file not found: ${file.absolutePath}")
        }
        if (file.length() < HEADER_BEGIN_BYTE_OFFSET + 4) {
            throw ModelCompatibilityException(
                "Model file too small to be a LiteRT container: ${file.absolutePath}"
            )
        }
        val sections = readSections(file)

        var metadataBytes: ByteArray? = null
        var hfZlib: ByteArray? = null
        var spBytes: ByteArray? = null
        for (section in sections) {
            when (section.dataType) {
                FlatBufferReader.DATA_TYPE_LLM_METADATA -> metadataBytes = readSectionBytes(file, section, MAX_METADATA_BYTES)
                FlatBufferReader.DATA_TYPE_HF_TOKENIZER_ZLIB -> hfZlib = readSectionBytes(file, section, MAX_TOKENIZER_BYTES)
                FlatBufferReader.DATA_TYPE_SP_TOKENIZER -> spBytes = readSectionBytes(file, section, MAX_TOKENIZER_BYTES)
                else -> {}
            }
        }

        if (metadataBytes == null) {
            throw ModelCompatibilityException(
                "Model metadata section (LlmMetadataProto) missing from container — cannot detect model family"
            )
        }
        val metadata = try {
            ProtoWireReader.read(metadataBytes)
        } catch (e: IllegalArgumentException) {
            throw ModelCompatibilityException("Corrupt LlmMetadata in container: ${e.message}", e)
        }

        val hfTokenizer = hfZlib?.let { decompressHfTokenizer(it) }
        val spTokenizer = spBytes?.let { EmbeddedTokenizer(TokenizerKind.SENTENCEPIECE, it) }
        val result = ContainerContents(metadata, hfTokenizer, spTokenizer)

        // Cache the result for subsequent loads of the same file
        metadataCache[canonicalPath] = result
        return result
    }

    /** Evicts the metadata cache entry for the given file. */
    fun evictCache(file: File) {
        metadataCache.remove(file.canonicalPath)
    }

    /** Clears the entire metadata cache. */
    fun clearCache() {
        metadataCache.clear()
    }

    /**
     * Parses the flatbuffer header from a small bounded read of [file]. If the
     * header is bigger than the initial window the window is doubled, so the
     * whole file is never loaded into memory.
     */
    private fun readSections(file: File): List<SectionObject> {
        var window = INITIAL_HEADER_WINDOW
        while (true) {
            val bytes = try {
                readRange(file, 0, window.toLong())
            } catch (e: IOException) {
                throw ModelCompatibilityException("Cannot read model file ${file.absolutePath}: ${e.message}", e)
            }
            try {
                return FlatBufferReader(ByteBuffer.wrap(bytes), HEADER_BEGIN_BYTE_OFFSET).rootSectionObjects()
            } catch (e: IllegalArgumentException) {
                throw ModelCompatibilityException("Corrupt .litertlm header: ${e.message}", e)
            } catch (e: IndexOutOfBoundsException) {
                val fileLength = file.length()
                if (window >= fileLength || window >= MAX_HEADER_WINDOW) {
                    throw ModelCompatibilityException(
                        "Corrupt .litertlm header: truncated or out-of-bounds sections", e
                    )
                }
                window = (window * 2).coerceAtMost(MAX_HEADER_WINDOW)
            }
        }
    }

    /** Streams exactly the [begin, end) range of [file] into memory. */
    private fun readSectionBytes(file: File, section: SectionObject, maxBytes: Int): ByteArray {
        val begin = section.beginOffset
        val end = section.endOffset
        val fileLength = file.length()
        if (begin < 0 || end > fileLength || end < begin) {
            throw ModelCompatibilityException(
                "Container section [${section.dataType}] out of bounds ($begin..$end of $fileLength)"
            )
        }
        val length = end - begin
        if (length > maxBytes.toLong()) {
            throw ModelCompatibilityException(
                "Container section [${section.dataType}] too large ($length bytes)"
            )
        }
        try {
            return readRange(file, begin, length)
        } catch (e: IOException) {
            throw ModelCompatibilityException("Cannot read model file ${file.absolutePath}: ${e.message}", e)
        }
    }

    private fun readRange(file: File, offset: Long, length: Long): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val out = ByteArray(length.toInt())
            raf.readFully(out)
            return out
        }
    }

    /** Parses container bytes (exposed for tests). */
    fun parse(bytes: ByteArray): ContainerContents {
        if (bytes.size < HEADER_BEGIN_BYTE_OFFSET + 4) {
            throw ModelCompatibilityException("Container is too small (${bytes.size} bytes)")
        }
        val magic = String(bytes, 0, 8, Charsets.US_ASCII)
        if (magic != MAGIC) {
            throw ModelCompatibilityException(
                "Not a LiteRT container: expected magic \"$MAGIC\" at offset 0, found \"$magic\""
            )
        }
        val major = leInt(bytes, 8)
        if (major != SUPPORTED_MAJOR) {
            throw ModelCompatibilityException(
                "Unsupported .litertlm container version $major (supported: $SUPPORTED_MAJOR.x)"
            )
        }

        val sections = try {
            FlatBufferReader(
                ByteBuffer.wrap(bytes),
                HEADER_BEGIN_BYTE_OFFSET
            ).rootSectionObjects()
        } catch (e: IllegalArgumentException) {
            throw ModelCompatibilityException("Corrupt .litertlm header: ${e.message}", e)
        } catch (e: IndexOutOfBoundsException) {
            throw ModelCompatibilityException("Corrupt .litertlm header: truncated or out-of-bounds sections", e)
        }

        var metadataBytes: ByteArray? = null
        var hfZlib: ByteArray? = null
        var spBytes: ByteArray? = null
        for (section in sections) {
            val data = slice(bytes, section)
            when (section.dataType) {
                FlatBufferReader.DATA_TYPE_LLM_METADATA -> metadataBytes = data
                FlatBufferReader.DATA_TYPE_HF_TOKENIZER_ZLIB -> hfZlib = data
                FlatBufferReader.DATA_TYPE_SP_TOKENIZER -> spBytes = data
                else -> {}
            }
        }

        if (metadataBytes == null) {
            throw ModelCompatibilityException(
                "Model metadata section (LlmMetadataProto) missing from container — cannot detect model family"
            )
        }
        val metadata = try {
            ProtoWireReader.read(metadataBytes)
        } catch (e: IllegalArgumentException) {
            throw ModelCompatibilityException("Corrupt LlmMetadata in container: ${e.message}", e)
        }

        val hfTokenizer = hfZlib?.let { decompressHfTokenizer(it) }
        val spTokenizer = spBytes?.let { EmbeddedTokenizer(TokenizerKind.SENTENCEPIECE, it) }
        return ContainerContents(metadata, hfTokenizer, spTokenizer)
    }

    private fun slice(bytes: ByteArray, section: SectionObject): ByteArray {
        val begin = section.beginOffset
        val end = section.endOffset
        if (begin < 0 || end > bytes.size) {
            throw ModelCompatibilityException(
                "Container section [${section.dataType}] out of bounds ($begin..$end of ${bytes.size})"
            )
        }
        return bytes.copyOfRange(begin.toInt(), end.toInt())
    }

    /**
     * The HF tokenizer section is [8-byte little-endian uncompressed size][zlib
     * stream]. Java's Inflater consumes the zlib format natively.
     */
    private fun decompressHfTokenizer(data: ByteArray): EmbeddedTokenizer {
        if (data.size < 8) {
            throw ModelCompatibilityException("HF tokenizer section too small (${data.size} bytes)")
        }
        val uncompressedSize = leLong(data, 0)
        val compressed = data.copyOfRange(8, data.size)
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArray(uncompressedSize.toInt().coerceAtMost(MAX_TOKENIZER_BYTES))
        val resultLen = try {
            inflater.inflate(out)
            inflater.finished()
        } catch (e: DataFormatException) {
            throw ModelCompatibilityException("Corrupt zlib tokenizer in container: ${e.message}", e)
        } finally {
            inflater.end()
        }
        if (!resultLen) {
            throw ModelCompatibilityException(
                "HF tokenizer section did not decompress to its declared size ($uncompressedSize bytes)"
            )
        }
        return EmbeddedTokenizer(
            kind = TokenizerKind.BPE,
            bytes = if (uncompressedSize.toInt() == out.size) out else out.copyOfRange(0, uncompressedSize.toInt()),
            compressedBytes = compressed
        )
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leLong(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return result
    }

    private const val MAX_TOKENIZER_BYTES = 256 * 1024 * 1024

    /** Initial header window — real headers are a few hundred bytes. */
    private const val INITIAL_HEADER_WINDOW = 64 * 1024

    /** Upper bound for header retry window. */
    private const val MAX_HEADER_WINDOW = 16 * 1024 * 1024

    /** LlmMetadataProto is a few KB; cap well above that. */
    private const val MAX_METADATA_BYTES = 16 * 1024 * 1024
}