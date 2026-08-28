package io.androllm.feature.coding.workspace

/** Kind of one diff line. */
enum class DiffKind { CONTEXT, ADD, DELETE }

/** One line of a line-level diff. */
data class DiffLine(val kind: DiffKind, val text: String)

/** Stats about a diff (lines added / removed). */
data class DiffStats(val added: Int, val removed: Int) {
    fun render(): String = "+$added −$removed"
}

/**
 * Small LCS-based line diff, pure JVM (no Android). Deliberately simple and
 * bounded: files larger than [MAX_DIFF_LINES] per side return null so callers
 * fall back to a plain "file too large to diff" note instead of burning memory
 * on the DP table.
 *
 * Used to show OpenCode-style change previews in the coding chat (colored
 * +/- lines) and to decide whether an edit is "major" enough to require the
 * user's review before it is applied.
 */
object LineDiff {

    const val MAX_DIFF_LINES = 800

    /**
     * Diffs [oldText] against [newText] line by line. Returns null when either
     * side exceeds [MAX_DIFF_LINES] lines.
     */
    fun diff(oldText: String, newText: String): List<DiffLine>? {
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        if (oldLines.size > MAX_DIFF_LINES || newLines.size > MAX_DIFF_LINES) return null

        val n = oldLines.size
        val m = newLines.size
        // LCS length table (n+1)*(m+1), rolling rows would lose backtracking —
        // bounded by MAX_DIFF_LINES so the table stays small (~2.5 MB worst case).
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (oldLines[i] == newLines[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                oldLines[i] == newLines[j] -> {
                    out += DiffLine(DiffKind.CONTEXT, oldLines[i]); i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    out += DiffLine(DiffKind.DELETE, oldLines[i]); i++
                }
                else -> {
                    out += DiffLine(DiffKind.ADD, newLines[j]); j++
                }
            }
        }
        while (i < n) { out += DiffLine(DiffKind.DELETE, oldLines[i]); i++ }
        while (j < m) { out += DiffLine(DiffKind.ADD, newLines[j]); j++ }
        return out
    }

    /** Counts added/removed lines (context ignored). */
    fun stats(lines: List<DiffLine>): DiffStats = DiffStats(
        added = lines.count { it.kind == DiffKind.ADD },
        removed = lines.count { it.kind == DiffKind.DELETE }
    )

    /**
     * Renders a compact unified-diff-style text: +/- markers, with unchanged
     * context trimmed to [contextRadius] lines around each change and `@@`
     * separators where chunks were skipped. Capped at [maxChars] (tail kept).
     */
    fun renderUnified(lines: List<DiffLine>, contextRadius: Int = 3, maxChars: Int = 6000): String {
        val keep = BooleanArray(lines.size)
        for ((idx, line) in lines.withIndex()) {
            if (line.kind != DiffKind.CONTEXT) {
                val from = maxOf(0, idx - contextRadius)
                val to = minOf(lines.size - 1, idx + contextRadius)
                for (k in from..to) keep[k] = true
            }
        }
        val sb = StringBuilder()
        var inHunk = false
        for ((idx, line) in lines.withIndex()) {
            if (keep[idx]) {
                if (!inHunk && idx > 0) sb.append("  @@\n")
                inHunk = true
                val marker = when (line.kind) {
                    DiffKind.ADD -> "+"
                    DiffKind.DELETE -> "-"
                    DiffKind.CONTEXT -> " "
                }
                sb.append(marker).append(' ').append(line.text.take(300)).append('\n')
            } else {
                inHunk = false
            }
        }
        val text = sb.toString().trimEnd()
        return if (text.length <= maxChars) text
        else "…[diff trimmed]\n" + text.takeLast(maxChars)
    }
}
