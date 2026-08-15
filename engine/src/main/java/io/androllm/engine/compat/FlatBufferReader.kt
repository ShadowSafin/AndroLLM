package io.androllm.engine.compat

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal, defensive FlatBuffers reader for the `.litertlm` container header.
 *
 * A `.litertlm` file is NOT a plain TFLite flatbuffer: it starts with the
 * `LITERTLM` magic + version, then a FlatBuffers `LiteRTLMMetaData` table
 * written at a fixed offset (32) that lists the file's sections (offsets,
 * lengths, type). This reader knows only the three tables it needs:
 *
 *   LiteRTLMMetaData { system_metadata: SystemMetadata (field 0);
 *                      section_metadata: SectionMetadata (field 1) }
 *   SectionMetadata   { objects: [SectionObject] (field 0) }
 *   SectionObject     { items: [KeyValuePair] (field 0);
 *                       begin_offset: ulong (field 1);
 *                       end_offset: ulong   (field 2);
 *                       data_type: ubyte     (field 3) }
 *
 * FlatBuffers is generated with a 1:1 schema — `data_type` is declared after
 * the offset fields, so the enum index (declaration order, 0-based) is 3.
 *
 * The header uses STANDARD FlatBuffers conventions (verified against real
 * containers produced by litertlm_core.py): the root field points to the
 * `SectionMetadata` table via a uoffset, `SectionMetadata.objects` points to
 * the section vector via a uoffset, and each vector element is a uoffset
 * relative to its own position. All offsets in this reader are relative to
 * [base] (the container's flatbuffer start, normally 32).
 *
 * This reader is bounds-checked and returns null / throws on malformed input;
 * it never touches native code and is unit-tested with synthetic containers.
 */
internal class FlatBufferReader(private val buffer: ByteBuffer, private val base: Int = 0) {

    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    companion object {
        // AnySectionDataType enum values (declaration order in
        // litertlm_header_schema.fbs). Only the ones the reader uses.
        const val DATA_TYPE_TFLITE_MODEL = 3
        const val DATA_TYPE_SP_TOKENIZER = 4
        const val DATA_TYPE_LLM_METADATA = 5
        const val DATA_TYPE_HF_TOKENIZER_ZLIB = 6
        const val DATA_TYPE_TFLITE_WEIGHTS = 7

        /** Upper bound on section count (defensive, not a format limit). */
        const val MAX_SECTIONS = 512
    }

    /** Parses the root table's field as a vector of section records. */
    fun rootSectionObjects(): List<SectionObject> {
        // Root table: 4-byte uoffset at position 0 (relative to buffer start).
        val rootOffset = int32(0)
        val tablePos = rootOffset.toLong()
        val vtable = vtableFor(tablePos)
        // LiteRTLMMetaData.section_metadata is field 1; its value is a
        // standard uoffset relative to the field position.
        val sectionMetadataField = fieldOffset(vtable, 1)
            ?: throw IllegalArgumentException("LiteRTLMMetaData.section_metadata missing")
        val sectionMetadataPos = tablePos + sectionMetadataField + int32(tablePos + sectionMetadataField)
        val sectionVtable = vtableFor(sectionMetadataPos)
        // SectionMetadata.objects is field 0; its value is a uoffset relative
        // to the field position.
        val objectsField = fieldOffset(sectionVtable, 0)
            ?: throw IllegalArgumentException("SectionMetadata.objects missing")
        val vectorPos = sectionMetadataPos + objectsField + int32(sectionMetadataPos + objectsField)
        val len = int32(vectorPos).toInt()
        if (len < 0 || len > MAX_SECTIONS) {
            throw IllegalArgumentException("implausible section count: $len")
        }
        val out = ArrayList<SectionObject>(len)
        var cursor = vectorPos + 4
        for (i in 0 until len) {
            // Vector element uoffsets are relative to their own position.
            val elemPos = cursor + int32(cursor)
            cursor += 4
            out.add(readSectionObject(elemPos))
        }
        return out
    }

    private fun readSectionObject(pos: Long): SectionObject {
        val vtable = vtableFor(pos)
        val beginField = fieldOffset(vtable, 1)
            ?: throw IllegalArgumentException("SectionObject.begin_offset missing")
        val endField = fieldOffset(vtable, 2)
            ?: throw IllegalArgumentException("SectionObject.end_offset missing")
        val dataTypeField = fieldOffset(vtable, 3)
            ?: throw IllegalArgumentException("SectionObject.data_type missing")
        val begin = uint64(pos + beginField)
        val end = uint64(pos + endField)
        if (end < begin) {
            throw IllegalArgumentException("section end ($end) before begin ($begin)")
        }
        val dataType = byteAt(pos + dataTypeField).toInt() and 0xFF
        return SectionObject(
            beginOffset = begin,
            endOffset = end,
            dataType = dataType
        )
    }

    // ------------------------------------------------------------------
    // Low-level accessors (all offsets are absolute buffer positions,
    // shifted by [base] — the container's flatbuffer start offset)
    // ------------------------------------------------------------------

    private fun byteAt(pos: Long): Byte = buffer.get(base + pos.toInt())

    private fun int32(pos: Long): Int = buffer.getInt(base + pos.toInt())

    private fun uint64(pos: Long): Long {
        val lo = buffer.getInt(base + pos.toInt())
        val hi = buffer.getInt(base + pos.toInt() + 4)
        return (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
    }

    /** Vtable position for a table located at [tablePos]. */
    private fun vtableFor(tablePos: Long): Long {
        val rel = int32(tablePos)
        return tablePos - rel
    }

    /** Offset of [fieldIndex] (0-based declaration order) from the table position, or null. */
    private fun fieldOffset(vtablePos: Long, fieldIndex: Int): Int? {
        val vtableSize = int32(vtablePos).toInt() and 0xFFFF
        val fieldSlot = 4 + fieldIndex * 2
        if (fieldSlot + 2 > vtableSize) return null
        val off = int32(vtablePos + fieldSlot).toInt() and 0xFFFF
        return if (off == 0) null else off
    }
}

/** One section entry from the `.litertlm` header. */
internal data class SectionObject(
    val beginOffset: Long,
    val endOffset: Long,
    val dataType: Int
) {
    val size: Long get() = endOffset - beginOffset
}