package io.androllm.feature.cloud

/** Parses "Key: value" lines (one per line) into a header map. */
internal fun parseHeaders(text: String): Map<String, String> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx > 0) line.take(idx).trim() to line.drop(idx + 1).trim() else null
        }
        .toMap()

/** Parses a comma-separated tag list, trimming empties. */
internal fun parseTags(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotBlank() }
