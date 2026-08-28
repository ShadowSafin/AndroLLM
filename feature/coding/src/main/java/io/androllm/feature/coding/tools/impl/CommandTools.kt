package io.androllm.feature.coding.tools.impl

import io.androllm.feature.coding.environment.BackgroundStartOutcome
import io.androllm.feature.coding.tools.CodingTool
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolResult
import io.androllm.feature.coding.tools.CodingToolSpec
import io.androllm.feature.coding.tools.Schemas
import io.androllm.feature.coding.tools.bool
import io.androllm.feature.coding.tools.int
import io.androllm.feature.coding.tools.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Run a shell command in the workspace Linux CLI environment. Output is returned
 * RAW (stdout + stderr + exit code) — never summarized. When the command needs a
 * runtime addon that is not installed, the failure carries [CodingToolResult.Failure.missingAddonId]
 * so the agent loop can prompt to install it and retry.
 *
 * Long-running servers: with `background: true` — or automatically, when the
 * command looks like a dev server (`npm run dev`, `npm start`, ...) — the
 * command is started as a detached background service. The result then carries
 * the service id, the detected port and the access URLs instead of blocking
 * until the timeout.
 */
class RunCommandTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "run_command",
        description = "Run a shell command in the workspace Linux environment (build, test, install, scripts). " +
            "Returns raw stdout/stderr and exit code. For dev servers and other long-running commands " +
            "(npm run dev, npm start, watchers), set background=true: the command keeps running after the call " +
            "returns and you get a service id, the detected port and access URLs. Destructive commands require user approval.",
        parameters = Schemas.obj(
            mapOf(
                "command" to Schemas.string("Full shell command line, e.g. 'npm run build'."),
                "working_dir" to Schemas.string("Optional sub-directory (relative to workspace) to run in."),
                "timeout_ms" to Schemas.integer("Optional timeout in milliseconds (default 120000)."),
                "background" to Schemas.boolean(
                    "Set true to run as a background service (dev servers, watchers, anything long-running). " +
                        "Returns immediately with the service id, detected port and access URLs. " +
                        "Server-like commands are auto-backgrounded even without this flag."
                )
            ),
            required = listOf("command")
        ),
        requiresConfirmation = false, // per-command risk is gated inside the executor
        readOnly = false
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val command = arguments.str("command") ?: return CodingToolResult.Failure("Missing 'command'.", retryable = false)
        val workingDir = arguments.str("working_dir") ?: ""
        val timeout = arguments.int("timeout_ms", 120_000).toLong()
        val explicitBackground = arguments.containsKey("background") && arguments.bool("background")
        val autoBackground = !explicitBackground && ServerCommands.looksLikeServer(command)

        // Background service path (explicit flag or auto-detected server).
        if (explicitBackground || autoBackground) {
            val outcome = context.executor.executeBackground(command, workingDir)
            return when (outcome) {
                is BackgroundStartOutcome.Started -> {
                    val note = if (autoBackground) {
                        "ℹ️ Auto-detected a long-running server — started it in the background instead of blocking.\n"
                    } else ""
                    CodingToolResult.Success(note + outcome.summary)
                }
                is BackgroundStartOutcome.Failed -> CodingToolResult.Failure(
                    outcome.summary,
                    retryable = true,
                    missingAddonId = outcome.missingAddonId
                )
            }
        }

        val result = context.executor.execute(
            command,
            workingDir,
            timeoutMs = timeout,
            onOutput = context.onCommandOutput
        )

        // Missing runtime addon → surface it so the loop can install + retry.
        val missing = result.missingDependency
        if (missing != null) {
            return CodingToolResult.Failure(
                summary = "Cannot run '$command': ${missing.reason} Install the '${missing.addonId}' addon from the marketplace, then retry.",
                retryable = true,
                missingAddonId = missing.addonId
            )
        }

        // A foreground command that timed out but looks like a server: tell the
        // model to re-run it in the background instead of fighting the timeout.
        if (result.cancelled && result.exitCode == 124 && ServerCommands.looksLikeServer(command)) {
            return CodingToolResult.Failure(
                result.render() +
                    "\n\nThis command looks like a long-running server and hit the timeout. " +
                    "Re-run it with background=true (it will keep running and you will get the port + access URL).",
                retryable = true
            )
        }

        val output = result.render()
        return if (result.isSuccess) {
            CodingToolResult.Success(output, mapOf("exit_code" to JsonPrimitive(result.exitCode)))
        } else {
            // Non-zero exit / cancelled: still return the RAW output so the agent
            // can read the error and fix it. Marked as failure for the loop.
            CodingToolResult.Failure(output, retryable = !result.cancelled)
        }
    }
}

/** Inspect git state (status + short diff) without running arbitrary commands. */
class GitStatusTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "git_status",
        description = "Show the git working-tree status and a short diff stat for the workspace. Safe read-only inspection.",
        parameters = Schemas.obj(emptyMap(), required = emptyList()),
        readOnly = true
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val status = context.executor.execute("git status --short --branch")
        if (status.missingDependency != null) {
            return CodingToolResult.Failure(
                "git is not installed. Install the 'git' addon, then retry.",
                retryable = true,
                missingAddonId = "git"
            )
        }
        if (!status.isSuccess) {
            return CodingToolResult.Failure(
                "git status failed:\n${status.combinedOutput.ifBlank { "[exit ${status.exitCode}]" }}",
                retryable = true
            )
        }
        val diffStat = context.executor.execute("git diff --stat")
        val sb = StringBuilder("git status:\n").append(status.combinedOutput.ifBlank { "(clean)" })
        if (diffStat.isSuccess && diffStat.combinedOutput.isNotBlank()) {
            sb.append("\n\ngit diff --stat:\n").append(diffStat.combinedOutput)
        }
        return CodingToolResult.Success(sb.toString())
    }
}

/** Summarize the workspace structure and tech stack. */
class WorkspaceSummaryTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "workspace_summary",
        description = "Summarize the workspace: file/dir counts, detected stack & frameworks (from manifest files), " +
            "entry points, and the canonical build/dev/test commands. Run this first to understand an unknown project.",
        parameters = Schemas.obj(emptyMap(), required = emptyList()),
        readOnly = true
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        return try {
            val s = context.fileOps.summarize()
            val ext = s.filesByExtension.entries.joinToString(", ") { "${it.key}×${it.value}" }
            val stack = io.androllm.feature.coding.workspace.ProjectStackDetector.detect(
                exists = { path -> context.fileOps.exists(path) },
                readHead = { path -> runCatching { context.fileOps.readFile(path, 12_000) }.getOrNull() }
            )
            CodingToolResult.Success(
                buildString {
                    append("Workspace '${context.workspace.name}' at ").append(s.rootPath).append('\n')
                    append("Files: ").append(s.fileCount).append("   Dirs: ").append(s.dirCount)
                    append("   Size: ").append(s.totalBytes).append(" bytes\n")
                    append("Top extensions: ").append(ext).append('\n')
                    if (stack.isEmpty) {
                        append("Stack: no known manifest files found (empty or unknown project type).")
                    } else {
                        append('\n').append(stack.render())
                    }
                }
            )
        } catch (e: Exception) {
            CodingToolResult.Failure("Summary failed: ${e.message}", retryable = true)
        }
    }
}
