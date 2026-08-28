package io.androllm.feature.coding.environment

import io.androllm.feature.coding.tools.impl.ServerCommands
import io.androllm.feature.coding.workspace.WorkspaceSafety
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for background services (dev servers): port detection, server-command
 * auto-detection, the manager lifecycle (start/stop/logs) and the executor's
 * background path (safety gates + missing addons).
 */
class BackgroundServiceManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var servicesDir: File
    private lateinit var backend: FakeShellBackend
    private lateinit var manager: BackgroundServiceManager

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        servicesDir = tmp.newFolder("services")
        backend = FakeShellBackend()
        manager = BackgroundServiceManager(
            servicesDir = servicesDir,
            portWaitMs = 1500,
            deviceIpProvider = { "192.168.0.133" }
        )
        manager.attachBackend(backend)
    }

    // ── Port detection ───────────────────────────────────────────────────────

    @Test
    fun `port detector recognizes common dev server announcements`() {
        assertEquals(5173, ServerPortDetector.detect("  ➜  Local: http://localhost:5173/"))
        assertEquals(3000, ServerPortDetector.detect("- Local:        http://localhost:3000"))
        assertEquals(5000, ServerPortDetector.detect(" * Running on http://127.0.0.1:5000"))
        assertEquals(8080, ServerPortDetector.detect("Server listening on port 8080"))
        assertEquals(4200, ServerPortDetector.detect("** Angular Live Development Server is listening on localhost:4200 **"))
        assertEquals(8000, ServerPortDetector.detect("Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ..."))
        assertEquals(3000, ServerPortDetector.detect("app running at http://0.0.0.0:3000"))
    }

    @Test
    fun `port detector ignores non-port text`() {
        assertNull(ServerPortDetector.detect("added 128 packages in 4s"))
        assertNull(ServerPortDetector.detect("found 0 vulnerabilities"))
        assertNull(ServerPortDetector.detect("compile took 12:34"))
        assertNull(ServerPortDetector.detect(""))
        // Out-of-range / too-short bare ports are rejected.
        assertNull(ServerPortDetector.detect("ratio 1:23"))
        assertNull(ServerPortDetector.detect("port 80"))
    }

    // ── Server command auto-detection ────────────────────────────────────────

    @Test
    fun `server commands are recognized`() {
        assertTrue(ServerCommands.looksLikeServer("npm run dev"))
        assertTrue(ServerCommands.looksLikeServer("npm start"))
        assertTrue(ServerCommands.looksLikeServer("npm run dev -- --host 0.0.0.0"))
        assertTrue(ServerCommands.looksLikeServer("yarn dev"))
        assertTrue(ServerCommands.looksLikeServer("pnpm start"))
        assertTrue(ServerCommands.looksLikeServer("node server.js"))
        assertTrue(ServerCommands.looksLikeServer("python3 -m http.server 8000"))
        assertTrue(ServerCommands.looksLikeServer("python manage.py runserver"))
        assertTrue(ServerCommands.looksLikeServer("vite dev"))
        assertTrue(ServerCommands.looksLikeServer("npx vite"))
    }

    @Test
    fun `one-shot commands are not treated as servers`() {
        assertFalse(ServerCommands.looksLikeServer("npm run build"))
        assertFalse(ServerCommands.looksLikeServer("npm install"))
        assertFalse(ServerCommands.looksLikeServer("npm test"))
        assertFalse(ServerCommands.looksLikeServer("ls -la"))
        assertFalse(ServerCommands.looksLikeServer("node script.js --once"))
        assertFalse(ServerCommands.looksLikeServer("python3 calc.py"))
    }

    // ── Manager lifecycle ────────────────────────────────────────────────────

    @Test
    fun `start reports the detected port and access urls`() = runBlocking {
        backend.spawnResponder = {
            FakeProcess(
                output = "VITE ready\n  ➜  Local: http://localhost:5173/\n",
                aliveMs = Long.MAX_VALUE
            )
        }

        val outcome = manager.start("npm run dev", root)

        assertTrue(outcome is BackgroundStartOutcome.Started)
        val started = outcome as BackgroundStartOutcome.Started
        assertEquals(5173, started.service.port)
        assertEquals("http://localhost:5173", started.service.urlOnDevice)
        assertEquals("http://192.168.0.133:5173", started.service.urlNetwork)
        assertTrue(started.summary.contains("5173"))
        assertTrue(started.summary.contains("192.168.0.133"))
        assertTrue(started.service.running)

        // Service shows up in the live state + list.
        assertEquals(1, manager.list().size)
        assertEquals(1, manager.state.value.size)

        // Log tail contains the captured startup output.
        val tail = manager.logTail(started.service.id)
        assertNotNull(tail)
        assertTrue(tail!!.contains("VITE ready"))
    }

    @Test
    fun `command that exits immediately is reported as failure with output`() = runBlocking {
        backend.spawnResponder = {
            FakeProcess(output = "npm error Missing script: \"dev\"", exitCode = 1, aliveMs = 50)
        }

        val outcome = manager.start("npm run dev", root)

        assertTrue(outcome is BackgroundStartOutcome.Failed)
        val failed = outcome as BackgroundStartOutcome.Failed
        assertTrue(failed.summary.contains("exit 1"))
        assertTrue(failed.summary.contains("Missing script"))
        // Failed starts are not kept in the service list.
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `stop kills the service and removes it from the list`() = runBlocking {
        val process = FakeProcess(output = "listening on port 4000\n", aliveMs = Long.MAX_VALUE)
        backend.spawnResponder = { process }

        val started = manager.start("node server.js", root) as BackgroundStartOutcome.Started
        assertEquals(1, manager.list().size)

        val summary = manager.stop(started.service.id)

        assertTrue(summary.contains("Stopped"))
        assertTrue(manager.list().isEmpty())
        assertFalse(process.isAlive)
    }

    @Test
    fun `stop with unknown id returns a helpful message`() {
        assertTrue(manager.stop("svc-nope").contains("No background service"))
    }

    @Test
    fun `start without attached backend fails cleanly`() = runBlocking {
        val bare = BackgroundServiceManager(servicesDir = servicesDir, portWaitMs = 100)
        val outcome = bare.start("npm run dev", root)
        assertTrue(outcome is BackgroundStartOutcome.Failed)
    }

    // ── Executor background path ─────────────────────────────────────────────

    @Test
    fun `executeBackground applies the safety gate`() = runBlocking {
        val executor = CommandExecutor(
            workspaceRoot = root,
            backend = backend,
            backgroundServices = manager
        )
        val outcome = executor.executeBackground("rm -rf /")
        assertTrue(outcome is BackgroundStartOutcome.Failed)
        assertTrue((outcome as BackgroundStartOutcome.Failed).summary.contains("Blocked"))
        assertTrue(backend.invocations.isEmpty())
    }

    @Test
    fun `executeBackground reports missing addon for auto-install`() = runBlocking {
        val executor = CommandExecutor(
            workspaceRoot = root,
            backend = backend,
            installedAddons = { emptySet() },
            backgroundServices = manager
        )
        val outcome = executor.executeBackground("npm run dev")
        assertTrue(outcome is BackgroundStartOutcome.Failed)
        assertEquals("nodejs", (outcome as BackgroundStartOutcome.Failed).missingAddonId)
        assertTrue(backend.invocations.isEmpty())
    }

    @Test
    fun `executeBackground spawns when gates pass`() = runBlocking {
        backend.spawnResponder = { FakeProcess(output = "listening on port 9999\n", aliveMs = Long.MAX_VALUE) }
        val executor = CommandExecutor(
            workspaceRoot = root,
            backend = backend,
            installedAddons = { setOf("nodejs") },
            backgroundServices = manager
        )
        val outcome = executor.executeBackground("npm run dev")
        assertTrue(outcome is BackgroundStartOutcome.Started)
        assertEquals(1, backend.invocations.size)
        assertEquals("npm run dev", backend.invocations[0].command)
        // Working dir is contained inside the workspace.
        assertTrue(backend.invocations[0].workingDir.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun `executeBackground without manager fails cleanly`() = runBlocking {
        val executor = CommandExecutor(workspaceRoot = root, backend = backend)
        val outcome = executor.executeBackground("npm run dev")
        assertTrue(outcome is BackgroundStartOutcome.Failed)
        assertTrue((outcome as BackgroundStartOutcome.Failed).summary.contains("not available"))
    }

    @Test
    fun `blocked command classification stays consistent for servers`() {
        // Sanity: the safety gate used by executeBackground classifies as expected.
        assertEquals(WorkspaceSafety.RiskLevel.BLOCKED, WorkspaceSafety.classifyCommand("rm -rf /"))
        assertEquals(WorkspaceSafety.RiskLevel.SAFE, WorkspaceSafety.classifyCommand("npm run dev"))
    }
}
