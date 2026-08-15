package io.androllm.engine.compat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Contract tests for [ContainerMetadataReader] against SYNTHETIC `.litertlm`
 * containers built byte-by-byte (magic + version + FlatBuffers header +
 * sections). This pins the reader's assumptions about the container layout:
 * the magic, the version, the flatbuffer field offsets, the zlib tokenizer
 * section format, and the bounds checks.
 */
class ContainerMetadataReaderTest {

    private fun varint(value: Long, out: ByteArrayOutputStream) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wire: Int) = (field shl 3) or wire

    /** LlmMetadata proto with qwen3 model type, max tokens, stop token, template. */
    private fun qwenMetadataProto(): ByteArray {
        val out = ByteArrayOutputStream()
        // field 5: max_num_tokens = 4096
        out.write(tag(5, 0)); varint(4096L, out)
        // field 6: llm_model_type oneof member qwen3 (sub-message field 5, empty)
        out.write(tag(6, 2)); out.write(2)
        out.write(tag(5, 2)); out.write(0)
        // field 2: stop_tokens -> TokenUnion { token_str: "<|im_end|>" }
        val stopBytes = "<|im_end|>".toByteArray(Charsets.UTF_8)
        val union = ByteArrayOutputStream()
        union.write(tag(2, 2)); union.write(stopBytes.size); union.write(stopBytes)
        out.write(tag(2, 2)); out.write(union.size()); union.writeTo(out)
        // field 7: jinja_prompt_template
        val template = ChatTemplates.qwen.toByteArray(Charsets.UTF_8)
        out.write(tag(7, 2)); varint(template.size.toLong(), out); out.write(template)
        return out.toByteArray()
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun leInt(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun leU16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte()
    )

    private fun leLong(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((value ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    /**
     * Builds the FlatBuffers header for N sections, matching the reader's
     * exact layout (`litertlm_header_schema.fbs` as read by [FlatBufferReader],
     * verified against real containers produced by litertlm_core.py):
     *
     *   pos 0   : root uoffset (-> root table at 12)
     *   pos 4   : root vtable (vtableSize 8, tableSize 12, f0 = 8, f1 = 4)
     *   pos 12  : root table (soffset 8 -> vtable at 4; f1 @16 = uoffset to
     *             SectionMetadata table)
     *   pos 20  : SectionMetadata vtable (vtableSize 6, tableSize 8, f0 = 4)
     *   pos 26  : SectionMetadata table (soffset 6 -> vtable at 20; f0 @30 =
     *             uoffset to the objects vector)
     *   pos 34  : objects vector (count, then N uoffsets relative to each
     *             uoffset's own position)
     *   objects : per section: vtable (12 bytes: [12,32,28,16,8,7]) then table
     *             (32 bytes: soffset 12, pad 3, data_type u8 @7, end u64 @8,
     *             begin u64 @16, items @28 = 0)
     *
     * begin/end are absolute FILE offsets. All other offsets are buffer
     * positions (file = buffer + 32).
     */
    private fun flatBufferHeader(
        sections: List<Triple<Long, Long, Int>>
    ): ByteArray {
        val n = sections.size
        val h = ByteArrayOutputStream()
        h.write(leInt(12))                                   // 0: root uoffset
        h.write(leU16(8)); h.write(leU16(12)); h.write(leU16(8)); h.write(leU16(4)) // 4: root vtable
        h.write(leInt(8))                                    // 12: root table soffset -> vtable at 4
        h.write(leInt(26 - 16))                              // 16: f1 uoffset -> SectionMetadata table at 26
        h.write(leU16(6)); h.write(leU16(8)); h.write(leU16(4)) // 20: SectionMetadata vtable
        h.write(leInt(6))                                    // 26: SectionMetadata table soffset -> vtable at 20
        h.write(leInt(34 - 30))                              // 30: f0 uoffset -> objects vector at 34
        h.write(leInt(n))                                    // 34: vector count
        for (i in 0 until n) {
            val tablePos = 38 + 4 * n + 44 * i + 12
            h.write(leInt(tablePos - (34 + 4 + 4 * i)))
        }
        for (i in 0 until n) {
            h.write(leU16(12)); h.write(leU16(32)); h.write(leU16(28))
            h.write(leU16(16)); h.write(leU16(8)); h.write(leU16(7)) // object vtable
            h.write(leInt(12))                               // table soffset -> vtable 12 back
            h.write(ByteArray(3))                            // padding before data_type
            h.write(byteArrayOf(sections[i].third.toByte())) // f3 data_type u8 @+7
            h.write(leLong(sections[i].second))              // f2 end_offset u64 @+8
            h.write(leLong(sections[i].first))               // f1 begin_offset u64 @+16
            h.write(leInt(0))                                // f0 items (absent) @+28
            h.write(ByteArray(4))                            // pad table to 32 bytes
        }
        return h.toByteArray()
    }

    private fun buildContainer(
        major: Int = 1,
        sections: List<Triple<Int, ByteArray, Int>>
    ): ByteArray {
        val magic = ByteArray(24)
        "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(magic, 0)
        magic[8] = major.toByte() // major version at offset 8

        // Header size depends only on the section COUNT (offsets are
        // fixed-size), so the section start position is known up front.
        // 38 fixed + 4n vector uoffsets + 44n object records (12+32 bytes each).
        val headerSize = 38 + 48 * sections.size
        // Sanity: the generated header must match the predicted size.
        check(headerSize == flatBufferHeader(List(sections.size) { Triple(0L, 0L, 0) }).size)
        var cursor = 32L + headerSize
        data class Placed(val begin: Long, val end: Long, val type: Int, val data: ByteArray)
        val placements = sections.map { (type, data, _) ->
            val begin = cursor
            cursor = align8(cursor + data.size)
            Placed(begin, begin + data.size, type, data)
        }
        val header = flatBufferHeader(placements.map { Triple(it.begin, it.end, it.type) })

        val out = ByteArrayOutputStream()
        out.write(magic)
        out.write(leLong(32L + header.size))
        out.write(header)
        placements.forEach { p ->
            while (out.size() < p.begin) out.write(0)
            out.write(p.data)
        }
        return out.toByteArray()
    }

    private fun align8(v: Long): Long = (v + 7) and -8L

    @Test
    fun `parses metadata and tokenizer sections`() {
        val proto = qwenMetadataProto()
        val tokenizerJson = "{\"model_type\":\"qwen2\",\"added_tokens\":[]}".toByteArray()
        val compressed = ByteArray(8 + zlib(tokenizerJson).size)
        // [u64 uncompressed size][zlib stream]
        val zlibBytes = zlib(tokenizerJson)
        val hfSection = ByteArray(8 + zlibBytes.size)
        leLong(tokenizerJson.size.toLong()).copyInto(hfSection, 0)
        zlibBytes.copyInto(hfSection, 8)

        val bytes = buildContainer(
            sections = listOf(
                Triple(FlatBufferReader.DATA_TYPE_LLM_METADATA, proto, 0),
                Triple(FlatBufferReader.DATA_TYPE_HF_TOKENIZER_ZLIB, hfSection, 0)
            )
        )
        val contents = ContainerMetadataReader.parse(bytes)
        assertEquals("qwen3", contents.metadata.modelTypeName)
        assertEquals(4096, contents.metadata.maxNumTokens)
        assertEquals(listOf("<|im_end|>"), contents.metadata.stopTokens)
        assertEquals(ChatTemplates.qwen, contents.metadata.jinjaPromptTemplate)
        assertEquals(TokenizerKind.BPE, contents.hfTokenizer?.kind)
        assertArrayEquals(tokenizerJson, contents.hfTokenizer?.bytes)
        assertTrue(contents.hasAnyTokenizer)
    }

    @Test
    fun `parses packed TokenIds start token`() {
        // Real litertlm containers encode TokenUnion.token_ids with the
        // proto3 packed repeated-int32 form (length-delimited varints),
        // which must not be misread as a non-packed field.
        val out = ByteArrayOutputStream()
        // field 5: max_num_tokens = 128
        out.write(tag(5, 0)); varint(128L, out)
        // field 6: llm_model_type = qwen3
        out.write(tag(6, 2)); out.write(2)
        out.write(tag(5, 2)); out.write(0)
        // field 1: start_token -> TokenUnion.token_ids (field 1)
        //   TokenIds.ids (field 1) packed: varint 151643 = db a0 09
        val tokenIds = byteArrayOf(0x0a.toByte(), 0x03, 0xdb.toByte(), 0xa0.toByte(), 0x09)
        val union = ByteArrayOutputStream()
        union.write(tag(1, 2)); union.write(tokenIds.size); union.write(tokenIds)
        out.write(tag(1, 2)); out.write(union.size()); union.writeTo(out)
        out.write(tag(7, 2))
        val template = ChatTemplates.qwen.toByteArray(Charsets.UTF_8)
        varint(template.size.toLong(), out); out.write(template)

        val bytes = buildContainer(
            sections = listOf(Triple(FlatBufferReader.DATA_TYPE_LLM_METADATA, out.toByteArray(), 0))
        )
        val contents = ContainerMetadataReader.parse(bytes)
        assertEquals("qwen3", contents.metadata.modelTypeName)
        assertEquals(128, contents.metadata.maxNumTokens)
    }

    @Test
    fun `rejects bad magic`() {
        val bytes = buildContainer(
            sections = listOf(Triple(FlatBufferReader.DATA_TYPE_LLM_METADATA, qwenMetadataProto(), 0))
        )
        val mutated = bytes.copyOf()
        mutated[0] = 'X'.code.toByte()
        val e = assertThrows(ModelCompatibilityException::class.java) {
            ContainerMetadataReader.parse(mutated)
        }
        assertTrue(e.message!!.contains("magic"))
    }

    @Test
    fun `rejects unsupported version`() {
        val bytes = buildContainer(
            major = 2,
            sections = listOf(Triple(FlatBufferReader.DATA_TYPE_LLM_METADATA, qwenMetadataProto(), 0))
        )
        val e = assertThrows(ModelCompatibilityException::class.java) {
            ContainerMetadataReader.parse(bytes)
        }
        assertTrue(e.message!!.contains("version"))
    }

    @Test
    fun `rejects truncated file`() {
        val bytes = buildContainer(
            sections = listOf(Triple(FlatBufferReader.DATA_TYPE_LLM_METADATA, qwenMetadataProto(), 0))
        )
        val e = assertThrows(ModelCompatibilityException::class.java) {
            ContainerMetadataReader.parse(bytes.copyOfRange(0, 20))
        }
        assertTrue(e.message!!.contains("too small"))
    }

    @Test
    fun `missing metadata section fails with actionable message`() {
        val bytes = buildContainer(
            sections = listOf(
                Triple(FlatBufferReader.DATA_TYPE_TFLITE_MODEL, ByteArray(16), 0)
            )
        )
        val e = assertThrows(ModelCompatibilityException::class.java) {
            ContainerMetadataReader.parse(bytes)
        }
        assertTrue(e.message!!.contains("metadata"))
    }

    @Test
    fun `corrupt zlib tokenizer fails`() {
        val proto = qwenMetadataProto()
        // Declares 100 bytes of uncompressed content but holds non-zlib junk.
        val badZlib = ByteArray(8 + 12)
        leLong(100L).copyInto(badZlib, 0)
        badZlib[8] = 0x01
        val content = buildContainer(
            sections = listOf(
                Triple(FlatBufferReader.DATA_TYPE_LLM_METADATA, proto, 0),
                Triple(FlatBufferReader.DATA_TYPE_HF_TOKENIZER_ZLIB, badZlib, 0)
            )
        )
        assertThrows(ModelCompatibilityException::class.java) {
            ContainerMetadataReader.parse(content)
        }
    }
}