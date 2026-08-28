package io.androllm.feature.coding.workspace

import java.net.URI
import java.net.URLDecoder

/**
 * Resolves SAF tree URIs (from ACTION_OPEN_DOCUMENT_TREE) to real filesystem
 * paths so the coding agent can open a user folder DIRECTLY and write into it
 * — no copying into app-private storage.
 *
 * Only folders backed by the device's primary storage can be resolved to a
 * path the shell environment can use (`content://com.android.externalstorage.
 * documents/tree/primary%3A...`). Cloud providers (Drive, Downloads provider
 * wrappers, SD-card document providers that are not raw-mounted) have no real
 * path and are rejected — the UI tells the user to pick a folder on device
 * storage.
 *
 * Pure JVM (no android.* types) so it is fully unit testable.
 */
object WorkspacePathResolver {

    const val DEFAULT_PRIMARY_ROOT = "/storage/emulated/0"
    private const val PRIMARY_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Returns the absolute filesystem path for a SAF tree URI, or null when
     * the URI does not point at a real, directly-usable folder.
     *
     * Handles:
     *  - `content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMyProj`
     *    → `<primaryRoot>/Documents/MyProj`
     *  - `...tree/primary%3A` (root of primary storage) → `<primaryRoot>`
     *  - `...tree/raw%3A%2Fstorage%2Femulated%2F0%2Fx` (raw doc id) → `/storage/emulated/0/x`
     */
    fun resolveTreeUri(uriString: String, primaryRoot: String = DEFAULT_PRIMARY_ROOT): String? {
        return runCatching {
            val uri = URI(uriString.trim())
            if (uri.scheme != "content") return null
            val pathSegments = uri.rawPath?.trimStart('/')?.split('/').orEmpty()
            if (pathSegments.size < 2 || pathSegments.first() != "tree") return null
            val docId = URLDecoder.decode(pathSegments.drop(1).joinToString("/"), "UTF-8")
            when {
                docId.startsWith("raw:") -> {
                    val raw = docId.removePrefix("raw:")
                    raw.takeIf { it.startsWith("/") && it.length > 1 }
                }
                ":" in docId -> {
                    if (uri.authority != PRIMARY_AUTHORITY) return null
                    val colon = docId.indexOf(':')
                    val volume = docId.substring(0, colon)
                    val rest = docId.substring(colon + 1).trimStart('/')
                    if (volume != "primary") return null
                    if (rest.isEmpty()) primaryRoot.trimEnd('/')
                    else "${primaryRoot.trimEnd('/')}/$rest"
                }
                else -> null
            }
        }.getOrNull()
    }

    /** Display name for a resolved path: its last path segment. */
    fun displayName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { path }
}
