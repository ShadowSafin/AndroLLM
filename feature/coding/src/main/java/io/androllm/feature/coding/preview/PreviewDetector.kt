package io.androllm.feature.coding.preview

import io.androllm.feature.coding.environment.BackgroundServiceInfo
import io.androllm.feature.coding.workspace.ProjectStackDetector
import io.androllm.feature.coding.workspace.StackReport
import timber.log.Timber

/**
 * Preview auto-detection for the coding agent.
 *
 * Infers the correct preview target from many signals so the app does NOT rely
 * on a single hardcoded condition. Signals inspected:
 *  - running background services (dev server port + URLs)
 *  - project structure / manifest files / build output dirs / file extensions
 *  - package.json scripts + dependencies (framework + dev/build commands)
 *  - generated HTML files (index.html and friends)
 *  - dev server output already lives inside BackgroundServiceManager port detection
 *
 * Every step emits a structured log line so the preview bug is trivial to debug:
 *  workspace scanned, framework detected, preview target found, preview opened,
 *  preview failed, preview refreshed, preview skipped, manual fallback used.
 *
 * Pure function over lambdas so it runs on JVM in unit tests.
 */
object PreviewDetector {

    enum class PreviewKind {
        DEV_SERVER,         // http://localhost:PORT from a running service
        STATIC_FILE,        // file:// or http serving a static html file
        BUILD_OUTPUT,       // dist/build/out contains a previewable site
        DEV_COMMAND,        // a dev script exists but no server is running yet
        NONE
    }

    enum class PreviewStatus {
        SCANNING,
        READY,
        NOT_AVAILABLE,
        FAILED
    }

    data class PreviewTarget(
        val kind: PreviewKind,
        val relativePath: String,      // e.g. "index.html" or "dist/index.html" or ""
        val url: String?,              // http://localhost:xxx when applicable
        val framework: String,         // e.g. "React + Vite" or "Static HTML"
        val title: String,             // human label for the UI
        val priority: Int,             // higher = more preferred
        val suggestion: String? = null // how to get a preview when not yet running
    )

    data class PreviewDetectionResult(
        val status: PreviewStatus,
        val framework: String?,
        val target: PreviewTarget?,
        val candidates: List<PreviewTarget>,
        /** Human-readable trace for the UI + Timber. */
        val logs: List<String>,
        /** Suggestion when no target is ready yet. */
        val suggestion: String?,
        /** True when the preview should be auto-opened now. */
        val autoOpen: Boolean,
        /** Stack report for extra context (frameworks, entry points). */
        val stackReport: StackReport? = null
    )

    // ── Known static candidates in priority order ──────────────────────────
    // Earlier = preferred when multiple exist.
    private val STATIC_CANDIDATES = listOf(
        "index.html",
        "dist/index.html",
        "build/index.html",
        "out/index.html",
        ".next/server/pages/index.html",
        "public/index.html",
        "docs/index.html",
        "preview/index.html",
        "app/index.html",
        "src/index.html",
        "www/index.html",
        "site/index.html"
    )

    // Build-output directories that imply a "built site ready to serve"
    private val BUILD_OUTPUT_DIRS = listOf("dist", "build", "out", ".next", "public/build")

    /**
     * Scans the workspace + running services and returns the best preview target.
     *
     * @param exists workspace-scoped exists("path") — true when the relative path exists
     * @param readHead workspace-scoped readHead("path") — at most a few KB or null
     * @param services snapshot of running background services
     * @param workspacePath absolute path for logging only
     */
    fun detect(
        exists: (String) -> Boolean,
        readHead: (String) -> String?,
        services: List<BackgroundServiceInfo> = emptyList(),
        workspacePath: String = ""
    ): PreviewDetectionResult {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            logs += msg
            Timber.i("[PreviewDetector] $msg")
        }

        log("workspace scanned: ${workspacePath.ifBlank { "(unknown path)" }}")

        // ── 1. Running dev servers (highest priority, always win) ────────────
        val runningServer = services.firstOrNull { it.running && it.urlOnDevice != null }
        if (runningServer != null) {
            log("framework detected: dev server running (${runningServer.command})")
            log("preview target found: DEV_SERVER at ${runningServer.urlOnDevice} (port ${runningServer.port})")
            log("preview opened: ${runningServer.urlOnDevice}")
            val target = PreviewTarget(
                kind = PreviewKind.DEV_SERVER,
                relativePath = "",
                url = runningServer.urlOnDevice,
                framework = "Dev Server",
                title = "Running server: ${runningServer.command}",
                priority = 100
            )
            return PreviewDetectionResult(
                status = PreviewStatus.READY,
                framework = "Dev Server",
                target = target,
                candidates = listOf(target),
                logs = logs,
                suggestion = null,
                autoOpen = true
            )
        }
        log("no running dev server detected")

        // ── 2. Framework detection via ProjectStackDetector + package.json ───
        val stackReport = runCatching {
            ProjectStackDetector.detect(exists, readHead)
        }.getOrNull()

        val frameworkLabel = when {
            stackReport == null || stackReport.isEmpty -> null
            stackReport.stacks.isNotEmpty() -> stackReport.stacks.joinToString(" + ")
            else -> null
        }
        if (frameworkLabel != null) {
            log("framework detected: $frameworkLabel")
            if (stackReport != null) {
                log("  stacks: ${stackReport.stacks.joinToString(", ")}")
                log("  entry points: ${stackReport.entryPoints.joinToString(", ").ifBlank { "(none)" }}")
                log("  build: ${stackReport.buildCommands.joinToString(", ").ifBlank { "(none)" }}")
                log("  dev: ${stackReport.devCommands.joinToString(", ").ifBlank { "(none)" }}")
            }
        } else {
            log("framework detected: (none / unknown)")
        }

        // ── 3. Package.json inspection (scripts + deps) ──────────────────────
        val pkgJson = if (exists("package.json")) readHead("package.json") else null
        val hasPackageJson = pkgJson != null
        if (hasPackageJson) {
            log("package.json found (${pkgJson!!.length} chars)")
            val lower = pkgJson.lowercase()
            val scripts = mutableListOf<String>()
            if (lower.contains("\"dev\"")) scripts += "dev"
            if (lower.contains("\"start\"")) scripts += "start"
            if (lower.contains("\"preview\"")) scripts += "preview"
            if (lower.contains("\"serve\"")) scripts += "serve"
            if (scripts.isNotEmpty()) log("package scripts detected: ${scripts.joinToString(", ")}")
            // Framework hints already in stackReport; also log explicit dep hits
            listOf("next" to "Next.js", "react" to "React", "vite" to "Vite",
                "vue" to "Vue", "svelte" to "Svelte", "angular" to "Angular",
                "astro" to "Astro", "nuxt" to "Nuxt").forEach { (dep, label) ->
                if (lower.contains("\"$dep\"")) log("dependency hint: $label ($dep)")
            }
        } else {
            log("no package.json")
        }

        // ── 4. Static file & build output scanning ────────────────────────────
        val staticCandidates = mutableListOf<PreviewTarget>()
        for (path in STATIC_CANDIDATES) {
            if (exists(path)) {
                val isBuildOutput = BUILD_OUTPUT_DIRS.any { path.startsWith("$it/") }
                val kind = if (isBuildOutput) PreviewKind.BUILD_OUTPUT else PreviewKind.STATIC_FILE
                val fw = frameworkLabel ?: if (isBuildOutput) "Built site" else "Static HTML"
                val target = PreviewTarget(
                    kind = kind,
                    relativePath = path,
                    url = null, // file preview — viewModel will map to file:// or serve URL
                    framework = fw,
                    title = when (kind) {
                        PreviewKind.BUILD_OUTPUT -> "Built site: $path"
                        else -> "Static page: $path"
                    },
                    priority = if (isBuildOutput) 60 else 80
                )
                staticCandidates += target
                log("preview target found: $kind at $path (framework: $fw)")
            }
        }

        // Also sweep for any *.html at root when index.html not among candidates
        if (staticCandidates.isEmpty()) {
            // Check generic html existence via common names already, but also
            // consider any preview-like generated landing page names.
            val extraHtmlNames = listOf("landing.html", "preview.html", "app.html", "home.html")
            for (name in extraHtmlNames) {
                if (exists(name)) {
                    val fw = frameworkLabel ?: "Static HTML"
                    val t = PreviewTarget(
                        kind = PreviewKind.STATIC_FILE,
                        relativePath = name,
                        url = null,
                        framework = fw,
                        title = "Static page: $name",
                        priority = 70
                    )
                    staticCandidates += t
                    log("preview target found: STATIC_FILE at $name")
                }
            }
        }

        if (staticCandidates.isNotEmpty()) {
            // Sort by priority desc (running server already handled)
            val sorted = staticCandidates.sortedByDescending { it.priority }
            val best = sorted.first()
            log("preview ready: ${best.title} (${best.kind})")
            log("preview opened: ${best.relativePath} (auto-open static preview)")
            return PreviewDetectionResult(
                status = PreviewStatus.READY,
                framework = frameworkLabel ?: best.framework,
                target = best,
                candidates = sorted,
                logs = logs,
                suggestion = "Open ${best.relativePath} in preview. For a live server, run: ${stackReport?.devCommands?.firstOrNull() ?: "npx serve ."}",
                autoOpen = true,
                stackReport = stackReport
            )
        }

        log("no static preview file found among: ${STATIC_CANDIDATES.joinToString(", ")}")

        // ── 5. Build output dir exists but no index.html yet (built elsewhere) —
        for (dir in BUILD_OUTPUT_DIRS) {
            if (exists(dir)) {
                log("build output dir found: $dir (but no index.html inside)")
            }
        }

        // ── 6. Package script available but server not running ────────────────
        val devCmd = stackReport?.devCommands?.firstOrNull()
        if (devCmd != null) {
            log("preview target found: DEV_COMMAND available ($devCmd) but no server running")
            val target = PreviewTarget(
                kind = PreviewKind.DEV_COMMAND,
                relativePath = "",
                url = null,
                framework = frameworkLabel ?: "Node.js",
                title = "Dev server available: $devCmd",
                priority = 40,
                suggestion = "Run `$devCmd` to start a previewable dev server"
            )
            log("preview skipped: server not running (needs `$devCmd`)")
            log("manual fallback used: suggest running $devCmd")
            return PreviewDetectionResult(
                status = PreviewStatus.NOT_AVAILABLE,
                framework = frameworkLabel,
                target = target,
                candidates = listOf(target),
                logs = logs,
                suggestion = "Start the dev server with `$devCmd` then the preview will open automatically.",
                autoOpen = false,
                stackReport = stackReport
            )
        }

        // ── 7. HTML file extension present anywhere? (workspace has web content)
        // We already checked top candidates; this is a final fallback check.
        // If any HTML file exists at all, suggest it.
        log("preview not available: no previewable target found")
        val fallbackSuggestion = when {
            hasPackageJson -> "No previewable page found. Create an index.html or run a dev server (`npm run dev`)."
            else -> "No previewable project detected. Create an index.html or add a frontend framework, then the preview will open automatically."
        }
        log("manual fallback used: $fallbackSuggestion")
        return PreviewDetectionResult(
            status = PreviewStatus.NOT_AVAILABLE,
            framework = frameworkLabel,
            target = null,
            candidates = emptyList(),
            logs = logs,
            suggestion = fallbackSuggestion,
            autoOpen = false,
            stackReport = stackReport
        )
    }

    /**
     * Convenience: true when a file-path preview target is present.
     * Used by ViewModel to decide whether to synthesize a file:// URL.
     */
    fun isStaticPreview(result: PreviewDetectionResult): Boolean =
        result.target?.kind == PreviewKind.STATIC_FILE || result.target?.kind == PreviewKind.BUILD_OUTPUT
}
