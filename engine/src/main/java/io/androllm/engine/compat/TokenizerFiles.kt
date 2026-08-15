package io.androllm.engine.compat

import java.io.File

/**
 * Sidecar tokenizer files that live next to the model file (the Hugging Face
 * export of the model's own tokenizer). They complement — and where needed
 * override — the tokenizer embedded in the `.litertlm` container.
 *
 * Required files are enforced at load time: a model that cannot produce its
 * own tokens must not silently fall back to another family's tokenizer, so the
 * load fails with the exact missing file name.
 */
data class SidecarTokenizers(
    /** `tokenizer.json` (BPE families: vocab + merges + added tokens). */
    val tokenizerJson: ByteArray? = null,
    /** `tokenizer_config.json` (template, bos/eos, legacy flags). */
    val tokenizerConfig: ByteArray? = null,
    /** `special_tokens_map.json` (when the export splits it out). */
    val specialTokensMap: ByteArray? = null,
    /** `added_tokens.json` (when the export splits it out). */
    val addedTokens: ByteArray? = null,
    /** `merges.txt` (rarely used — tokenizer.json already contains merges). */
    val merges: ByteArray? = null
) {
    /** File names this family requires but that are absent. */
    fun missingRequired(family: ModelFamily): List<String> = buildList {
        if (tokenizerJson == null && family.tokenizerKind == TokenizerKind.BPE) add("tokenizer.json")
        if (tokenizerJson == null && family.tokenizerKind == TokenizerKind.SENTENCEPIECE) add("tokenizer.json")
        if (tokenizerConfig == null) add("tokenizer_config.json")
    }

    /** The names of the tokenizer files actually present. */
    val present: List<String>
        get() = buildList {
            if (tokenizerJson != null) add("tokenizer.json")
            if (tokenizerConfig != null) add("tokenizer_config.json")
            if (specialTokensMap != null) add("special_tokens_map.json")
            if (addedTokens != null) add("added_tokens.json")
            if (merges != null) add("merges.txt")
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SidecarTokenizers) return false
        return tokenizerJson.contentEquals(other.tokenizerJson) &&
            tokenizerConfig.contentEquals(other.tokenizerConfig) &&
            specialTokensMap.contentEquals(other.specialTokensMap) &&
            addedTokens.contentEquals(other.addedTokens) &&
            merges.contentEquals(other.merges)
    }

    override fun hashCode(): Int {
        var result = tokenizerJson?.contentHashCode() ?: 0
        result = 31 * result + (tokenizerConfig?.contentHashCode() ?: 0)
        result = 31 * result + (specialTokensMap?.contentHashCode() ?: 0)
        result = 31 * result + (addedTokens?.contentHashCode() ?: 0)
        result = 31 * result + (merges?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Loads the sidecar tokenizer files for a model file, and parses the added
 * special tokens out of them so the decode rules can be enriched with the
 * model's ACTUAL tokens (not just the family's catalog).
 */
object TokenizerFiles {

    private const val MB = 1024 * 1024

    /**
     * Reads the sidecar files for [modelFile] and returns them. Files that are
     * absent are simply null in the result — call [SidecarTokenizers.missingRequired]
     * to fail with the exact missing name when they are required.
     *
     * @throws ModelCompatibilityException if a file exists but cannot be read.
     */
    fun loadFrom(modelFile: File): SidecarTokenizers {
        val dir = modelFile.parentFile ?: throw ModelCompatibilityException("model file has no parent directory: ${modelFile.path}")
        val baseName = modelFile.name.substringBeforeLast('.', modelFile.name)
        return SidecarTokenizers(
            tokenizerJson = readOrNull(File(dir, "$baseName.tokenizer.json")),
            tokenizerConfig = readOrNull(File(dir, "$baseName.tokenizer_config.json")),
            specialTokensMap = readOrNull(File(dir, "$baseName.special_tokens_map.json")),
            addedTokens = readOrNull(File(dir, "$baseName.added_tokens.json")),
            merges = readOrNull(File(dir, "$baseName.merges.txt"))
        )
    }

    private fun readOrNull(file: File): ByteArray? {
        if (!file.exists()) return null
        if (file.length() > 50 * MB) {
            throw ModelCompatibilityException("tokenizer file too large: ${file.path} (${file.length()} bytes)")
        }
        return try {
            file.readBytes()
        } catch (e: Exception) {
            throw ModelCompatibilityException("cannot read tokenizer file: ${file.path}", e)
        }
    }

    /**
     * Parses the list of added-token strings from a `tokenizer.json` or
     * `added_tokens.json` payload. Unknown shapes return an empty list — the
     * caller decides whether that is acceptable.
     */
    fun parseAddedTokens(jsonBytes: ByteArray): List<String> {
        val text = String(jsonBytes, Charsets.UTF_8)
        val brace = text.indexOf("\"added_tokens\"")
        if (brace < 0) return emptyList()
        val start = text.indexOf('[', brace)
        if (start < 0) return emptyList()
        val end = text.indexOf(']', start)
        if (end < 0) return emptyList()
        val items = text.substring(start + 1, end)
        val result = mutableListOf<String>()
        var i = 0
        while (i < items.length) {
            val objStart = items.indexOf('{', i)
            if (objStart < 0) break
            val objEnd = findClosing(items, objStart, '{', '}')
            if (objEnd < 0) break
            val obj = items.substring(objStart, objEnd + 1)
            val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj)
            if (content != null) result += decodeJsonString(content.groupValues[1])
            i = objEnd + 1
        }
        return result
    }

    private fun findClosing(s: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var inString = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun decodeJsonString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = s.substring(i + 2, i + 6)
                        sb.append(hex.toInt(16).toChar())
                        i += 6
                        continue
                    }
                    else -> sb.append(e)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}