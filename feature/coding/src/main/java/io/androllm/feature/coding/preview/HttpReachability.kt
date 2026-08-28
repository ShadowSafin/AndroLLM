package io.androllm.feature.coding.preview

import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether a local HTTP server is actually reachable — the preview only
 * opens AFTER this succeeds ("wait until the server is really up"). Any HTTP
 * response (even 404) counts as "server is alive and answering".
 */
object HttpReachability {

    /**
     * True when [url] answers with any HTTP status within [timeoutMs].
     * Never throws; connection failures simply return false.
     */
    fun check(url: String, timeoutMs: Int = 1200): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            val code = conn.responseCode
            code in 100..499
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
