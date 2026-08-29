package io.androllm.feature.coding.tools

import io.androllm.feature.coding.environment.CommandExecutor
import io.androllm.feature.coding.environment.CommandResult
import io.androllm.feature.coding.environment.FakeShellBackend
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceFileOps
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end tool tests through [CodingToolExecutor]: file read/write/edit,
 * grep, run_command (raw output preserved), missing-addon detection and the
 * workspace security boundary.
 */
class CodingToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var fileOps: WorkspaceFileOps
    private lateinit var backend: FakeShellBackend
    private lateinit var executor: CommandExecutor
    private lateinit var context: CodingToolContext
    private lateinit var toolExecutor: CodingToolExecutor

    private val touchedFiles = mutableListOf<String>()
    private val usedTools = mutableListOf<String>()
    private var installed: MutableSet<String> = mutableSetOf()

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        fileOps = WorkspaceFileOps(root)
        backend = FakeShellBackend()
        executor = CommandExecutor(
            workspaceRoot = root,
            backend = backend,
            installedAddons = { installed.toSet() }
        )
        val workspace = CodingWorkspace(id = "ws-1", name = "Test WS", absolutePath = root.canonicalPath)
        context = CodingToolContext(
            workspace = workspace,
            fileOps = fileOps,
            executor = executor,
            onFileTouched = { path, _ -> touchedFiles += path },
            onToolUsed = { usedTools += it }
        )
        toolExecutor = CodingToolExecutor(CodingToolRegistry())
    }

    private suspend fun run(name: String, args: String): CodingToolResult =
        toolExecutor.execute(name, args, context)

    // ── read_file ────────────────────────────────────────────────────────────

    @Test
    fun `read_file returns content with line numbers`() = runBlocking {
        File(root, "hello.txt").writeText("alpha\nbeta")
        val result = run("read_file", """{"path":"hello.txt"}""")
        assertTrue(result.isSuccess)
        assertTrue(result.summary.contains("1: alpha"))
        assertTrue(result.summary.contains("2: beta"))
        assertEquals(listOf("hello.txt"), touchedFiles)
    }

    @Test
    fun `read_file on missing file is a retryable failure`() = runBlocking {
        val result = run("read_file", """{"path":"nope.txt"}""") as CodingToolResult.Failure
        assertTrue(result.retryable)
    }

    // ── write_file ───────────────────────────────────────────────────────────

    @Test
    fun `write_file creates file and parent dirs`() = runBlocking {
        val result = run("write_file", """{"path":"src/deep/Main.kt","content":"fun main() {}\n"}""")
        assertTrue(result.isSuccess)
        assertEquals("fun main() {}\n", File(root, "src/deep/Main.kt").readText())
    }

    // ── edit_file ────────────────────────────────────────────────────────────

    @Test
    fun `edit_file replaces a unique fragment`() = runBlocking {
        File(root, "a.txt").writeText("one two three")
        val result = run("edit_file", """{"path":"a.txt","old_text":"two","new_text":"TWO"}""")
        assertTrue(result.isSuccess)
        assertEquals("one TWO three", File(root, "a.txt").readText())
    }

    @Test
    fun `edit_file refuses ambiguous match without replace_all`() = runBlocking {
        File(root, "b.txt").writeText("x x x")
        val result = run("edit_file", """{"path":"b.txt","old_text":"x","new_text":"y"}""") as CodingToolResult.Failure
        assertTrue(result.summary.contains("matches"))
        assertEquals("x x x", File(root, "b.txt").readText())
    }

    @Test
    fun `edit_file replace_all replaces every occurrence`() = runBlocking {
        File(root, "c.txt").writeText("x x x")
        val result = run("edit_file", """{"path":"c.txt","old_text":"x","new_text":"y","replace_all":true}""")
        assertTrue(result.isSuccess)
        assertEquals("y y y", File(root, "c.txt").readText())
    }

    @Test
    fun `edit_file with missing old_text fails`() = runBlocking {
        File(root, "d.txt").writeText("abc")
        val result = run("edit_file", """{"path":"d.txt","old_text":"zzz","new_text":"y"}""") as CodingToolResult.Failure
        assertTrue(result.summary.contains("not found"))
    }

    // ── grep ─────────────────────────────────────────────────────────────────

    @Test
    fun `grep finds matches with file and line numbers`() = runBlocking {
        File(root, "src").mkdirs()
        File(root, "src/One.kt").writeText("fun main() {}\nval todo = 1\n")
        File(root, "src/Two.md").writeText("todo list\n")
        val result = run("grep", """{"pattern":"todo","include":"*.kt"}""")
        assertTrue(result.isSuccess)
        assertTrue(result.summary.contains("src/One.kt:2"))
        assertFalse("glob must exclude .md", result.summary.contains("Two.md"))
    }

    @Test
    fun `grep with no matches reports success`() = runBlocking {
        val result = run("grep", """{"pattern":"zzz_never"}""")
        assertTrue(result.isSuccess)
        assertTrue(result.summary.contains("No matches"))
    }

    // ── run_command: raw output preserved ────────────────────────────────────

    @Test
    fun `run_command returns raw stdout and stderr verbatim`() = runBlocking {
        val rawOut = "warning: thing \u001b[31mred\u001b[0m\nline2  trailing   spaces   "
        val rawErr = "error: boom\n\tstack trace"
        backend = FakeShellBackend { cmd, dir ->
            CommandResult(cmd, exitCode = 0, stdout = rawOut, stderr = rawErr, workingDir = dir.path)
        }
        executor = CommandExecutor(root, backend)
        context = CodingToolContext(
            CodingWorkspace("ws-1", "Test WS", root.canonicalPath), fileOps, executor
        )
        val result = run("run_command", """{"command":"./build.sh"}""")
        assertTrue(result.isSuccess)
        assertTrue("stdout must be preserved verbatim", result.summary.contains(rawOut))
        assertTrue("stderr must be preserved verbatim", result.summary.contains(rawErr))
        assertTrue(result.summary.contains("[exit 0]"))
    }

    @Test
    fun `run_command non-zero exit is a failure but keeps raw output`() = runBlocking {
        backend = FakeShellBackend { cmd, dir ->
            CommandResult(cmd, exitCode = 2, stdout = "partial", stderr = "fatal: bad", workingDir = dir.path)
        }
        executor = CommandExecutor(root, backend)
        context = CodingToolContext(
            CodingWorkspace("ws-1", "Test WS", root.canonicalPath), fileOps, executor
        )
        val result = run("run_command", """{"command":"./test.sh"}""") as CodingToolResult.Failure
        assertTrue(result.retryable)
        assertTrue(result.summary.contains("fatal: bad"))
        assertTrue(result.summary.contains("[exit 2]"))
    }

    // ── missing addon detection ──────────────────────────────────────────────

    @Test
    fun `run_command with missing runtime reports missingAddonId`() = runBlocking {
        installed.clear()
        val result = run("run_command", """{"command":"npm install"}""") as CodingToolResult.Failure
        assertEquals("nodejs", result.missingAddonId)
        assertTrue(result.retryable)
        assertTrue("command must NOT have run", backend.invocations.isEmpty())
    }

    @Test
    fun `run_command runs once the addon is installed`() = runBlocking {
        installed += "nodejs"
        val result = run("run_command", """{"command":"node -v"}""")
        assertTrue(result.isSuccess)
        assertEquals(1, backend.invocations.size)
    }

    // ── security boundary ────────────────────────────────────────────────────

    @Test
    fun `read_file cannot escape the workspace via traversal`() = runBlocking {
        File(tmp.root, "secret.txt").writeText("top secret")
        val result = run("read_file", """{"path":"../secret.txt"}""") as CodingToolResult.Failure
        assertTrue(result.summary.contains("Security"))
        assertFalse(result.summary.contains("top secret"))
    }

    @Test
    fun `write_file cannot escape the workspace via absolute path`() = runBlocking {
        val outside = File(tmp.root, "outside.txt")
        val result = run(
            "write_file",
            """{"path":"${outside.canonicalPath.replace("\\", "\\\\")}","content":"pwned"}"""
        ) as CodingToolResult.Failure
        assertTrue(result.summary.contains("Security"))
        assertFalse(outside.exists())
    }

    @Test
    fun `grep cannot search outside the workspace`() = runBlocking {
        val result = run("grep", """{"pattern":"x","path":"../../.."}""") as CodingToolResult.Failure
        assertTrue(result.summary.contains("Security"))
    }

    // ── executor-level guards ────────────────────────────────────────────────

    @Test
    fun `unknown tool is rejected with the tool list`() = runBlocking {
        val result = run("hack_the_planet", "{}") as CodingToolResult.Failure
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("read_file"))
    }

    @Test
    fun `malformed arguments json is rejected`() = runBlocking {
        val result = run("read_file", "{not json") as CodingToolResult.Failure
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("malformed"))
    }

    @Test
    fun `tool use is recorded in the context`() = runBlocking {
        run("list_dir", "{}")
        assertEquals(listOf("list_dir"), usedTools)
    }

    @Test
    fun `confirmation gate can decline a flagged tool`() = runBlocking {
        val flagged = object : CodingTool {
            override val spec = CodingToolSpec(
                name = "danger",
                description = "dangerous",
                parameters = JsonObject(emptyMap()),
                requiresConfirmation = true,
                readOnly = false
            )
            override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult =
                CodingToolResult.Success("should never run")
        }
        val gated = CodingToolExecutor(CodingToolRegistry(listOf(flagged)), CodingToolExecutor.DeclineAll)
        val result = gated.execute("danger", "{}", context) as CodingToolResult.Failure
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("declined"))
    }

    @Test
    fun `registry exposes all default tools as cloud tools`() {
        val registry = CodingToolRegistry()
        val expected = setOf(
            "read_file", "write_file", "edit_file", "replace_text", "grep",
            "list_dir", "file_tree", "run_command", "git_status", "workspace_summary",
            "list_background_services", "stop_background_service", "update_plan"
        )
        assertEquals(expected, registry.names())
        val cloud = registry.toCloudTools()
        assertEquals(expected.size, cloud.size)
        assertTrue(cloud.all { it.type == "function" })
        assertNotNull(cloud.first().function.parameters["type"])
    }

    // ── run_command: background services ─────────────────────────────────────

    @Test
    fun `run_command with explicit background starts a service`() = runBlocking {
        val manager = io.androllm.feature.coding.environment.BackgroundServiceManager(
            servicesDir = tmp.newFolder("svc"),
            portWaitMs = 1500,
            deviceIpProvider = { "10.0.0.5" }
        )
        manager.attachBackend(backend)
        backend.spawnResponder = {
            io.androllm.feature.coding.environment.FakeProcess(
                output = "listening on port 4321\n",
                aliveMs = Long.MAX_VALUE
            )
        }
        installed += "nodejs"
        val ctx = CodingToolContext(
            workspace = context.workspace,
            fileOps = fileOps,
            executor = CommandExecutor(root, backend, installedAddons = { installed.toSet() }, backgroundServices = manager),
            services = manager
        )

        val result = toolExecutor.execute(
            "run_command",
            """{"command":"node server.js","background":true}""",
            ctx
        )

        assertTrue(result.isSuccess)
        assertTrue(result.summary.contains("background"))
        assertTrue(result.summary.contains("4321"))
        assertTrue(result.summary.contains("http://10.0.0.5:4321"))
        assertEquals(1, manager.list().size)
    }

    @Test
    fun `run_command auto-backgrounds server-like commands`() = runBlocking {
        val manager = io.androllm.feature.coding.environment.BackgroundServiceManager(
            servicesDir = tmp.newFolder("svc2"),
            portWaitMs = 1500,
            deviceIpProvider = { null }
        )
        manager.attachBackend(backend)
        backend.spawnResponder = {
            io.androllm.feature.coding.environment.FakeProcess(
                output = "Local: http://localhost:5173/\n",
                aliveMs = Long.MAX_VALUE
            )
        }
        installed += "nodejs"
        val ctx = CodingToolContext(
            workspace = context.workspace,
            fileOps = fileOps,
            executor = CommandExecutor(root, backend, installedAddons = { installed.toSet() }, backgroundServices = manager),
            services = manager
        )

        val result = toolExecutor.execute("run_command", """{"command":"npm run dev"}""", ctx)

        assertTrue(result.isSuccess)
        assertTrue("auto-background note expected", result.summary.contains("Auto-detected"))
        assertEquals(5173, manager.list().first().port)
    }

    @Test
    fun `run_command foreground path streams output lines to the sink`() = runBlocking {
        val streamingBackend = FakeShellBackend { cmd, dir ->
            CommandResult(cmd, 0, stdout = "line1\nline2", workingDir = dir.path)
        }
        val exec2 = CommandExecutor(root, streamingBackend, installedAddons = { installed.toSet() })
        val lines = mutableListOf<String>()
        val ctx2 = CodingToolContext(
            workspace = context.workspace,
            fileOps = fileOps,
            executor = exec2,
            onCommandOutput = { lines += it }
        )

        val result = toolExecutor.execute("run_command", """{"command":"echo hi"}""", ctx2)

        assertTrue(result.isSuccess)
        // Default fake runStreaming emits the combined output lines after the run.
        assertEquals(listOf("line1", "line2"), lines)
    }

    @Test
    fun `list and stop background services tools work end to end`() = runBlocking {
        val manager = io.androllm.feature.coding.environment.BackgroundServiceManager(
            servicesDir = tmp.newFolder("svc3"),
            portWaitMs = 1500,
            deviceIpProvider = { "10.0.0.5" }
        )
        manager.attachBackend(backend)
        backend.spawnResponder = {
            io.androllm.feature.coding.environment.FakeProcess(
                output = "listening on port 7777\n",
                aliveMs = Long.MAX_VALUE
            )
        }
        val ctx = CodingToolContext(
            workspace = context.workspace,
            fileOps = fileOps,
            executor = executor,
            services = manager
        )

        val empty = toolExecutor.execute("list_background_services", "{}", ctx)
        assertTrue(empty.summary.contains("No background services"))

        manager.start("npm run dev", root)
        val id = manager.list().first().id

        val listed = toolExecutor.execute("list_background_services", """{"id":"$id"}""", ctx)
        assertTrue(listed.isSuccess)
        assertTrue(listed.summary.contains(id))
        assertTrue(listed.summary.contains("7777"))
        assertTrue(listed.summary.contains("listening on port 7777"))

        val stopped = toolExecutor.execute("stop_background_service", """{"id":"$id"}""", ctx)
        assertTrue(stopped.isSuccess)
        assertTrue(stopped.summary.contains("Stopped"))
        assertTrue(manager.list().isEmpty())
    }
}
