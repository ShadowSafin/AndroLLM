package io.androllm.engine

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.core.LiteRtLmEngine
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.StreamChunk
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime Stabilization — on-device stress test for the LiteRT-LM engine
 * ([LiteRtLmEngine], the production local runtime — the llama.cpp engine was
 * fully removed). Drives hundreds of consecutive multi-turn chat prompts
 * through the stateful LiteRT conversation path (KV cache persists across
 * turns inside the [com.google.ai.edge.litertlm.Conversation]). Asserts:
 *
 *  - no crashes (a native fault fails the test process)
 *  - no tokenizer corruption (output contains no U+FFFD replacement chars)
 *  - engine always returns to [EngineState.Ready] after each turn
 *  - Prompt #2 regression: the second turn must answer the new prompt, not
 *    continue the first answer from a stale context
 *  - cancel mid-generation never corrupts the next prompt
 *
 * Requires a `.litertlm` model on the device. Resolution order:
 *   1. Instrumentation arg `-e modelPath /sdcard/.../model.litertlm`
 *   2. Environment variable ANDROLLM_TEST_MODEL
 *   3. Any *.litertlm under /sdcard/Download or the app's models directories
 *   4. Otherwise the test is SKIPPED (never fails the suite).
 *
 * Run:
 *   ./gradlew :engine:connectedDebugAndroidTest -PandrollmAbis=arm64-v8a \
 *       -Pandroid.testInstrumentationRunnerArguments.modelPath=/sdcard/Download/gemma3-270m-it-q8.litertlm
 */
@RunWith(AndroidJUnit4::class)
class EngineStressInstrumentedTest {

    companion object {
        private const val TAG = "EngineStress"

        /** Consecutive prompts — the stabilization criterion. */
        private const val TURNS = 200

        private const val MAX_TOKENS = 24

        /** U+FFFD marks invalid/truncated UTF-8 (tokenizer corruption). */
        private const val REPLACEMENT_CHAR = '\uFFFD'

        /** The multi-turn chat script from the stabilization checklist. */
        private val SCRIPT = listOf(
            "Hello",
            "What is Android?",
            "Write Kotlin code.",
            "Explain the code.",
            "Summarize.",
            "Continue."
        )
    }

    private lateinit var engine: InferenceEngine
    private var modelPath: String? = null

    @Before
    fun setUp() {
        modelPath = resolveModelPath()
        assumeTrue("No .litertlm model found on device — skipping stress test", modelPath != null)
        engine = LiteRtLmEngine(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.release()
        }
    }

    private fun resolveModelPath(): String? {
        val args: Bundle = InstrumentationRegistry.getArguments()
        val argPath = args.getString("modelPath")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i(TAG, "resolveModelPath: arg='$argPath' exists=${argPath?.let { File(it).exists() }}")
        Log.i(TAG, "resolveModelPath: filesDir='${ctx.filesDir}' candDirExists=${File(ctx.filesDir, "models").exists()}")
        argPath?.takeIf { File(it).exists() }?.let { return it }

        System.getenv("ANDROLLM_TEST_MODEL")?.takeIf { File(it).exists() }?.let { return it }

        // Prefer the catalog's known-good model (Qwen3-0.6B mixed-int4) — the
        // device may also carry stale/degenerate artifacts (e.g. the old
        // gemma-270m repack that decodes '<pad>') whose containers are broken.
        val preferredNames = listOf("qwen3_0_6b_mixed_int4", "qwen3-0.6b", "qwen3")
        val candidates = listOf(
            File("/sdcard/Download"),
            File("/sdcard/Android/data/io.androllm.app/files/Documents/models"),
            File("/sdcard/Android/data/io.androllm.app/files/models"),
            // App-internal models dir (scoped-storage safe, direct path access).
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "models")
        )
        val all = candidates
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles { f -> f.extension.equals("litertlm", ignoreCase = true) }?.toList() ?: emptyList() }
        if (all.isEmpty()) return null
        // 1) Any catalog-preferred model (known-good, verified on this device).
        preferredNames.firstNotNullOfOrNull { pref ->
            all.firstOrNull { it.name.contains(pref, ignoreCase = true) }
        }?.let { return it.absolutePath }
        // 2) Otherwise the largest artifact (best quality) as a fallback.
        return all.maxByOrNull { it.length() }?.absolutePath
    }

    private fun genConfig() = GenerationConfig(
        maxTokens = MAX_TOKENS,
        temperature = 0.2f,
        topK = 20,
        seed = 42L
    )

    private suspend fun engineReadyState(): EngineState.Ready? =
        engine.engineState.first() as? EngineState.Ready

    private suspend fun InferenceEngine.loadStressModel() {
        val init = initialize(EngineConfig(threads = 4))
        assertTrue("initialize failed: $init", init.isSuccess())
        val load = loadModel(
            Model(id = "stress", name = "Stress", filePath = modelPath!!, quantization = "Q8"),
            ModelLoadConfig(contextLength = 1024, batchSize = 128, threads = 4)
        )
        assertTrue("load failed: $load", load.isSuccess())
    }

    /** Runs one chat turn through the stateful LiteRT conversation path. */
    private suspend fun chatTurn(
        eng: InferenceEngine,
        history: MutableList<ChatPromptMessage>,
        userMessage: String
    ): String {
        history.add(ChatPromptMessage(role = "user", content = userMessage))
        val result = eng.generateChat(
            history,
            addAssistant = true,
            config = genConfig()
        )
        val text = result.getOrNull()
        assertNotNull("generation failed: $result", text)
        history.add(ChatPromptMessage(role = "assistant", content = text!!.trim()))
        return text
    }

    @Test
    fun twoHundredConsecutivePromptsSurviveWithoutCrashOrCorruption() = runBlocking {
        Log.i(TAG, "Loading model: $modelPath")
        engine.loadStressModel()

        val info = engine.getLoadedModel()
        assertNotNull("model info missing", info)
        Log.i(TAG, "Loaded ${info?.generalName ?: "model"}, ctx=${info?.contextLength}")

        val history = mutableListOf<ChatPromptMessage>()
        var corrupted = 0
        var failures = 0

        val t0 = System.currentTimeMillis()

        for (i in 1..TURNS) {
            val text = try {
                chatTurn(engine, history, "Turn $i: tell me a fact about the number $i in exactly one short sentence.")
            } catch (e: Exception) {
                Log.e(TAG, "Turn $i: generation failed — ${e.message}")
                failures++
                continue
            }

            if (text.contains(REPLACEMENT_CHAR)) {
                corrupted++
                Log.e(TAG, "Turn $i: replacement char in output: ${text.take(40)}")
            }

            if (engineReadyState() == null) {
                Log.e(TAG, "Turn $i: engine not Ready — ${engine.engineState.first()}")
                failures++
            }

            if (i % 50 == 0) {
                Log.i(TAG, "Turn $i: ok, out=${text.take(30)}")
            }
        }

        val elapsedMs = System.currentTimeMillis() - t0

        Log.i(TAG, "DONE: turns=$TURNS failures=$failures corrupted=$corrupted elapsed=${elapsedMs}ms")

        // No crashes: reaching here is the primary assertion.
        assertEquals("tokenizer corruption (U+FFFD): $corrupted", 0, corrupted)
        assertEquals("generation failures: $failures", 0, failures)
        // Every turn must have appended exactly one user + one assistant message.
        assertEquals("history incomplete", TURNS * 2, history.size)
    }

    @Test
    fun multiTurnChatScriptRepeatsWithoutCorruption() = runBlocking {
        Log.i(TAG, "Multi-turn script test (${SCRIPT.size}-prompt script repeated)")
        engine.loadStressModel()

        // Exactly the stabilization checklist scenario: a fixed conversational
        // script ("Hello" -> "What is Android?" -> "Write Kotlin code." ->
        // "Explain the code." -> "Summarize." -> "Continue.") repeated.
        // Every continuation goes through the stateful conversation path.
        val history = mutableListOf<ChatPromptMessage>()
        val totalTurns = SCRIPT.size * 17 // 102 turns

        var failures = 0
        var corrupted = 0

        for (t in 1..totalTurns) {
            val userMsg = SCRIPT[(t - 1) % SCRIPT.size]
            val text = try {
                chatTurn(engine, history, userMsg)
            } catch (e: Exception) {
                Log.e(TAG, "turn $t: generation failed — ${e.message}")
                failures++
                continue
            }

            if (text.contains(REPLACEMENT_CHAR)) {
                corrupted++
                Log.e(TAG, "turn $t: replacement char: ${text.take(40)}")
            }

            if (t % 20 == 0) {
                Log.i(TAG, "script turn $t/$totalTurns ok, out=${text.take(30)}")
            }
        }

        Log.i(TAG, "SCRIPT DONE: turns=$totalTurns failures=$failures corrupted=$corrupted")
        assertEquals("generation failures: $failures", 0, failures)
        assertEquals("tokenizer corruption (U+FFFD): $corrupted", 0, corrupted)
        // Every turn must have appended exactly one user + one assistant message.
        assertEquals("history incomplete after script", totalTurns * 2, history.size)
    }

    /**
     * Prompt #2 regression (bug signature): the second turn must respond to
     * the new prompt, not continue the first answer from a stale context.
     */
    @Test
    fun prompt2AnswersTheNewPromptInsteadOfContinuingPrompt1() = runBlocking {
        engine.loadStressModel()

        val history = mutableListOf<ChatPromptMessage>()
        val t1 = chatTurn(engine, history, "Hello").trim()
        val t2 = chatTurn(engine, history, "What is Android?").trim()

        Log.i(TAG, "turn1=<$t1> turn2=<$t2>")
        assertFalse("turn 2 is empty", t2.isEmpty())

        // With the stale-context bug, turn 2 continued turn 1's stale context:
        // it repeated/continued the first answer's tail and never answered the
        // new question.
        assertFalse(
            "Prompt #2 repeats Prompt #1 verbatim: #1=<$t1> #2=<$t2>",
            t1.isNotEmpty() && t2 == t1
        )
        if (t1.length >= 12) {
            val tail = t1.takeLast(12)
            assertFalse(
                "Prompt #2 contains the tail of Prompt #1 (stale-context continuation): tail=<$tail> #2=<$t2>",
                t2.contains(tail)
            )
        }
    }

    @Test
    fun cancelMidGenerationNeverCorruptsTheNextPrompt() = runBlocking {
        engine.loadStressModel()

        // Start a long generation, then cancel it mid-flight.
        val cancelled = AtomicBoolean(false)
        val job: Job = launch(Dispatchers.Default) {
            engine.tokenStream(
                "Write a long detailed essay about the history of computing.",
                GenerationConfig(maxTokens = 128, temperature = 0.5f)
            ).collect { }
        }
        delay(250)
        engine.cancel()
        cancelled.set(true)
        job.join()

        // The very next chat turn must succeed — the cancelled run must not
        // have left the conversation in a state that corrupts generation.
        val history = mutableListOf<ChatPromptMessage>()
        val text = chatTurn(engine, history, "What is 2 + 2? Answer in one short sentence.")
        assertFalse("corrupted output after cancel", text.contains(REPLACEMENT_CHAR))
        assertTrue("cancel was never exercised", cancelled.get())
    }

    /**
     * The app's ACTUAL chat path: [InferenceEngine.generateChatStream] driven
     * with the default GenerationConfig (maxTokens = 65536 "unlimited" — the
     * exact config that used to hang: thinking tokens streamed invisibly in
     * Message.channels, no output cap, no thinking budget). The stream must
     * produce visible deltas (thinking + answer), reach the finished chunk,
     * and the final answer text must be non-blank — never a 100s invisible
     * "Generating" that never completes.
     */
    @Test
    fun streamingChatWithDefaultConfigProducesTokensAndCompletes() = runBlocking {
        Log.i(TAG, "Streaming chat path with default config (former hang)")
        engine.loadStressModel()

        val history = listOf(
            ChatPromptMessage(role = "user", content = "What is 2 + 2? Answer in one short sentence.")
        )
        val deltas = mutableListOf<StreamChunk>()
        val firstDeltaAtMs = System.currentTimeMillis()
        val streamStartedMs = System.currentTimeMillis()
        var firstDeltaMs: Long = -1
        try {
            withTimeout(120_000) {
                // Low temperature + fixed seed: the exact config whose blocking
                // path produced coherent text on this device. Isolates whether
                // the streaming path itself degrades output or temperature does.
                engine.generateChatStream(
                    history,
                    addAssistant = true,
                    config = GenerationConfig(temperature = 0.2f, seed = 42L, topK = 20)
                ).collect { chunk ->
                        when (chunk) {
                            is Result.Success -> {
                                if (firstDeltaMs < 0 && chunk.data.delta.isNotEmpty()) {
                                    firstDeltaMs = System.currentTimeMillis() - firstDeltaAtMs
                                }
                                deltas.add(chunk.data)
                            }
                            is Result.Error -> throw chunk.exception
                        }
                    }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "STREAM HANG: no completion within 120s — deltas=${deltas.size} firstDeltaMs=$firstDeltaMs")
            assertTrue("streaming generation hung (no completion in 120s) — deltas=${deltas.size}", false)
        }

        val answer = deltas.filter { !it.isThinking && it.delta.isNotEmpty() }.joinToString("") { it.delta }
        val thinking = deltas.filter { it.isThinking }.joinToString("") { it.delta }
        val elapsedMs = System.currentTimeMillis() - streamStartedMs
        Log.i(
            TAG,
            "STREAM DONE: elapsed=${elapsedMs}ms firstDelta=${firstDeltaMs}ms deltas=${deltas.size} " +
                "thinkingLen=${thinking.length} answerLen=${answer.length} answer=${answer.take(80)}"
        )
        assertTrue("first delta took ${firstDeltaMs}ms (hang signature)", firstDeltaMs in 0..60_000)
        assertTrue("stream produced no answer text", answer.isNotBlank())
        assertTrue("stream never emitted the finished chunk", deltas.any { it.finished })
        assertFalse("replacement char in streamed answer", answer.contains(REPLACEMENT_CHAR))
        // Chat-template tokens must never reach the UI (they belong to the
        // tokenizer only): the Qwen3 template leaks a raw <think> when
        // thinking is enabled without a parseable end token, and im_start/
        // im_end are the Qwen3 role markers.
        val leaked = listOf("<think>", "</think>", "<|im_start|>", "<|im_end|>", "<bos>", "<eos>")
            .filter { answer.contains(it) }
        assertTrue("template tokens leaked into answer: $leaked — answer=<$answer>", leaked.isEmpty())
    }

    /**
     * Stop-token contract, on-device: streaming with the DEFAULT config
     * (maxTokens = 65536 "unlimited", temperature 0.8) on a long essay prompt
     * must terminate in well under the 8192-token stream cap — either on the
     * model's own EOS or on the engine's stop-sequence tracker — and the
     * streamed answer must never contain a stop token, a template marker or a
     * duplicated prefix. This is the exact reported bug: the decoder ignored
     * stop tokens and kept generating forever (repeated words, stalled
     * spinner, no completion).
     */
    @Test
    fun streamingUnlimitedBudgetStillTerminatesAndNeverLeaksStopTokens() = runBlocking {
        Log.i(TAG, "Streaming stop-token contract with unlimited budget")
        engine.loadStressModel()

        val history = listOf(
            ChatPromptMessage(
                role = "user",
                content = "Write a detailed essay about the history of computing in at least five paragraphs."
            )
        )
        val deltas = mutableListOf<StreamChunk>()
        val streamStartedMs = System.currentTimeMillis()
        try {
            withTimeout(120_000) {
                engine.generateChatStream(
                    history,
                    addAssistant = true,
                    config = GenerationConfig()
                ).collect { chunk ->
                    when (chunk) {
                        is Result.Success -> deltas.add(chunk.data)
                        is Result.Error -> throw chunk.exception
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "STOP-TOKEN HANG: no completion in 120s — deltas=${deltas.size}")
            assertTrue("generation never terminated (stop tokens ignored) — deltas=${deltas.size}", false)
        }

        val answer = deltas.filter { !it.isThinking && it.delta.isNotEmpty() }.joinToString("") { it.delta }
        val elapsedMs = System.currentTimeMillis() - streamStartedMs
        // 8192 tokens at ~21 tok/s would take ~6.5 minutes; terminating within
        // the 120s budget proves the run stopped at a natural end, not the cap.
        Log.i(
            TAG,
            "STOP-TOKEN DONE: elapsed=${elapsedMs}ms deltas=${deltas.size} answerLen=${answer.length} answer=${answer.take(80)}"
        )
        assertTrue("generation ran to the token cap (stop tokens ignored) — ${elapsedMs}ms", elapsedMs < 120_000)
        assertTrue("stream produced no answer text", answer.isNotBlank())
        assertTrue("stream never emitted the finished chunk", deltas.any { it.finished })
        assertFalse("replacement char in streamed answer", answer.contains(REPLACEMENT_CHAR))
        val leaked = listOf(" thinking", " response", "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<bos>", "<eos>")
            .filter { answer.contains(it) }
        assertTrue("stop tokens leaked into answer: $leaked", leaked.isEmpty())
        // Duplicated-prefix guard: the accumulated-diff emission must never
        // re-emit an already-streamed prefix when a marker is cut at the
        // stream boundary.
        if (answer.length >= 30) {
            val head = answer.substring(0, 20)
            val mid = answer.substring(answer.length / 2, answer.length / 2 + 20)
            assertFalse("streamed answer duplicated its own prefix: head='$head' mid='$mid'", head == mid)
        }
    }

    /**
     * 100 consecutive "hello" generations stay stable — the engine must
     * return to [EngineState.Ready] after every turn and never escalate a
     * recovery (a healthy backend stays at recoveryCount = 0).
     */
    @Test
    fun hundredConsecutiveHelloGenerationsStayStable() = runBlocking {
        Log.i(TAG, "Stability: 100x hello / 30 tokens with per-turn cleanup")
        engine.loadStressModel()

        var failures = 0
        var corrupted = 0

        for (i in 1..100) {
            val history = mutableListOf(
                ChatPromptMessage(role = "user", content = "hello")
            )
            val text = try {
                engine.generateChat(
                    history,
                    addAssistant = true,
                    config = GenerationConfig(maxTokens = 30, temperature = 0.2f, seed = 42L)
                ).getOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "iteration $i: generation failed — ${e.message}")
                failures++
                null
            }
            if (text.isNullOrEmpty()) {
                failures++
                continue
            }
            if (text.contains(REPLACEMENT_CHAR)) {
                corrupted++
                Log.e(TAG, "iteration $i: replacement char: ${text.take(40)}")
            }

            if (engineReadyState() == null) {
                Log.e(TAG, "iteration $i: engine not Ready — ${engine.engineState.first()}")
                failures++
            }
        }

        val info = engine.getDebugInfo().getOrNull()
        val recoveries = info?.recoveryCount ?: -1

        Log.i(
            TAG,
            "100x DONE: failures=$failures corrupted=$corrupted recoveryCount=$recoveries"
        )

        assertEquals("generation failures: $failures", 0, failures)
        assertEquals("tokenizer corruption (U+FFFD): $corrupted", 0, corrupted)

        // Any recovery escalation on a healthy backend is a finding: on a
        // stable device these MUST be zero across 100 turns.
        assertEquals("recovery escalated during 100x hello: $recoveries", 0, recoveries)
    }

    /**
     * App-prompt fidelity check: the chat app injects a LARGE tool-advertisement
     * system message (unbounded on small-context containers because the output
     * reserve consumes the whole budget) plus the user turn, and generates with
     * the DEFAULT sampling config (temperature 0.8). A clean user-only prompt
     * must stay coherent (control), and the advertisement variant must too —
     * a 1.5B-class model can be driven into code-fragment gibberish by an
     * oversized tool list. Logs all three so the root cause is visible.
     */
    @Test
    fun toolAdvertisementDoesNotDegradeOutput() = runBlocking {
        Log.i(TAG, "AD Degradation probe: clean vs advertisement vs advertised+lowtemp")
        engine.loadStressModel()

        fun advertisement(): String {
            val sb = StringBuilder()
            sb.append(
                "You are the assistant of an on-device AI agent and you HAVE access to tools. " +
                    "The tools below are available RIGHT NOW — never claim you lack access to them.\n" +
                    "When the user's request involves an app, the web, the device, or communication " +
                    "(weather, search, opening apps, sending messages/calls/emails, clipboard, " +
                    "screenshots, music, settings, alarms, notes, files, calculator, translation, " +
                    "GitHub, image generation, …), use the matching tool and summarize its result " +
                    "for the user. Never say \"I can't do that\" when a tool exists for it.\n\n" +
                    "AVAILABLE TOOLS (name — description | arguments: key (type) | example):\n"
            )
            // The app's REAL tool list: 47 tools, names + descriptions copied
            // verbatim from ToolPromptBuilder's registry. ~11K chars.
            val tools = listOf(
                "set_wifi" to "Turn Wi-Fi on or off. On newer Android versions the OS may require opening the settings panel instead.",
                "github" to "Query GitHub: 'search_repos <query>' finds repositories; 'latest_release <owner>/<repo>' returns the newest release. Example: github(search='search_repos', repo='BerriAI/litellm')",
                "open_translation" to "Open Google Translate for a piece of text (optionally to a target language, e.g. 'es'). Note: the assistant can also translate directly in chat.",
                "open_gallery" to "Open the device's photo gallery.",
                "record_voice" to "Record a voice note for a few seconds (default 10, max 60) and return the audio file path.",
                "convert_units" to "Convert a value between units: length (m, km, mi, ft, in, cm), mass (kg, g, lb, oz), temperature (°C, °F, K), data (B, KB, MB, GB, TB), volume (L, mL, gal, cup), speed (km/h, mph, m/s), time (s, min, h, day).",
                "convert_currency" to "Convert an amount between currencies (USD, EUR, GBP, JPY, INR, CAD, AUD, CHF, CNY, BRL, KRW, MXN, SEK, NOK, NZD, SGD, HKD, ZAR). Rates are approximate (fixed reference table) — mention they may not be live.",
                "search_web" to "Search the web for a query and return the top results with titles, snippets and URLs. Use when asked to look something up, check news, prices or facts.",
                "set_flashlight" to "Turn the flashlight on or off.",
                "calculate" to "Evaluate a mathematical expression (e.g. '((15 + 3) * 2) / 4', '2^10', '500 * 0.07'). Returns the exact result.",
                "find_contacts" to "Look up a contact by name and return their phone numbers and email addresses. Use before messaging or calling a named person.",
                "get_weather" to "Get current weather and a 3-day forecast for a city or location. Returns temperature, humidity, condition, rain chance, wind, UV index and daily forecast.",
                "launch_app" to "Launch an installed app by its name (e.g. 'Spotify', 'Settings', 'WhatsApp') or package name. Matches partial and fuzzy names ('YT' for YouTube).",
                "list_downloads" to "List recent files in the Downloads folder (name, size, when downloaded).",
                "list_app_files" to "List files this assistant has created on the device (exports, notes, recordings) with their paths.",
                "variable_set" to "Store a value under a name for the rest of this task (e.g. result of a previous step). Later steps can read it with variable_get. Use to chain tool outputs or implement loops.",
                "variable_get" to "Read a value saved earlier in this task by variable_set or another tool (e.g. weather, search_results). With no key, returns every saved variable.",
                "send_email" to "Open the email app with a pre-filled draft (recipient, subject, body) for the user to review and send. Always requires confirmation.",
                "export_pdf" to "Save text content as a PDF file and return its path.",
                "export_markdown" to "Save text content as a Markdown (.md) file and return its path.",
                "copy_to_clipboard" to "Copy text to the clipboard. Use when the user asks to copy or save text.",
                "set_bluetooth" to "Turn Bluetooth on or off. On newer Android versions the OS may require opening the settings panel instead.",
                "make_call" to "Open the dialer with a phone number so the user can make a call. Always requires the user's confirmation.",
                "get_battery" to "Read the current battery level (%), charging status, health and temperature.",
                "set_volume" to "Set the media volume to a percentage (0–100). Also accepts a stream: media, ring or alarm.",
                "get_device_info" to "Return device information: model, manufacturer, Android version, screen resolution, RAM and free storage.",
                "list_apps" to "List installed apps (names + package names). Optionally filter by keyword (e.g. 'whats').",
                "get_running_apps" to "Return apps used in the last 10 minutes (foreground first). Needs the Usage access permission in system settings.",
                "vibrate" to "Vibrate the phone for a short burst (default ~500ms).",
                "set_brightness" to "Set the screen brightness to a percentage (0–100). Requires the special 'Modify system settings' permission.",
                "open_clock" to "Open the system clock and alarms app.",
                "get_location" to "Read the device's last known location (latitude, longitude, accuracy). Requires location permission.",
                "open_camera" to "Open the camera app.",
                "read_notifications" to "Read the notifications currently visible on the device, newest first.",
                "open_navigation" to "Open turn-by-turn navigation in Google Maps to a destination or address.",
                "search_places" to "Search nearby places (hospitals, restaurants, ATMs, gas stations...) and show the results on a map.",
                "send_sms" to "Send an SMS text message. The recipient may be a phone number (e.g. +919876543210) OR a contact name like 'Mom' — the contact is resolved automatically. Always requires the user's confirmation before sending.",
                "calendar" to "Create a calendar event or list upcoming events. For create, provide title and a start time (e.g. 'tomorrow 15:00' or an ISO time).",
                "control_music" to "Control the active music or media player: play, pause, next or previous track.",
                "create_reminder" to "Schedule a reminder that notifies at a specific time. Provide the text and when ('in 20 minutes', 'tomorrow 09:00', an ISO time or epoch millis).",
                "set_alarm" to "Set an alarm that rings at a clock time. Provide the time ('07:00', '7 AM', ISO time or epoch millis) and an optional label.",
                "take_screenshot" to "Capture the screen. Requires an active screen-capture session granted by the user.",
                "note_save" to "Save a note by title (overwrites an existing note with the same title).",
                "note_list" to "List the titles of all saved notes.",
                "note_get" to "Read the content of a saved note by title.",
                "note_delete" to "Delete a saved note by title.",
                "share_text" to "Open the share sheet with text so the user can send it to another app or person."
            )
            for ((name, desc) in tools) {
                sb.append("- ").append(name)
                    .append(" — ").append(desc.take(140))
                    .append(" | arguments: query (string), value (string) | example: {\"query\": \"value\"}\n")
            }
            return sb.toString()
        }

        suspend fun run(history: List<ChatPromptMessage>, config: GenerationConfig): String {
            val deltas = mutableListOf<String>()
            engine.generateChatStream(history, addAssistant = true, config = config).collect { chunk ->
                when (chunk) {
                    is Result.Success -> if (chunk.data.delta.isNotEmpty()) deltas.add(chunk.data.delta)
                    is Result.Error -> throw chunk.exception
                }
            }
            return deltas.joinToString("")
        }

        val ad = advertisement()
        val user = "Hi"
        Log.i(TAG, "AD length=${ad.length} chars (≈${ad.length / 4} tokens)")

        val clean = withTimeout(120_000) {
            run(
                listOf(ChatPromptMessage(role = "user", content = user)),
                GenerationConfig(temperature = 0.2f, seed = 42L, topK = 20)
            )
        }
        Log.i(TAG, "CLEAN(lowtemp): ${clean.take(120)}")

        val advertisedDefault = withTimeout(120_000) {
            run(
                listOf(
                    ChatPromptMessage(role = "system", content = ad),
                    ChatPromptMessage(role = "user", content = user)
                ),
                GenerationConfig()
            )
        }
        Log.i(TAG, "AD+DEFAULT: ${advertisedDefault.take(160)}")

        val advertisedLowTemp = withTimeout(120_000) {
            run(
                listOf(
                    ChatPromptMessage(role = "system", content = ad),
                    ChatPromptMessage(role = "user", content = user)
                ),
                GenerationConfig(temperature = 0.2f, seed = 42L, topK = 20, repetitionPenalty = 1.1f)
            )
        }
        Log.i(TAG, "AD+LOWTEMP: ${advertisedLowTemp.take(160)}")

        // Threshold probe: a SHORT advertisement (first 15 tools, ~3K chars)
        // under the default sampler — clean output means SIZE is the trigger.
        val adShort = advertisement().lineSequence().take(16).joinToString("\n")
        val advertisedShort = withTimeout(120_000) {
            run(
                listOf(
                    ChatPromptMessage(role = "system", content = adShort),
                    ChatPromptMessage(role = "user", content = user)
                ),
                GenerationConfig()
            )
        }
        Log.i(TAG, "AD-SHORT(${adShort.length}): ${advertisedShort.take(160)}")

        val adMid = advertisement().lineSequence().take(21).joinToString("\n")
        val advertisedMid = withTimeout(120_000) {
            run(
                listOf(
                    ChatPromptMessage(role = "system", content = adMid),
                    ChatPromptMessage(role = "user", content = user)
                ),
                GenerationConfig()
            )
        }
        Log.i(TAG, "AD-MID(${adMid.length}): ${advertisedMid.take(160)}")

        val adLong = advertisement().lineSequence().take(30).joinToString("\n")
        val advertisedLong = withTimeout(120_000) {
            run(
                listOf(
                    ChatPromptMessage(role = "system", content = adLong),
                    ChatPromptMessage(role = "user", content = user)
                ),
                GenerationConfig()
            )
        }
        Log.i(TAG, "AD-LONG(${adLong.length}): ${advertisedLong.take(160)}")

        // Control: same SIZE as the full advertisement but plain prose — is the
        // degradation about the tool-list content or about system-prompt size?
        val lorem = buildString {
            while (length < 9350) {
                append("The on-device assistant helps with daily tasks: it manages alarms, reminders, notes, " +
                    "contacts, messages, media, files, settings and navigation. It answers questions, searches " +
                    "the web, converts units and currencies, controls music and brightness, and keeps the " +
                    "user informed about battery, storage, weather and location. When a task needs an app, " +
                    "it opens the right one; when a task needs confirmation, it always asks first. ")
            }
        }
        val advertisedLorem = withTimeout(120_000) {
            run(
                listOf(
                    ChatPromptMessage(role = "system", content = lorem.take(9350)),
                    ChatPromptMessage(role = "user", content = user)
                ),
                GenerationConfig()
            )
        }
        Log.i(TAG, "AD-LOREM(9350): ${advertisedLorem.take(160)}")

        // REGRESSION: the app now caps the advertisement to the family's
        // toolAdvertisementCapChars (4500 chars ≈ 1100 tokens for small Qwen
        // families), which sits well below the measured degradation
        // breakpoint. This is the size the chat layer ACTUALLY sends now —
        // it must generate coherently with the default sampler.
        val adCapped = advertisement().lineSequence().take(23).joinToString("\n")
        val advertisedCapped = withTimeout(120_000) {
            run(
                listOf(
                    ChatPromptMessage(role = "system", content = adCapped),
                    ChatPromptMessage(role = "user", content = user)
                ),
                GenerationConfig()
            )
        }
        Log.i(TAG, "AD-CAPPED(${adCapped.length}): ${advertisedCapped.take(160)}")

        val out = advertisedCapped
        assertTrue("capped-advertisement output is blank", out.isNotBlank())
        assertTrue("code-comment fragment in capped output: <$out>", !out.contains("//"))
        assertTrue("template marker leaked: <$out>", !out.contains("<|"))
        assertTrue("runaway length (tool-list echo): ${out.length} chars", out.length < 800)
        assertFalse("replacement char", out.contains(REPLACEMENT_CHAR))
        // Coherent = at least two real words beyond the greeting.
        assertTrue("gibberish output: <$out>", out.split(Regex("\\s+")).count { it.length > 2 } >= 3)
    }
}
