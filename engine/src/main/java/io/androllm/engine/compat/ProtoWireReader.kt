package io.androllm.engine.compat

/**
 * Minimal protobuf wire-format reader for the subset of
 * `litert.lm.proto.LlmMetadata` that the compatibility layer needs.
 *
 * The `.litertlm` container stores the model metadata as a raw protobuf
 * (`AnySectionDataType_LlmMetadataProto` section). The runtime reads it with
 * the compiled protobuf library; this reader parses the same wire format
 * without any protobuf dependency. Only fields used for family detection and
 * conversation configuration are decoded; unknown fields are skipped by wire
 * type. It is bounds-checked and unit-tested.
 *
 * LlmMetadata field numbers (llm_metadata.proto):
 *   1 start_token        (TokenUnion)
 *   2 stop_tokens        (repeated TokenUnion)
 *   3 prompt_templates   (PromptTemplates)
 *   4 sampler_params     (SamplerParameters)
 *   5 max_num_tokens     (int32)
 *   6 llm_model_type     (LlmModelType)
 *   7 jinja_prompt_template (string)
 *   8 channels           (repeated Channel)
 *   9 suppress_tokens    (TokenIds)
 *  10 kv_cache_init_value (int64)
 *
 * TokenUnion: 1 = token_ids (TokenIds), 2 = token_str (string)
 * TokenIds:   1 = ids (repeated int32)
 * LlmModelType oneof: 1 generic_model, 2 gemma3n, 3 function_gemma, 4 gemma3,
 *                     5 qwen3, 7 qwen2p5, 8 gemma4, 9 fast_vlm
 * Channel:    1 = channel_name (string), 2 = start (string), 3 = end (string)
 * SamplerParameters: 1 = type, 2 = k, 3 = p (float), 4 = temperature (float),
 *                    5 = seed, 6 = backend
 */
internal object ProtoWireReader {

    class Cursor(private val data: ByteArray) {
        var pos: Int = 0
        val size: Int get() = data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw IllegalArgumentException("varint too long")
            }
            throw IllegalArgumentException("truncated varint")
        }

        /** Returns (field number, wire type). */
        fun readTag(): Pair<Int, Int> {
            val v = readVarint()
            val field = (v ushr 3).toInt()
            val wire = (v and 7).toInt()
            if (field <= 0) throw IllegalArgumentException("invalid field number $field")
            return field to wire
        }

        fun readLengthDelimited(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > data.size) {
                throw IllegalArgumentException("length-delimited field out of bounds ($len at $pos/${data.size})")
            }
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun skipField(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> readLengthDelimited()
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            if (pos > data.size) throw IllegalArgumentException("field ran past end of buffer")
        }

        fun readFixed32(): Int {
            if (pos + 4 > data.size) throw IllegalArgumentException("fixed32 out of bounds")
            val v = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
    }

    fun read(data: ByteArray): ContainerMetadata {
        val cursor = Cursor(data)
        var startToken: String? = null
        val stopTokens = ArrayList<String>()
        var maxNumTokens = 0
        var modelTypeName: String? = null
        var jinjaTemplate: String? = null
        var samplerType = 0
        var samplerK = 0
        var samplerP = 0f
        var samplerTemperature = 0f
        var samplerSeed = -1
        val channels = ArrayList<ChannelSpec>()
        val suppressTokens = ArrayList<Int>()

        while (cursor.pos < cursor.size) {
            val (field, wire) = cursor.readTag()
            when (field) {
                1 -> startToken = parseTokenUnion(cursor.readLengthDelimited())?.tokenString
                2 -> stopTokens.addAll(parseTokenUnion(cursor.readLengthDelimited())?.tokenStrings ?: emptyList())
                3 -> cursor.skipField(wire) // prompt_templates (legacy, unused)
                4 -> {
                    val s = parseSamplerParams(cursor.readLengthDelimited())
                    samplerType = s.type
                    samplerK = s.k
                    samplerP = s.p
                    samplerTemperature = s.temperature
                    samplerSeed = s.seed
                }
                5 -> maxNumTokens = cursor.readVarint().toInt()
                6 -> modelTypeName = parseModelTypeName(cursor.readLengthDelimited())
                7 -> jinjaTemplate = String(cursor.readLengthDelimited(), Charsets.UTF_8)
                8 -> channels.add(parseChannel(cursor.readLengthDelimited()))
                9 -> suppressTokens.addAll(parseTokenIds(cursor.readLengthDelimited()))
                else -> cursor.skipField(wire)
            }
        }

        return ContainerMetadata(
            startToken = startToken,
            stopTokens = stopTokens,
            maxNumTokens = maxNumTokens,
            modelTypeName = modelTypeName,
            jinjaPromptTemplate = jinjaTemplate,
            samplerType = samplerType,
            samplerTopK = samplerK,
            samplerTopP = samplerP,
            samplerTemperature = samplerTemperature,
            samplerSeed = samplerSeed,
            channels = channels,
            suppressTokenIds = suppressTokens
        )
    }

    private fun parseTokenUnion(data: ByteArray): TokenUnion? {
        val cursor = Cursor(data)
        var tokenIds: List<Int> = emptyList()
        var tokenString: String? = null
        while (cursor.pos < cursor.size) {
            val (field, wire) = cursor.readTag()
            when (field) {
                1 -> tokenIds = parseTokenIds(cursor.readLengthDelimited())
                2 -> tokenString = String(cursor.readLengthDelimited(), Charsets.UTF_8)
                else -> cursor.skipField(wire)
            }
        }
        return TokenUnion(tokenIds, tokenString)
    }

    private fun parseTokenIds(data: ByteArray): List<Int> {
        val cursor = Cursor(data)
        val ids = ArrayList<Int>()
        while (cursor.pos < cursor.size) {
            val (field, wire) = cursor.readTag()
            when (field) {
                1 -> {
                    // proto3 repeated int32 defaults to PACKED encoding (field
                    // is length-delimited, holding concatenated varints). The
                    // non-packed form (a bare varint per element) is also
                    // accepted for robustness.
                    if (wire == 2) {
                        val packed = cursor.readLengthDelimited()
                        val p = Cursor(packed)
                        while (p.pos < p.size) ids.add(p.readVarint().toInt())
                    } else {
                        ids.add(cursor.readVarint().toInt())
                    }
                }
                else -> cursor.skipField(wire)
            }
        }
        return ids
    }

    /** Extracts the oneof member name of LlmModelType (the field name of the sub-message). */
    private fun parseModelTypeName(data: ByteArray): String? {
        val cursor = Cursor(data)
        while (cursor.pos < cursor.size) {
            val (field, wire) = cursor.readTag()
            when (field) {
                1 -> return "generic_model"
                2 -> return "gemma3n"
                3 -> return "function_gemma"
                4 -> return "gemma3"
                5 -> return "qwen3"
                7 -> return "qwen2p5"
                8 -> return "gemma4"
                9 -> return "fast_vlm"
                else -> cursor.skipField(wire)
            }
        }
        return null
    }

    private fun parseChannel(data: ByteArray): ChannelSpec {
        val cursor = Cursor(data)
        var name = ""
        var start = ""
        var end = ""
        while (cursor.pos < cursor.size) {
            val (field, wire) = cursor.readTag()
            when (field) {
                1 -> name = String(cursor.readLengthDelimited(), Charsets.UTF_8)
                2 -> start = String(cursor.readLengthDelimited(), Charsets.UTF_8)
                3 -> end = String(cursor.readLengthDelimited(), Charsets.UTF_8)
                else -> cursor.skipField(wire)
            }
        }
        return ChannelSpec(name, start, end)
    }

    private fun parseSamplerParams(data: ByteArray): SamplerParamsSpec {
        val cursor = Cursor(data)
        var type = 0
        var k = 0
        var p = 0f
        var temperature = 0f
        var seed = -1
        while (cursor.pos < cursor.size) {
            val (field, wire) = cursor.readTag()
            when (field) {
                1 -> type = cursor.readVarint().toInt()
                2 -> k = cursor.readVarint().toInt()
                3 -> p = Float.fromBits(cursor.readFixed32())
                4 -> temperature = Float.fromBits(cursor.readFixed32())
                5 -> seed = cursor.readVarint().toInt()
                else -> cursor.skipField(wire)
            }
        }
        return SamplerParamsSpec(type, k, p, temperature, seed)
    }

    private data class TokenUnion(val tokenIds: List<Int>, val tokenString: String?) {
        val tokenStrings: List<String>
            get() = if (tokenString != null) listOf(tokenString) else emptyList()
    }
}

/** A chat channel (e.g. the thinking channel) defined by the container. */
data class ChannelSpec(
    val channelName: String,
    val start: String,
    val end: String
)

internal data class SamplerParamsSpec(
    val type: Int,
    val k: Int,
    val p: Float,
    val temperature: Float,
    val seed: Int
)