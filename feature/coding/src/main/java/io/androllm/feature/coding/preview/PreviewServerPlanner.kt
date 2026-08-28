package io.androllm.feature.coding.preview

import java.net.ServerSocket

/**
 * A concrete plan for the local HTTP server that serves a preview target.
 *
 * @param command shell command to start the server (runs through the normal
 *   background-service path, so port detection + URLs work unchanged).
 * @param workingDir workspace-relative directory the server serves from.
 * @param port requested port (static servers); dev servers announce their own.
 * @param description human-readable label for UI + logs.
 * @param requiredAddonId set when a needed runtime addon is NOT installed —
 *   the caller should offer to install it instead of spawning a doomed process.
 */
data class PreviewServerPlan(
    val command: String,
    val workingDir: String,
    val port: Int,
    val description: String,
    val requiredAddonId: String? = null
)

/**
 * Chooses the right local HTTP server for a detected preview target.
 *
 * The preview NEVER opens raw file:// URLs: static sites are served through a
 * lightweight local HTTP server (python3 http.server, or node http-server as a
 * fallback) and framework projects run their real dev server (npm run dev,
 * next dev, ...). Pure function for unit tests.
 */
object PreviewServerPlanner {

    /** Addon ids used by the marketplace / dependency detector. */
    private const val ADDON_PYTHON = "python"
    private const val ADDON_NODE = "nodejs"

    /**
     * Plans the server for [target], or null when nothing sensible can be
     * started (e.g. a dev server is already running or no command is known).
     */
    fun plan(
        target: PreviewDetector.PreviewTarget,
        devCommands: List<String> = emptyList(),
        installedAddons: Set<String> = emptySet(),
        portProvider: () -> Int = ::freePort
    ): PreviewServerPlan? = when (target.kind) {
        // Already served — no plan needed.
        PreviewDetector.PreviewKind.DEV_SERVER -> null
        PreviewDetector.PreviewKind.NONE -> null

        // Static site / built output → serve the containing directory over HTTP.
        PreviewDetector.PreviewKind.STATIC_FILE,
        PreviewDetector.PreviewKind.BUILD_OUTPUT -> {
            val dir = target.relativePath.substringBeforeLast('/', "")
            val serveLabel = dir.ifBlank { "." }
            val port = portProvider()
            when {
                ADDON_PYTHON in installedAddons -> PreviewServerPlan(
                    command = "python3 -m http.server $port --bind 0.0.0.0",
                    workingDir = dir,
                    port = port,
                    description = "Static HTTP server (python3) serving $serveLabel"
                )
                ADDON_NODE in installedAddons -> PreviewServerPlan(
                    command = "npx -y http-server -p $port -a 0.0.0.0 .",
                    workingDir = dir,
                    port = port,
                    description = "Static HTTP server (node) serving $serveLabel"
                )
                // No runtime yet — plan python (smallest) and flag the addon.
                else -> PreviewServerPlan(
                    command = "python3 -m http.server $port --bind 0.0.0.0",
                    workingDir = dir,
                    port = port,
                    description = "Static HTTP server (python3) serving $serveLabel",
                    requiredAddonId = ADDON_PYTHON
                )
            }
        }

        // Framework project → run its real dev server.
        PreviewDetector.PreviewKind.DEV_COMMAND -> {
            val cmd = devCommands.firstOrNull() ?: return null
            val needsNode = cmd.startsWith("npm") || cmd.startsWith("npx") ||
                cmd.startsWith("node") || cmd.startsWith("pnpm") ||
                cmd.startsWith("yarn") || cmd.startsWith("bun")
            val needsPython = cmd.startsWith("python") || cmd.startsWith("flask") ||
                cmd.startsWith("uvicorn") || cmd.startsWith("gunicorn")
            PreviewServerPlan(
                command = cmd,
                workingDir = "",
                port = portProvider(),
                description = "Dev server: $cmd",
                requiredAddonId = when {
                    needsNode && ADDON_NODE !in installedAddons -> ADDON_NODE
                    needsPython && ADDON_PYTHON !in installedAddons -> ADDON_PYTHON
                    else -> null
                }
            )
        }
    }

    /** Grabs a free TCP port from the OS (proot shares the host network stack). */
    fun freePort(): Int = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrDefault(DEFAULT_PORT)

    private const val DEFAULT_PORT = 8080
}
