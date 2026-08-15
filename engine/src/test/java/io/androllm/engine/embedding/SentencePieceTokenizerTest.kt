package io.androllm.engine.embedding

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Validates [SentencePieceTokenizer] against the reference Python
 * `sentencepiece` implementation on the exact EmbeddingGemma tokenizer model.
 *
 * The reference vectors (`token_vectors.json`) were captured on the host with
 * Python `sentencepiece` against `eg_spm.model` (Gemma 3, 262 144 vocab,
 * identity normalizer, whitespace escaping on).
 *
 * The model binary is NOT committed to the repo (Gemma license); the test
 * reads it from an external path supplied via the `EMBEDDING_SPM_MODEL` env
 * var or the `litert-test-models` dir next to the repo. When absent the test
 * is skipped rather than failing, so CI without the model stays green.
 */
class SentencePieceTokenizerTest {

    private fun tokenizer(): SentencePieceTokenizer? {
        val candidates = listOfNotNull(
            System.getenv("EMBEDDING_SPM_MODEL"),
            File("../litert-test-models/tokenizer.model").absolutePath,
        )
        val model = candidates.firstOrNull { File(it).exists() } ?: return null
        return SentencePieceTokenizer(File(model))
    }

    private fun referenceVectors(): List<Triple<String, List<Int>, List<String>>> {
        val jsonFile = File("../litert-test-models/token_vectors.json")
        if (!jsonFile.exists()) return emptyList()
        val raw = jsonFile.readText()
        // Tiny JSON parser — the vectors file is a flat array of objects with
        // text/ids/pieces; regex extraction keeps this dependency-free.
        val out = mutableListOf<Triple<String, List<Int>, List<String>>>()
        val objRe = Regex("\"text\": \"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"ids\": \\[([^\\]]*)\\],\\s*\"pieces\": \\[([^\\]]*)\\]")
        for (m in objRe.findAll(raw)) {
            val text = m.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val ids = m.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
            val pieces = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[3])
                .map { pm -> pm.groupValues[1].replace("\\u2581", "\u2581") }
                .toList()
            out.add(Triple(text, ids, pieces))
        }
        return out
    }

    @Test
    fun `tokenizer matches reference sentencepiece output`() {
        val tok = tokenizer() ?: return // model not present; skip
        val vectors = referenceVectors()
        if (vectors.isEmpty()) return // vectors not present; skip

        for ((text, expectedIds, _) in vectors) {
            val actual = tok.encode(text)
            assertEquals("token ids for ${text.take(30)}", expectedIds, actual)
        }
    }

    @Test
    fun `bos is prepended when requested`() {
        val tok = tokenizer() ?: return
        assertEquals(2, tok.bosTokenId)
        assertEquals(listOf(2, 9259), tok.encodeWithBos("Hello").take(2))
    }

    @Test
    fun `unknown characters fall back to byte pieces`() {
        val tok = tokenizer() ?: return
        // U+1F600 is actually in the Gemma vocab (a dedicated piece); a null
        // byte is not, so byte_fallback decomposes it into `<0x00>` (238).
        val ids = tok.encode("\u0000")
        assert(ids == listOf(238))
    }

    @Test
    fun `empty text tokenizes to empty list`() {
        val tok = tokenizer() ?: return
        assertEquals(emptyList<Int>(), tok.encode(""))
    }
}
