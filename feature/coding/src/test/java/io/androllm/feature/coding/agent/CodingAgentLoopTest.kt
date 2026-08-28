package io.androllm.feature.coding.agent

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudTool
import io.androllm.feature.coding.environment.CommandExecutor
import io.androllm.feature.coding.environment.CommandResult
import io.androllm.feature.coding.environment.FakeShellBackend
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolExecutor
import io.androllm.feature.coding.tools.CodingToolRegistry
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceFileOps
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Agent-loop tests against a scripted cloud client: tool rounds, raw feedback,
 * missing-addon install + retry, continuations, loop guards and error paths.
 */
class CodingAgentLoopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Replays scripted event rounds, one list per stream() call. */
    private class ScriptedCloud(private val rounds: List<List<CloudStreamEvent>>) : CodingCloudClient {
        var callCount = 0
            private set
        var advertisedTools: List<CloudTool> = emptyList()
            private set
        val histories = mutableListOf<List<CloudChatMessage>>()

        override suspend fun isConfigured(): Boolean = true
        override suspend fun maxOutputTokens(): Long? = 4096
        override suspend fun activeModelLabel(): String = "scripted"

        override fun stream(
            messages: List<CloudChatMessage>,
            tools: List<CloudTool>,
            sessionId: String?,
            maxTokens: Int?
        ): Flow<CloudStreamEvent> = flow {
            histories += messages.toList()
            advertisedTools = tools
            val idx = callCount++
            if (idx >= rounds.size) {
                emit(CloudStreamEvent.Delta("(no more script)"))
                emit(CloudStreamEvent.Finish("stop"))
                return@flow
            }
            for (e in rounds[idx]) emit(e)
        }
    }

    private lateinit var root: File
    private lateinit var fileOps: WorkspaceFileOps
    private lateinit var backend: FakeShellBackend
    private val installed = mutableSetOf<String>()
    private lateinit var context: CodingToolContext

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        fileOps = WorkspaceFileOps(root)
        backend = FakeShellBackend()
        context = CodingToolContext(
            workspace = CodingWorkspace("ws-1", "WS", root.canonicalPath),
            fileOps = fileOps,
            executor = CommandExecutor(root, backend, installedAddons = { installed.toSet() })
        )
    }

    private fun toolCall(id: String, name: String, argsJson: String): List<CloudStreamEvent> =
        listOf(
            CloudStreamEvent.ToolCallDelta(index = 0, id = id, name = name, arguments = argsJson),
            CloudStreamEvent.Finish("tool_calls"),
            CloudStreamEvent.Done
        )

    private fun answer(text: String, finish: String = "stop"): List<CloudStreamEvent> =
        listOf(CloudStreamEvent.Delta(text), CloudStreamEvent.Finish(finish), CloudStreamEvent.Done)

    private fun loop(
        cloud: ScriptedCloud,
        missingAddonHandler: MissingAddonHandler = MissingAddonHandler { _, _ -> false },
        maxRounds: Int = 8,
        maxToolCalls: Int = 24
    ): CodingAgentLoop = CodingAgentLoop(
        cloud = cloud,
        toolRegistry = CodingToolRegistry(),
        toolExecutor = CodingToolExecutor(CodingToolRegistry()),
        contextProvider = { context },
        missingAddonHandler = missingAddonHandler,
        maxRounds = maxRounds,
        maxToolCalls = maxToolCalls
    )

    @Test
    fun `plain answer round returns the text and advertises coding tools`() = runBlocking {
        val cloud = ScriptedCloud(listOf(answer("Hello from the agent")))
        val history = mutableListOf(CloudChatMessage(role = "user", content = "hi"))

        val result = loop(cloud).run(history, sessionId = "s1")

        assertEquals("Hello from the agent", result)
        assertEquals(1, cloud.callCount)
        assertTrue(cloud.advertisedTools.map { it.function.name }.contains("run_command"))
    }

    @Test
    fun `tool call round executes the tool and feeds the result back`() = runBlocking {
        val args = """{"path":"note.txt","content":"hello world"}"""
        val cloud = ScriptedCloud(
            listOf(
                toolCall("call_1", "write_file", args),
                answer("File written.")
            )
        )
        val history = mutableListOf(CloudChatMessage(role = "user", content = "create note.txt"))

        val final = loop(cloud).run(history, sessionId = null)

        assertEquals("File written.", final)
        assertEquals("hello world", File(root, "note.txt").readText())
        // History must contain the assistant tool-call message and the tool result.
        val assistantWithCalls = history.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        assertEquals(1, assistantWithCalls.size)
        assertEquals("call_1", assistantWithCalls.first().toolCalls!!.first().id)
        val toolMsg = history.first { it.role == "tool" }
        assertEquals("call_1", toolMsg.toolCallId)
        assertTrue(toolMsg.content!!.contains("Wrote"))
    }

    @Test
    fun `raw command output reaches the model verbatim`() = runBlocking {
        val rawOut = "warn: \u001b[33mcolor\u001b[0m\n  indented line  "
        backend = FakeShellBackend { cmd, dir ->
            CommandResult(cmd, 0, stdout = rawOut, workingDir = dir.path)
        }
        context = CodingToolContext(
            CodingWorkspace("ws-1", "WS", root.canonicalPath),
            fileOps,
            CommandExecutor(root, backend, installedAddons = { installed.toSet() })
        )
        val cloud = ScriptedCloud(
            listOf(
                toolCall("c1", "run_command", """{"command":"echo hi"}"""),
                answer("done")
            )
        )
        val history = mutableListOf(CloudChatMessage(role = "user", content = "run it"))

        loop(cloud).run(history, sessionId = null)

        val toolMsg = history.first { it.role == "tool" }
        assertTrue("raw stdout must not be stripped", toolMsg.content!!.contains(rawOut))
        assertTrue(toolMsg.content!!.contains("[exit 0]"))
    }

    @Test
    fun `missing addon triggers install handler then retry succeeds`() = runBlocking {
        val installs = mutableListOf<String>()
        val handler = MissingAddonHandler { addonId, _ ->
            installs += addonId
            installed += addonId
            true
        }
        val missingSeen = mutableListOf<String>()
        val callbacks = CodingAgentCallbacks(onMissingAddon = { id, _ -> missingSeen += id })

        val cloud = ScriptedCloud(
            listOf(
                toolCall("c1", "run_command", """{"command":"npm install"}"""),
                toolCall("c2", "run_command", """{"command":"npm install"}"""),
                answer("Dependencies installed.")
            )
        )
        val history = mutableListOf(CloudChatMessage(role = "user", content = "npm install"))

        val final = loop(cloud, missingAddonHandler = handler).run(history, sessionId = null, callbacks)

        assertEquals("Dependencies installed.", final)
        assertEquals(listOf("nodejs"), installs)
        assertEquals(listOf("nodejs"), missingSeen)
        // First tool message tells the model to retry; second run actually executed.
        val toolMsgs = history.filter { it.role == "tool" }
        assertTrue(toolMsgs[0].content!!.contains("now installed"))
        assertTrue(toolMsgs[0].content!!.contains("Retry"))
        assertEquals(1, backend.invocations.size)
        assertEquals("npm install", backend.invocations[0].command)
    }

    @Test
    fun `declined addon install tells the model to stop and ask`() = runBlocking {
        val cloud = ScriptedCloud(
            listOf(
                toolCall("c1", "run_command", """{"command":"pnpm build"}"""),
                answer("You need the nodejs and pnpm addons.")
            )
        )
        val history = mutableListOf(CloudChatMessage(role = "user", content = "build"))

        loop(cloud, missingAddonHandler = MissingAddonHandler { _, _ -> false })
            .run(history, sessionId = null)

        val toolMsg = history.first { it.role == "tool" }
        assertTrue(toolMsg.content!!.contains("not installed"))
        assertTrue("command must never have run", backend.invocations.isEmpty())
    }

    @Test
    fun `length finish reason requests a continuation`() = runBlocking {
        val cloud = ScriptedCloud(
            listOf(
                answer("part one ", finish = "length"),
                answer("part two")
            )
        )
        val history = mutableListOf(CloudChatMessage(role = "user", content = "long answer"))

        val final = loop(cloud).run(history, sessionId = null)

        assertEquals("part one part two", final)
        assertTrue(history.any { it.role == "user" && it.content == "Continue exactly where you stopped." })
    }

    @Test
    fun `loop guard stops a runaway tool loop at maxRounds`() = runBlocking {
        val rounds = List(10) { toolCall("c$it", "list_dir", "{}") }
        val cloud = ScriptedCloud(rounds)
        val history = mutableListOf(CloudChatMessage(role = "user", content = "go"))

        loop(cloud, maxRounds = 3).run(history, sessionId = null)

        assertEquals("loop must stop at maxRounds", 3, cloud.callCount)
    }

    @Test
    fun `tool budget exhaustion ends the turn with a notice`() = runBlocking {
        val twoCalls = listOf(
            CloudStreamEvent.ToolCallDelta(0, "a", "list_dir", "{}"),
            CloudStreamEvent.ToolCallDelta(1, "b", "list_dir", "{}"),
            CloudStreamEvent.Finish("tool_calls"),
            CloudStreamEvent.Done
        )
        val cloud = ScriptedCloud(listOf(twoCalls, answer("stopped")))
        val history = mutableListOf(CloudChatMessage(role = "user", content = "go"))

        loop(cloud, maxToolCalls = 1).run(history, sessionId = null)

        val toolMsgs = history.filter { it.role == "tool" }
        assertEquals(2, toolMsgs.size)
        assertTrue(toolMsgs[1].content!!.contains("budget exhausted"))
    }

    @Test
    fun `tool call is announced as soon as its name streams in`() = runBlocking {
        // The name arrives in the first fragment; the arguments keep streaming
        // for a while (write_file carries the whole file content).
        val cloud = ScriptedCloud(
            listOf(
                listOf(
                    CloudStreamEvent.ToolCallDelta(0, "c1", "write_file", "{\"path\":\""),
                    CloudStreamEvent.ToolCallDelta(0, null, null, "a.txt\",\"content\":\""),
                    CloudStreamEvent.ToolCallDelta(0, null, null, "hello\"}"),
                    CloudStreamEvent.Finish("tool_calls"),
                    CloudStreamEvent.Done
                ),
                answer("done")
            )
        )
        val announced = mutableListOf<String>()
        val started = mutableListOf<String>()
        val callbacks = CodingAgentCallbacks(
            onToolAnnounced = { announced += it },
            onToolStart = { name, _ -> started += name }
        )
        val history = mutableListOf(CloudChatMessage(role = "user", content = "write it"))

        loop(cloud).run(history, sessionId = null, callbacks = callbacks)

        assertEquals("announcement fires exactly once, at first name sight", listOf("write_file"), announced)
        assertEquals(listOf("write_file"), started)
        assertEquals("hello", File(root, "a.txt").readText())
    }

    @Test
    fun `final answer is recorded in history exactly once`() = runBlocking {
        val args = """{"path":"note.txt","content":"hi"}"""
        val cloud = ScriptedCloud(
            listOf(
                toolCall("call_1", "write_file", args),
                answer("All done.")
            )
        )
        val history = mutableListOf(CloudChatMessage(role = "user", content = "do it"))

        loop(cloud).run(history, sessionId = null)

        // The final assistant text must appear exactly once, as the last message —
        // the ViewModel no longer re-appends the concatenated answer.
        val finalAssistant = history.filter { it.role == "assistant" && it.toolCalls.isNullOrEmpty() }
        assertEquals(1, finalAssistant.size)
        assertEquals("All done.", finalAssistant.single().content)
        assertEquals("All done.", history.last().content)
    }

    @Test
    fun `cloud failure raises CodingAgentException`() {
        val failing = object : CodingCloudClient {
            override suspend fun isConfigured(): Boolean = true
            override suspend fun maxOutputTokens(): Long? = null
            override suspend fun activeModelLabel(): String = "x"
            override fun stream(
                messages: List<CloudChatMessage>,
                tools: List<CloudTool>,
                sessionId: String?,
                maxTokens: Int?
            ): Flow<CloudStreamEvent> = flow { throw RuntimeException("network down") }
        }
        val loop = CodingAgentLoop(
            cloud = failing,
            toolRegistry = CodingToolRegistry(),
            toolExecutor = CodingToolExecutor(CodingToolRegistry()),
            contextProvider = { context }
        )
        try {
            runBlocking { loop.run(mutableListOf(CloudChatMessage(role = "user", content = "hi")), null) }
            fail("expected CodingAgentException")
        } catch (e: CodingAgentException) {
            assertTrue(e.message!!.contains("network down"))
        }
    }

    @Test
    fun `missing workspace raises CodingAgentException before any cloud call`() {
        val cloud = ScriptedCloud(listOf(answer("never")))
        val loop = CodingAgentLoop(
            cloud = cloud,
            toolRegistry = CodingToolRegistry(),
            toolExecutor = CodingToolExecutor(CodingToolRegistry()),
            contextProvider = { error("no workspace") }
        )
        try {
            runBlocking { loop.run(mutableListOf(CloudChatMessage(role = "user", content = "hi")), null) }
            fail("expected CodingAgentException")
        } catch (e: CodingAgentException) {
            assertTrue(e.message!!.contains("workspace"))
            assertEquals(0, cloud.callCount)
        }
    }
}
