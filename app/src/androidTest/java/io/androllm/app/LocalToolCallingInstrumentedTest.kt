package io.androllm.app

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.androllm.core.common.getOrNull
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.agent.DeviceContextProvider
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.tool.impl.BatteryTool
import io.androllm.core.tools.tool.impl.DeviceInfoTool
import io.androllm.core.tools.tool.impl.VolumeTool
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.core.LiteRtLmEngine
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.ModelLoadConfig
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full local tool-calling pipeline, on-device, with a real LiteRT-LM model:
 *
 *   user prompt → ToolPlanner.planLocal (free-form model output + structured
 *   JSON parser — the LiteRT compatibility layer) → registry → tool.execute
 *   → ToolResult.
 *
 * This is the production path ChatViewModel drives (planAndExecuteTools →
 * ToolRunCoordinator.runLocalWorkflow); here the pieces are constructed
 * directly so the test needs no UI or confirmation gate. Asserts:
 *
 *   - the loaded model is READY before planning
 *   - planLocal returns either NO calls (model answered directly) or only
 *     calls for tools actually registered (never hallucinated names)
 *   - every returned call executes through its Tool implementation without
 *     throwing, producing a real Success/Failure result
 *   - probeCapability() reports the model's native-JSON vs parser mode
 *
 * Requires a `.litertlm` model on the device (same resolution as the engine
 * stress test). Skipped when none is found.
 */
@RunWith(AndroidJUnit4::class)
class LocalToolCallingInstrumentedTest {

    companion object {
        private const val TAG = "ToolPipeTest"
    }

    private val context get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private var modelPath: String? = null
    private lateinit var repository: DefaultEngineRepository
    private lateinit var registry: ToolRegistry
    private lateinit var planner: ToolPlanner

    @Before
    fun setUp() {
        modelPath = resolveModelPath()
        assumeTrue("No .litertlm model found on device — skipping", modelPath != null)
    }

    /** Loads the model and builds the real tool stack (registry + planner). */
    private suspend fun buildStack(): Pair<ToolRegistry, ToolPlanner> {
        val engine = LiteRtLmEngine(context)
        repository = DefaultEngineRepository(engine)
        repository.initialize()

        val load = repository.loadModel(
            Model(id = "tooltest", name = "Tool Test", filePath = modelPath!!, quantization = "Q8"),
            ModelLoadConfig(contextLength = 2048, batchSize = 128, threads = 4)
        )
        assertTrue("model load failed: $load", load.isSuccess())
        val ready = repository.engineState.first()
        assertTrue("engine not Ready after load: $ready", ready is EngineState.Ready)
        Log.i(TAG, "Engine ready: ${(ready as EngineState.Ready).model.generalName}")

        // Minimal real tool stack: battery (no confirmation) + device info +
        // volume. Registry is the single source of truth for the planner.
        registry = ToolRegistry().apply {
            register(BatteryTool(context))
            register(DeviceInfoTool(context))
            register(VolumeTool(context))
        }
        val settingsStore = AutomationSettingsStore(context)
        val agentContext = AgentContextBuilder(DeviceContextProvider(context), AgentVariableStore())
        planner = ToolPlanner(registry, settingsStore, repository, agentContext)
        return registry to planner
    }

    @Test
    fun localToolPipelinePlansAndExecutesWithRealModel() = runBlocking {
        Log.i(TAG, "Loading model: $modelPath")
        buildStack()

        val messages = listOf(
            ChatPromptMessage(role = "user", content = "What is my battery percentage?")
        )

        Log.i(TAG, "PLAN START")
        val calls = planner.planLocal(messages)
        Log.i(TAG, "PLAN DONE: ${calls.size} call(s)")

        val known = registry.all().map { it.spec.name }.toSet()
        for (call in calls) {
            assertTrue(
                "planner hallucinated tool '${call.name}' — not in registry",
                call.name in known
            )
            val tool = registry.get(call.name)!!
            val result = tool.execute(call.arguments)
            Log.i(TAG, "EXEC '${call.name}' -> ${result.statusLabel}: ${result.summary.take(120)}")
            // A tool must never crash the process; any Success/Failure is valid.
            assertTrue("unexpected result type", result is ToolResult)
        }

        // Capability probe: one tiny generation classified as native JSON or
        // parser compatibility — must never hang or fail the pipeline.
        val cap = planner.probeCapability()
        Log.i(
            TAG,
            "CAPABILITY probe=${cap.probeStatus} rounds=${cap.planningRounds} " +
                "clean=${cap.cleanParses} fallback=${cap.fallbackParses} " +
                "sample=${cap.lastOutputSample.take(80)}"
        )
        assertNotNull(cap)
        assertFalse("probe must not report no output", cap.probeStatus.contains("no output"))

        repository.release()
    }

    @Test
    fun nativeToolMarkersAreExtractedFromAnswerGeneration() = runBlocking {
        Log.i(TAG, "Native-markers test: loading model $modelPath")
        buildStack()

        // Mirror the app's chat prompt exactly: the tool advertisement lives
        // in the system message, which is what teaches the model to emit
        // <|tool_call|> markers instead of just answering conversationally.
        val advertisement = ToolPromptBuilder(planner).advertisement()
        assertNotNull("advertisement must not be null", advertisement)
        val messages = listOf(
            ChatPromptMessage(role = "system", content = advertisement!!),
            ChatPromptMessage(role = "user", content = "What is my battery percentage?")
        )

        Log.i(TAG, "GENERATE (native) START")
        repository.generateChat(
            messages = messages,
            addAssistant = true,
            config = io.androllm.engine.models.GenerationConfig(maxTokens = 128, temperature = 0.2f)
        )
        val completed = repository.generationState.first() as? io.androllm.engine.api.GenerationState.Completed
        val text = completed?.text.orEmpty()
        val calls = repository.takeLastNativeToolCalls()
        Log.i(TAG, "GENERATE (native) DONE text='${text.take(80)}' nativeCalls=${calls.size}: ${calls.joinToString { "${it.name}(${it.argumentsJson})" }}")

        // The model must either call get_battery natively (this Gemma 4 repack
        // does) or answer directly — but the returned text must NEVER contain
        // raw marker tokens, and any extracted call must be executable.
        assertFalse("raw marker leaked into answer text", text.contains("<|tool_call"))
        assertFalse("raw marker leaked into answer text", text.contains("<tool_call|"))
        if (calls.isNotEmpty()) {
            assertEquals("get_battery", calls[0].name)
            val tool = registry.get(calls[0].name)
            assertNotNull("native call for unknown tool ${calls[0].name}", tool)
            val result = tool!!.execute(runCatching {
                Json.parseToJsonElement(calls[0].argumentsJson).jsonObject
            }.getOrElse { JsonObject(emptyMap()) })
            Log.i(TAG, "EXEC native '${calls[0].name}' -> ${result.statusLabel}: ${result.summary.take(100)}")
            assertTrue("native call failed", result.isSuccess)
        }

        repository.release()
    }

    private fun resolveModelPath(): String? {
        val args: Bundle = InstrumentationRegistry.getArguments()
        val argPath = args.getString("modelPath")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        argPath?.takeIf { File(it).exists() }?.let { return it }
        System.getenv("ANDROLLM_TEST_MODEL")?.takeIf { File(it).exists() }?.let { return it }

        val preferredNames = listOf("qwen3_0_6b_mixed_int4", "qwen3-0.6b", "qwen3")
        val candidates = listOf(
            File("/sdcard/Download"),
            File("/sdcard/Android/data/io.androllm.app/files/Documents/models"),
            File("/sdcard/Android/data/io.androllm.app/files/models"),
            File(ctx.filesDir, "models")
        )
        val all = candidates
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { f -> f.extension.equals("litertlm", ignoreCase = true) }?.toList() ?: emptyList()
            }
        if (all.isEmpty()) return null
        preferredNames.firstNotNullOfOrNull { pref ->
            all.firstOrNull { it.name.contains(pref, ignoreCase = true) }
        }?.let { return it.absolutePath }
        return all.maxByOrNull { it.length() }?.absolutePath
    }
}
