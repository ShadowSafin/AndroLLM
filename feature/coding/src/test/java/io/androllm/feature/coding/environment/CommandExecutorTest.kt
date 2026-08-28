package io.androllm.feature.coding.environment

import io.androllm.feature.coding.workspace.WorkspaceSafety
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Command execution: safety gates, dependency detection, containment, history. */
class CommandExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun executor(
        backend: ShellBackend = FakeShellBackend(),
        installed: Set<String> = emptySet(),
        gate: ConfirmationGate = AutoApproveGate
    ): Pair<CommandExecutor, FakeShellBackend> {
        val root = tmp.newFolder("ws")
        val fake = backend as? FakeShellBackend ?: FakeShellBackend()
        val exec = CommandExecutor(
            workspaceRoot = root,
            backend = backend,
            installedAddons = { installed },
            confirmationGate = gate
        )
        return exec to fake
    }

    @Test
    fun `runs allowed command inside workspace and preserves raw output`() = runBlocking {
        val backend = FakeShellBackend { cmd, dir ->
            CommandResult(cmd, exitCode = 0, stdout = "raw build log\nwarning: something\n", stderr = "")
        }
        val (exec, fake) = executor(backend, installed = setOf("nodejs"))
        val result = exec.execute("npm run build")
        assertTrue(result.isSuccess)
        assertEquals("raw build log\nwarning: something\n", result.stdout)
        assertEquals(1, fake.invocations.size)
        // Working dir must be inside the workspace.
        assertTrue(WorkspaceSafety.isWithin(tmp.root.listFiles()!!.first { it.isDirectory }, fake.invocations[0].workingDir))
    }

    @Test
    fun `blocks dangerous command without running process`() = runBlocking {
        val (exec, fake) = executor()
        val result = exec.execute("rm -rf /")
        assertEquals(CommandResult.EXIT_BLOCKED, result.exitCode)
        assertTrue(result.stderr.contains("Blocked"))
        assertEquals("process must not run for blocked command", 0, fake.invocations.size)
    }

    @Test
    fun `declined destructive command does not run`() = runBlocking {
        val (exec, fake) = executor(gate = AutoDeclineGate)
        val result = exec.execute("rm -rf build")
        assertEquals(CommandResult.EXIT_BLOCKED, result.exitCode)
        assertTrue(result.stderr.contains("Declined"))
        assertEquals(0, fake.invocations.size)
    }

    @Test
    fun `approved destructive command runs`() = runBlocking {
        val (exec, fake) = executor(gate = AutoApproveGate)
        val result = exec.execute("rm -rf build")
        assertEquals(1, fake.invocations.size)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `missing dependency is reported without running process`() = runBlocking {
        val (exec, fake) = executor(installed = emptySet())
        val result = exec.execute("npm run build")
        assertNotNull(result.missingDependency)
        assertEquals("nodejs", result.missingDependency?.addonId)
        assertEquals(CommandResult.EXIT_MISSING_DEP, result.exitCode)
        assertEquals("no process for missing dep", 0, fake.invocations.size)
    }

    @Test
    fun `dependency satisfied runs normally`() = runBlocking {
        val (exec, fake) = executor(installed = setOf("nodejs"))
        val result = exec.execute("npm run build")
        assertNull(result.missingDependency)
        assertEquals(1, fake.invocations.size)
    }

    @Test
    fun `working dir outside workspace is clamped to root`() = runBlocking {
        val (exec, fake) = executor()
        exec.execute("ls", workingDir = "../../etc")
        val root = tmp.root.listFiles()!!.first { it.isDirectory }
        assertEquals(
            "cwd must be clamped into the workspace",
            root.canonicalPath,
            fake.invocations[0].workingDir.canonicalPath
        )
    }

    @Test
    fun `history records every result`() = runBlocking {
        val (exec, _) = executor(installed = setOf("nodejs"))
        exec.execute("npm run build")
        exec.execute("git status")
        assertEquals(2, exec.history.value.size)
    }

    @Test
    fun `cancel invokes backend cancellation`() = runBlocking {
        val (exec, fake) = executor()
        exec.cancel()
        assertEquals(1, fake.cancelCount)
        assertNull(exec.running.value)
    }

    @Test
    fun `non-zero exit is a failure but output preserved`() = runBlocking {
        val backend = FakeShellBackend { cmd, _ ->
            CommandResult(cmd, exitCode = 2, stdout = "", stderr = "error: build failed\n")
        }
        val (exec, _) = executor(backend, installed = setOf("nodejs"))
        val result = exec.execute("npm run build")
        assertFalse(result.isSuccess)
        assertEquals(2, result.exitCode)
        assertTrue(result.combinedOutput.contains("error: build failed"))
    }
}
