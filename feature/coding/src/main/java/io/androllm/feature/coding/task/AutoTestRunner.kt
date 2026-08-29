package io.androllm.feature.coding.task

import io.androllm.feature.coding.workspace.WorkspaceFileOps
import java.io.File

/**
 * A single test command and a one-line human label. The runner tries the
 * commands in order; the first one whose file is present in the workspace
 * wins.
 */
data class TestCommand(
    val command: String,
    val label: String,
    /** Workspace-relative marker that must exist for this command to apply. */
    val marker: String,
    /** Regex to extract (passed, failed, error, skipped) counts from output. */
    val summaryRegex: Regex? = null
)

/**
 * Pure policy that picks the right test command for a workspace, runs it
 * through the supplied [runner], and parses a [TestResultRecord] from the
 * output. The actual command execution is injected so the same policy runs
 * in unit tests with a fake runner.
 */
class AutoTestRunner(
    private val runner: suspend (command: String, workingDir: File) -> TestRunResult
) {

    /**
     * Picks a test command for [workspaceRoot], runs it, and returns a
     * structured result. Returns null when no recognised test runner exists.
     */
    suspend fun run(workspaceRoot: File): TestResultRecord? = run {
        val ops = WorkspaceFileOps(workspaceRoot)
        val command = pickCommand(ops) ?: return@run null
        val raw = runner(command.command, workspaceRoot)
        parse(command, raw)
    }

    /**
     * Returns the test command that would be chosen for [workspaceRoot], or
     * null if no recognised test runner exists. Useful for the UI ("Tests
     * detected: npm test") and for the chat status line.
     */
    fun detect(workspaceRoot: File): TestCommand? {
        val ops = WorkspaceFileOps(workspaceRoot)
        return pickCommand(ops)
    }

    private fun pickCommand(ops: WorkspaceFileOps): TestCommand? {
        val candidates = listOf(
            TestCommand("npm test", "npm test", "package.json"),
            TestCommand("pnpm test", "pnpm test", "pnpm-lock.yaml"),
            TestCommand("yarn test", "yarn test", "yarn.lock"),
            TestCommand("pytest", "pytest", "pyproject.toml", summaryRegex = PYTEST_SUMMARY),
            TestCommand("./gradlew test", "gradle test", "build.gradle.kts"),
            TestCommand("./gradlew test", "gradle test", "build.gradle"),
            TestCommand("mvn test", "maven test", "pom.xml", summaryRegex = MAVEN_SUMMARY),
            TestCommand("go test ./...", "go test", "go.mod", summaryRegex = GO_SUMMARY),
            TestCommand("cargo test", "cargo test", "Cargo.toml", summaryRegex = CARGO_SUMMARY)
        )
        return candidates.firstOrNull { ops.exists(it.marker) }
    }

    private fun parse(command: TestCommand, raw: TestRunResult): TestResultRecord {
        val combined = raw.combinedOutput
        val counts = when {
            command.summaryRegex != null -> {
                val m = command.summaryRegex.find(combined)
                Counts(
                    passed = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
                    failed = m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                )
            }
            else -> {
                // Default heuristic for tap/mocha/jest output.
                val passed = Regex("""(\d+)\s+(?:passing|passed|tests?)""", RegexOption.IGNORE_CASE)
                    .find(combined)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val failed = Regex("""(\d+)\s+(?:failing|failed)""", RegexOption.IGNORE_CASE)
                    .find(combined)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Counts(passed = passed, failed = failed)
            }
        }
        val tail = combined.takeLast(2_000)
        return TestResultRecord(
            framework = command.label,
            passed = counts.passed,
            failed = counts.failed,
            rawOutputTail = tail,
            timestampMs = System.currentTimeMillis()
        ).copy(
            // Heuristic: non-zero exit with no parsed "failed" line → count it as one error.
            error = if (raw.exitCode != 0 && counts.failed == 0 && counts.passed == 0) 1 else 0
        )
    }

    private data class Counts(val passed: Int, val failed: Int)

    companion object {
        // pytest: "===== 12 passed, 2 failed in 0.4s ====="
        private val PYTEST_SUMMARY = Regex("""(\d+)\s+passed.*?(\d+)\s+failed""")
        // maven: "Tests run: 12, Failures: 2"
        private val MAVEN_SUMMARY = Regex("""Tests run:\s*(\d+),.*?Failures:\s*(\d+)""")
        // go test: "ok ... 12.0s" / "FAIL ... 12.0s" — counts parsed from PASS/FAIL lines
        private val GO_SUMMARY = Regex("""^--- PASS:\s*\w+\s+\((?:\d+\.\d+s|\d+\.\d+ms)\)""", RegexOption.MULTILINE)
        // cargo test: "test result: ok. 12 passed; 0 failed"
        private val CARGO_SUMMARY = Regex("""test result:\s*\w+\.\s*(\d+)\s+passed;\s*(\d+)\s+failed""")
    }
}

/** Result of running a single command, supplied by the injected runner. */
data class TestRunResult(
    val exitCode: Int,
    val combinedOutput: String
)
