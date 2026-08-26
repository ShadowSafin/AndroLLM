package io.androllm.engine.embedding

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/**
 * Minimal, self-contained SentencePiece **BPE** tokenizer for the Gemma family
 * tokenizers (EmbeddingGemma).
 *
 * Why this exists: the memory system's embeddings moved from the (removed)
 * llama.cpp GGUF path to a raw LiteRT interpreter running EmbeddingGemma, whose
 * `.tflite` model takes token IDs, not text. The official LiteRT-LM Kotlin
 * `EmbeddingEngine` is unreleased (main-branch only), and the classic
 * `tensorflow-lite-support` `SentencePieceTokenizer` has been gutted into a
 * stub — so this class implements the two pieces of a BPE sentencepiece model
 * that EmbeddingGemma's `tokenizer.model` actually contains:
 *
 *  1. a minimal protobuf wire-format reader for `ModelProto`
 *     (pieces = field 1, trainer_spec = field 2, normalizer_spec = field 3),
 *  2. the BPE merge algorithm (greedy highest-scoring pair merge) with
 *     identity normalization (no charsmap, no dummy prefix, no NFKC) and
 *     byte-fallback for out-of-vocabulary characters.
 *
 * The behavior is validated against the reference Python `sentencepiece`
 * implementation on the exact EmbeddingGemma `tokenizer.model`
 * (262 144 vocab, **BPE** model type, bos=2, eos=1, unk=3, byte_fallback)
 * in `SentencePieceTokenizerTest`.
 *
 * This tokenizer is intentionally tiny and read-only — it is not a general
 * sentencepiece implementation. It supports the BPE model type and assumes
 * identity normalization (which is what the Gemma 3 / EmbeddingGemma tokenizer
 * model ships with).
 *
 * Optimization: static LRU cache of parsed models (hash -> ParsedModel) avoids
 * re-parsing 262k vocab on every embedding engine init. The tokenizer.model
 * is immutable per app install; parsing is ~O(vocab) and dominates load time.
 */
class SentencePieceTokenizer(modelBytes: ByteArray) {

    private data class ParsedModel(
        val pieceToId: HashMap<String, Int>,
        val scores: FloatArray,
        val types: IntArray,
        val bosId: Int,
        val eosId: Int,
        val unkId: Int,
    )

    // Piece types (SentencePiece.Type enum).
    companion object {
        const val TYPE_NORMAL = 1
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4
        const val TYPE_UNUSED = 5
        const val TYPE_BYTE = 6

        /**
         * Static LRU cache of parsed SentencePiece models (hash -> ParsedModel).
         * Avoids re-parsing 262k vocab per embedding init. Thread-safe, max 4
         * entries (typically only one model per device, but allows A/B testing).
         */
        private const val MAX_CACHED_MODELS = 4
        private val parsedCacheLock = Any()
        private val parsedCache = object : LinkedHashMap<Int, ParsedModel>(MAX_CACHED_MODELS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ParsedModel>?): Boolean {
                return size > MAX_CACHED_MODELS
            }
        }

        /** Diagnostics — exposed for testing/profiling. */
        fun cacheStats(): String = synchronized(parsedCacheLock) { "size=${parsedCache.size}/$MAX_CACHED_MODELS" }
        fun clearCache() = synchronized(parsedCacheLock) { parsedCache.clear() }

        private fun getOrParse(modelBytes: ByteArray): ParsedModel {
            val hash = modelBytes.contentHashCode()
            synchronized(parsedCacheLock) { parsedCache[hash]?.let { return it } }
            val parsed = parseModelProtoStatic(modelBytes)
            synchronized(parsedCacheLock) { parsedCache[hash] = parsed }
            return parsed
        }

        private fun parseModelProtoStatic(data: ByteArray): ParsedModel {
            val pieces = ArrayList<String>(262144)
            val pieceScores = ArrayList<Float>(262144)
            val pieceTypes = ArrayList<Int>(262144)
            var bos = -1
            var eos = -1
            var unk = -1

            var pos = 0
            while (pos < data.size) {
                val tag = readVarintStatic(data, pos)
                pos = tag.second
                val field = tag.first ushr 3
                val wire = tag.first and 7
                when (wire) {
                    2 -> {
                        val len = readVarintStatic(data, pos)
                        pos = len.second
                        val value = data.copyOfRange(pos, pos + len.first)
                        pos += len.first
                        when (field) {
                            1 -> { // SentencePiece message
                                val p = parseSentencePieceStatic(value)
                                pieces.add(p.piece)
                                pieceScores.add(p.score)
                                pieceTypes.add(p.type)
                            }
                            2 -> { // trainer_spec (unk/bos/eos ids)
                                val spec = parseTrainerSpecStatic(value)
                                if (spec.unkId >= 0) unk = spec.unkId
                                if (spec.bosId >= 0) bos = spec.bosId
                                if (spec.eosId >= 0) eos = spec.eosId
                            }
                            else -> { /* normalizer_spec (3) and friends ignored */ }
                        }
                    }
                    else -> {
                        // skip varint / fixed32 / fixed64
                        pos = when (wire) {
                            0 -> readVarintStatic(data, pos).second
                            5 -> pos + 4
                            1 -> pos + 8
                            else -> throw IllegalArgumentException("Unsupported protobuf wire type $wire")
                        }
                    }
                }
            }

            val pieceMap = HashMap<String, Int>(pieces.size)
            val scoreArray = FloatArray(pieces.size)
            val typeArray = IntArray(pieces.size) { TYPE_NORMAL }
            for ((index, p) in pieces.withIndex()) {
                pieceMap[p] = index
                scoreArray[index] = pieceScores[index]
                typeArray[index] = pieceTypes[index]
            }
            return ParsedModel(pieceMap, scoreArray, typeArray, bos, eos, unk)
        }

        private data class SentencePiece(val piece: String, val score: Float, val type: Int)

        private fun parseSentencePieceStatic(data: ByteArray): SentencePiece {
            var piece = ""
            var score = 0f
            var type = TYPE_NORMAL
            var pos = 0
            while (pos < data.size) {
                val tag = readVarintStatic(data, pos)
                pos = tag.second
                when (tag.first ushr 3) {
                    1 -> { // piece (string)
                        val len = readVarintStatic(data, pos)
                        pos = len.second
                        piece = String(data, pos, len.first, Charsets.UTF_8)
                        pos += len.first
                    }
                    2 -> { // score (float, wire type 5)
                        score = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
                        pos += 4
                    }
                    3 -> { // type (enum, varint)
                        val v = readVarintStatic(data, pos)
                        pos = v.second
                        type = v.first
                    }
                    else -> {
                        val wire = tag.first and 7
                        pos = when (wire) {
                            0 -> readVarintStatic(data, pos).second
                            2 -> {
                                val len = readVarintStatic(data, pos)
                                len.second + len.first
                            }
                            5 -> pos + 4
                            1 -> pos + 8
                            else -> pos
                        }
                    }
                }
            }
            return SentencePiece(piece, score, type)
        }

        private data class TrainerSpec(val unkId: Int, val bosId: Int, val eosId: Int)

        private fun parseTrainerSpecStatic(data: ByteArray): TrainerSpec {
            var unk = -1
            var bos = -1
            var eos = -1
            var pos = 0
            while (pos < data.size) {
                val tag = readVarintStatic(data, pos)
                pos = tag.second
                when (tag.first ushr 3) {
                    40 -> { // unk_id
                        val v = readVarintStatic(data, pos)
                        pos = v.second
                        unk = v.first
                    }
                    41 -> { // bos_id
                        val v = readVarintStatic(data, pos)
                        pos = v.second
                        bos = v.first
                    }
                    42 -> { // eos_id
                        val v = readVarintStatic(data, pos)
                        pos = v.second
                        eos = v.first
                    }
                    else -> {
                        val wire = tag.first and 7
                        pos = when (wire) {
                            0 -> readVarintStatic(data, pos).second
                            2 -> {
                                val len = readVarintStatic(data, pos)
                                len.second + len.first
                            }
                            5 -> pos + 4
                            1 -> pos + 8
                            else -> pos
                        }
                    }
                }
            }
            return TrainerSpec(unk, bos, eos)
        }

        private fun readVarintStatic(data: ByteArray, pos: Int): Pair<Int, Int> {
            var result = 0
            var shift = 0
            var p = pos
            while (p < data.size) {
                val b = data[p].toInt() and 0xFF
                p++
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return Pair(result, p)
        }
    }

    /** piece string -> piece id. */
    private val pieceToId: HashMap<String, Int>

    /** score per piece id. */
    private val scores: FloatArray

    /** type per piece id. */
    private val types: IntArray

    private val bosId: Int
    private val eosId: Int
    private val unkId: Int

    init {
        val parsed = getOrParse(modelBytes)
        pieceToId = parsed.pieceToId
        scores = parsed.scores
        types = parsed.types
        bosId = parsed.bosId
        eosId = parsed.eosId
        unkId = parsed.unkId
    }

    constructor(modelFile: File) : this(modelFile.readBytes())

    /** Tokenizes [text] into piece ids (no bos/eos added). */
    fun encode(text: String): List<Int> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        return bpeEncode(normalized)
    }

    /** Tokenizes [text] and prepends the bos token if the model has one. */
    fun encodeWithBos(text: String): List<Int> {
        val ids = encode(text)
        return if (bosId >= 0) listOf(bosId) + ids else ids
    }

    val bosTokenId: Int get() = bosId
    val eosTokenId: Int get() = eosId
    val unkTokenId: Int get() = unkId

    // ------------------------------------------------------------------
    // Normalization (from normalizer_spec)
    // ------------------------------------------------------------------

    /**
     * Applies the model's normalizer. The EmbeddingGemma model uses identity
     * normalization with `escape_whitespaces = true`: the only transformation
     * is ASCII space ` ` -> `▁` (U+2581). No dummy prefix, no whitespace
     * collapsing, no case folding, no charsmap.
     */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(if (c == ' ') '\u2581' else c)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // BPE merge encoding
    // ------------------------------------------------------------------

    private class Symbol(
        var piece: String,
        var prev: Int,
        var next: Int,
        var freeze: Boolean,
        var byteLen: Int,
    )

    private class SymbolPair(
        val score: Float,
        val left: Int,
        val right: Int,
        val size: Int,
    )

    private fun bpeEncode(normalized: String): List<Int> {
        // 1. Split the input into initial symbols: one code point each,
        //    except USER_DEFINED pieces which match as a whole and freeze.
        val symbols = ArrayList<Symbol>()
        var i = 0
        var index = 0
        while (i < normalized.length) {
            val mblen = matchUserDefined(normalized, i)
            val freeze = mblen > 0
            val len = if (freeze) mblen else charCountAt(normalized, i)
            val piece = normalized.substring(i, i + len)
            val prev = if (index == 0) -1 else index - 1
            symbols.add(
                Symbol(
                    piece = piece,
                    prev = prev,
                    next = index + 1,
                    freeze = freeze,
                    byteLen = piece.toByteArray(Charsets.UTF_8).size,
                )
            )
            i += len
            index++
        }
        if (symbols.isNotEmpty()) symbols[symbols.size - 1].next = -1
        if (symbols.isEmpty()) return emptyList()

        // Max-heap by score; tie-break: smaller left index first.
        val agenda = PriorityQueue { a: SymbolPair, b: SymbolPair ->
            if (a.score != b.score) {
                if (a.score > b.score) -1 else 1
            } else {
                a.left - b.left
            }
        }

        fun maybeAdd(leftIdx: Int, rightIdx: Int) {
            if (leftIdx == -1 || rightIdx == -1) return
            val left = symbols[leftIdx]
            val right = symbols[rightIdx]
            if (left.freeze || right.freeze) return
            val piece = left.piece + right.piece
            val id = pieceToId[piece] ?: unkId
            if (id == unkId || isReservedId(id)) return
            agenda.add(
                SymbolPair(
                    score = scores[id],
                    left = leftIdx,
                    right = rightIdx,
                    size = left.byteLen + right.byteLen,
                )
            )
        }

        // Lookup all adjacent bigrams.
        if (symbols.size > 1) {
            var left = 0
            var right = 1
            while (right < symbols.size) {
                maybeAdd(left, right)
                left = right
                right++
            }
        }

        // Greedy merge: repeatedly merge the highest-scoring valid pair.
        while (agenda.isNotEmpty()) {
            val top = agenda.poll()
            val leftSym = symbols[top.left]
            val rightSym = symbols[top.right]
            if (leftSym.piece.isEmpty() || rightSym.piece.isEmpty()) continue
            if (leftSym.byteLen + rightSym.byteLen != top.size) continue

            // Merge right into left.
            leftSym.piece = leftSym.piece + rightSym.piece
            leftSym.byteLen += rightSym.byteLen
            leftSym.next = rightSym.next
            if (rightSym.next >= 0) symbols[rightSym.next].prev = top.left
            rightSym.piece = ""

            maybeAdd(leftSym.prev, top.left)
            maybeAdd(top.left, leftSym.next)
        }

        // 2. Emit ids walking the surviving symbols. Unknown characters are
        //    decomposed into `<0xXX>` byte pieces (byte_fallback = true).
        val out = ArrayList<Int>(symbols.size)
        var idx = 0
        while (idx != -1) {
            val sym = symbols[idx]
            if (sym.piece.isNotEmpty()) {
                val id = pieceToId[sym.piece] ?: unkId
                if (id == unkId) {
                    emitByteFallback(sym.piece, out)
                } else {
                    out.add(id)
                }
            }
            idx = sym.next
        }
        return out
    }

    /**
     * Decomposes an out-of-vocabulary character into `<0xXX>` byte pieces
     * (the model ships byte_fallback = true). Bytes without a byte piece in
     * the vocab fall back to the unk token.
     */
    private fun emitByteFallback(piece: String, out: MutableList<Int>) {
        for (b in piece.toByteArray(Charsets.UTF_8)) {
            val hex = (b.toInt() and 0xFF).toString(16).padStart(2, '0')
            out.add(pieceToId["<0x$hex>"] ?: unkId)
        }
    }

    /** Longest prefix match of a USER_DEFINED piece at [pos]; 0 when none. */
    private fun matchUserDefined(text: String, pos: Int): Int {
        var best = 0
        for ((piece, id) in pieceToId) {
            if (types[id] != TYPE_USER_DEFINED) continue
            if (piece.length > best && text.startsWith(piece, pos)) {
                best = piece.length
            }
        }
        return best
    }

    private fun charCountAt(text: String, pos: Int): Int {
        val cp = text.codePointAt(pos)
        return Character.charCount(cp)
    }

    /** BPE merges are only allowed for NORMAL / USER_DEFINED / UNUSED. */
    private fun isReservedId(id: Int): Boolean {
        val t = types.getOrElse(id) { TYPE_NORMAL }
        return t != TYPE_NORMAL && t != TYPE_USER_DEFINED && t != TYPE_UNUSED
    }

    // Instance helper — delegates to cached static parser for compatibility
    private fun parseModelProto(data: ByteArray): ParsedModel = getOrParse(data)
}
