package io.androllm.feature.coding.task

import io.androllm.feature.coding.workspace.ProjectStackDetector
import io.androllm.feature.coding.workspace.StackReport
import io.androllm.feature.coding.workspace.WorkspaceFileOps
import java.io.File

/**
 * A compact, prompt-friendly summary of a workspace's stack, intended to be
 * injected into the agent's system prompt so the model understands the
 * project's shape without having to read every file. Built once per workspace
 * attach and cached.
 */
data class WorkspaceContext(
    val stacks: List<String>,
    val entryPoints: List<String>,
    val devCommands: List<String>,
    val buildCommands: List<String>,
    val testCommands: List<String>,
    val topLevelFiles: List<String>
) {
    val hasPackageJson: Boolean get() = topLevelFiles.contains("package.json")
    val isEmpty: Boolean get() = stacks.isEmpty() && entryPoints.isEmpty()

    /** Single-line, prompt-friendly summary. */
    fun oneLiner(): String = buildString {
        if (stacks.isNotEmpty()) append("stack=").append(stacks.joinToString("+").lowercase())
        if (testCommands.isNotEmpty()) append("  test=").append(testCommands.first())
        if (devCommands.isNotEmpty()) append("  dev=").append(devCommands.first())
    }
}

/**
 * Builds a [WorkspaceContext] for a workspace. Pure / suspending so the
 * orchestrator can re-build on demand after major file changes.
 */
class WorkspaceContextLoader {

    suspend fun load(workspaceRoot: File): WorkspaceContext = run {
        val ops = WorkspaceFileOps(workspaceRoot)
        val report: StackReport = ProjectStackDetector.detect(
            exists = { p -> runCatching { ops.exists(p) }.getOrDefault(false) },
            readHead = { p -> runCatching { ops.readFile(p, 12_000) }.getOrNull() }
        )
        WorkspaceContext(
            stacks = report.stacks,
            entryPoints = report.entryPoints,
            devCommands = report.devCommands,
            buildCommands = report.buildCommands,
            testCommands = report.testCommands,
            topLevelFiles = runCatching { ops.listDir("").map { it.name } }.getOrDefault(emptyList())
        )
    }
}
