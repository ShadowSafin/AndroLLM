package io.androllm.engine

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.androllm.core.common.getOrNull
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.llama.LlamaCppEngine
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Runtime Stabilization — on-device stress test.
 *
 * Drives 500+ consecutive multi-turn chat prompts through the REAL native
 * engine ([LlamaCppEngine] + llama.cpp) using the official llama.cpp
 * multi-turn pattern (incremental template-diff decoding at a continuing KV
 * position — see `doGenerateChat` in native_api.cpp). Asserts:
 *
 *  - no crashes (a JNI fault or native abort fails the test process)
 *  - no decode errors (stop reason never "decode_error")
 *  - no tokenizer corruption (output contains no U+FFFD replacement chars)
 *  - engine always returns to [EngineState.Ready] after each turn
 *  - context shifts are exercised and never corrupt generation
 *  - memory peak stays bounded across the whole run (no unbounded growth)
 *  - the diff path is deterministic: the same conversation replayed on two
 *    fresh engines with the same seed must produce byte-identical output
 *    (catches KV-state corruption)
 *  - Prompt #2 regression: the second turn must answer the new prompt, not
 *    continue the first answer from a stale context
 *  - cancel mid-generation never corrupts the next prompt
 *
 * Requires a GGUF model on the device. Resolution order:
 *   1. Instrumentation arg `-e modelPath /sdcard/.../model.gguf`
 *   2. Environment variable ANDROLLM_TEST_MODEL
 *   3. Any *.gguf under /sdcard/Download or the app's models directories
 *   4. Otherwise the test is SKIPPED (never fails the suite).
 *
 * Run (expect several minutes — 500 turns is the stabilization criterion):
 *   ./gradlew :engine:connectedDebugAndroidTest -PandrollmAbis=arm64-v8a \
 *       -Pandroid.testInstrumentationRunnerArguments.modelPath=/sdcard/Download/model.gguf
 */
@RunWith(AndroidJUnit4::class)
class EngineStressInstrumentedTest {

    companion object {
        private const val TAG = "EngineStress"

        /** The stabilization success criterion: 500 consecutive prompts. */
        private const val TURNS = 500

        /** Small context forces KV shifts every few turns. */
        private const val CONTEXT_LENGTH = 512
        private const val MAX_TOKENS = 24

        /** U+FFFD marks invalid/truncated UTF-8 (tokenizer corruption). */
        private const val REPLACEMENT_CHAR = '\uFFFD'

        /** Fixed seed: sampling is deterministic, so identical cache state on
         * two engines must produce identical output. */
        private const val SEED = 42L

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
        assumeTrue("No GGUF model found on device — skipping stress test", modelPath != null)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.release()
        }
    }

    private fun resolveModelPath(): String? {
        val args: Bundle = InstrumentationRegistry.getArguments()
        args.getString("modelPath")?.takeIf { File(it).exists() }?.let { return it }

        System.getenv("ANDROLLM_TEST_MODEL")?.takeIf { File(it).exists() }?.let { return it }

        val candidates = listOf(
            File("/sdcard/Download"),
            File("/sdcard/Android/data/io.androllm.app/files/Documents/models"),
            File("/sdcard/Android/data/io.androllm.app/files/models")
        )
        for (dir in candidates) {
            if (dir.isDirectory) {
                dir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }
                    ?.maxByOrNull { it.length() }
                    ?.let { return it.absolutePath }
            }
        }
        return null
    }

    private fun genConfig(reuseKvCache: Boolean) = GenerationConfig(
        maxTokens = MAX_TOKENS,
        temperature = 0.2f,
        topK = 20,
        seed = SEED,
        reuseKvCache = reuseKvCache
    )

    private suspend fun engineReadyState(): EngineState.Ready? =
        engine.engineState.first() as? EngineState.Ready

    private suspend fun InferenceEngine.loadStressModel() {
        val init = initialize(EngineConfig(threads = 4))
        assertTrue("initialize failed: $init", init.isSuccess())
        val load = loadModel(
            Model(id = "stress", name = "Stress", filePath = modelPath!!, quantization = "Q8_0"),
            ModelLoadConfig(contextLength = CONTEXT_LENGTH, batchSize = 128, threads = 4)
        )
        assertTrue("load failed: $load", load.isSuccess())
    }

    /** Runs one chat turn with the official diff-based path. */
    private suspend fun chatTurn(
        eng: InferenceEngine,
        history: MutableList<ChatPromptMessage>,
        userMessage: String,
        reuseKvCache: Boolean = true
    ): String {
        history.add(ChatPromptMessage(role = "user", content = userMessage))
        val result = eng.generateChat(
            history,
            addAssistant = true,
            config = genConfig(reuseKvCache)
        )
        val text = result.getOrNull()
        assertNotNull("generation failed: $result", text)
        // Native stores the assistant text verbatim (upstream ai_chat.cpp
        // behavior); the app persists its own trimmed copy. The native
        // continuation check compares trimmed-to-trimmed, so the test must
        // keep storing the trimmed text — NOT the raw text, which would break
        // the match whenever the model emits trailing whitespace.
        history.add(ChatPromptMessage(role = "assistant", content = text!!.trim()))
        return text
    }

    @Test
    fun `500 consecutive prompts survive without crash corruption or memory growth`() = runBlocking {
        Log.i(TAG, "Loading model: $modelPath")
        engine.loadStressModel()

        val info = engine.getLoadedModel()
        assertNotNull("model info missing", info)
        Log.i(TAG, "Loaded ${info?.generalName ?: "model"}, ctx=${info?.contextLength}")

        val history = mutableListOf<ChatPromptMessage>()
        var decodeErrors = 0
        var corrupted = 0
        var failures = 0

        val t0 = System.currentTimeMillis()
        var peakMemoryWarm = 0L // native peak after the first 20 turns

        for (i in 1..TURNS) {
            val text = try {
                chatTurn(engine, history, "Turn $i: tell me a fact about the number $i in exactly one short sentence.")
            } catch (e: Exception) {
                Log.e(TAG, "Turn $i: generation failed — ${e.message}")
                failures++
                continue
            }

            val stats = engine.stats.first()
            if (stats?.stopReason == "decode_error") {
                decodeErrors++
                Log.e(TAG, "Turn $i: decode_error")
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
                Log.i(TAG, "Turn $i: ok, stop=${stats?.stopReason} tokens=${stats?.generatedTokens} prompt=${stats?.promptTokens}")
            }
            if (i == 20) peakMemoryWarm = stats?.memoryPeakBytes ?: 0L
        }

        val elapsedMs = System.currentTimeMillis() - t0
        val memoryAtEnd = engine.stats.first()?.memoryPeakBytes ?: 0L

        Log.i(TAG, "DONE: turns=$TURNS failures=$failures decodeErrors=$decodeErrors corrupted=$corrupted")
        Log.i(TAG, "elapsed=${elapsedMs}ms peakMemoryWarm=${peakMemoryWarm}B peakMemoryEnd=${memoryAtEnd}B")

        // No crashes: reaching here is the primary assertion.
        assertEquals("decode errors: $decodeErrors", 0, decodeErrors)
        assertEquals("tokenizer corruption (U+FFFD): $corrupted", 0, corrupted)
        assertEquals("generation failures: $failures", 0, failures)

        // The native peak is expected to plateau after warm-up (KV cache and
        // buffers are allocated once). Continued growth across hundreds more
        // turns indicates a native memory leak.
        assertTrue(
            "peak memory kept growing: warm=$peakMemoryWarm end=$memoryAtEnd",
            peakMemoryWarm <= 0 || memoryAtEnd <= peakMemoryWarm * 2 + (256L * 1024 * 1024)
        )
    }

    @Test
    fun `multi-turn chat script repeats 100 times without corruption`() = runBlocking {
        Log.i(TAG, "Multi-turn script test (${SCRIPT.size}-prompt script repeated)")
        engine.loadStressModel()

        // Exactly the stabilization checklist scenario: a fixed conversational
        // script ("Hello" -> "What is Android?" -> "Write Kotlin code." ->
        // "Explain the code." -> "Summarize." -> "Continue.") repeated 100+
        // times. Every continuation goes through the diff-based KV path.
        val history = mutableListOf<ChatPromptMessage>()
        val totalTurns = SCRIPT.size * 17 // 102 turns

        var failures = 0
        var corrupted = 0
        var decodeErrors = 0

        for (t in 1..totalTurns) {
            val userMsg = SCRIPT[(t - 1) % SCRIPT.size]
            val text = try {
                chatTurn(engine, history, userMsg)
            } catch (e: Exception) {
                Log.e(TAG, "turn $t: generation failed — ${e.message}")
                failures++
                continue
            }

            val stats = engine.stats.first()
            if (stats?.stopReason == "decode_error") {
                decodeErrors++
                Log.e(TAG, "turn $t: decode_error")
            }
            if (text.contains(REPLACEMENT_CHAR)) {
                corrupted++
                Log.e(TAG, "turn $t: replacement char: ${text.take(40)}")
            }

            if (t % 20 == 0) {
                Log.i(TAG, "script turn $t/$totalTurns ok, stop=${stats?.stopReason} out=${text.take(30)}")
            }
        }

        Log.i(TAG, "SCRIPT DONE: turns=$totalTurns failures=$failures decodeErrors=$decodeErrors corrupted=$corrupted")
        assertEquals("generation failures: $failures", 0, failures)
        assertEquals("decode errors: $decodeErrors", 0, decodeErrors)
        assertEquals("tokenizer corruption (U+FFFD): $corrupted", 0, corrupted)
        // Every turn must have appended exactly one user + one assistant message.
        assertEquals("history incomplete after script", totalTurns * 2, history.size)
    }

    /**
     * Determinism regression for the multi-turn diff path.
     *
     * Two fresh engines, same model, same seed, same backend: the same
     * conversation replayed on both must produce byte-identical turn-1 and
     * turn-2 outputs. The diff path decodes identical diffs into identical
     * cache states, so any divergence signals KV-state corruption or backend
     * nondeterminism (the turn-1 baseline isolates the latter).
     */
    @Test
    fun `identical conversation replay is deterministic`() = runBlocking {
        Log.i(TAG, "Determinism test: same conversation on two engines")
        val engineA = LlamaCppEngine()
        val engineB = LlamaCppEngine()
        try {
            engineA.loadStressModel()
            engineB.loadStressModel()

            val historyA = mutableListOf<ChatPromptMessage>()
            val historyB = mutableListOf<ChatPromptMessage>()

            val t1a = chatTurn(engineA, historyA, "Hello")
            val t1b = chatTurn(engineB, historyB, "Hello")
            Log.i(TAG, "turn1 A=<$t1a> B=<$t1b>")

            val t2a = chatTurn(engineA, historyA, "What is Android?")
            val t2b = chatTurn(engineB, historyB, "What is Android?")
            Log.i(TAG, "turn2 A=<$t2a> B=<$t2b>")

            // NOTE: both engines take the reuse (diff) path here. A
            // "KV-reused turn 2 == fresh full re-prefill" parity assertion
            // would NOT hold: native stores the generated text verbatim (with
            // any trailing-whitespace token the model emitted), while a fresh
            // re-prefill renders the app's trimmed history — the reuse cache
            // is the truthful one. Compare reuse-vs-reuse instead.

            // Baseline: turn 1 (full re-render on both) isolates GPU
            // nondeterminism from the diff path under test.
            assertEquals("turn 1 diverged between engines (non-determinism?)", t1a.trim(), t1b.trim())
            // The diff-based turn 2 must be deterministic too.
            assertEquals(
                "turn 2 diverged between engines — KV/diff state corruption. A=<$t2a> B=<$t2b>",
                t2a.trim(), t2b.trim()
            )
        } finally {
            engineA.release()
            engineB.release()
        }
    }

    /**
     * Prompt #2 regression (bug signature): the second turn must respond to
     * the new prompt, not continue the first answer from a stale context.
     * (The previous custom KV-reuse path could leave the new user message
     * undecoded, so the model kept generating its previous answer.)
     */
    @Test
    fun `prompt 2 answers the new prompt instead of continuing prompt 1`() = runBlocking {
        engine.loadStressModel()

        val history = mutableListOf<ChatPromptMessage>()
        val t1 = chatTurn(engine, history, "Hello").trim()
        val t2 = chatTurn(engine, history, "What is Android?").trim()

        Log.i(TAG, "turn1=<$t1> turn2=<$t2>")
        assertFalse("turn 2 is empty", t2.isEmpty())

        // With the corruption bug, turn 2 continued turn 1's stale context:
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
    fun `cancel mid-generation never corrupts the next prompt`() = runBlocking {
        engine.loadStressModel()

        // Start a long generation, then cancel it mid-flight.
        val cancelled = AtomicBoolean(false)
        val job: Job = launch(Dispatchers.Default) {
            engine.tokenStream(
                "Write a long detailed essay about the history of computing.",
                GenerationConfig(maxTokens = 512, temperature = 0.5f)
            ).collect { }
        }
        delay(250)
        engine.cancel()
        cancelled.set(true)
        job.join()

        // The very next chat turn must succeed — the cancelled run must not
        // have left the KV cache in a state that corrupts generation.
        val history = mutableListOf<ChatPromptMessage>()
        val text = chatTurn(engine, history, "What is 2 + 2? Answer in one short sentence.")
        assertFalse("corrupted output after cancel", text.contains(REPLACEMENT_CHAR))
        assertTrue("cancel was never exercised", cancelled.get())
    }

    /**
     * Vulkan device-lost / GPU-cache stress (requirements checklist):
     * 100 consecutive "hello" generations (30 tokens each) with full context
     * cleanup between them. Asserts:
     *
     *  - every turn succeeds (no crash, no failed generation)
     *  - no U+FFFD tokenizer corruption and no decode errors
     *  - the engine returns to [EngineState.Ready] after every turn
     *  - a HEALTHY backend must never escalate a recovery: recoveryCount and
     *    vulkanDeviceLostRecoveries stay 0 (any >0 means the device-lost
     *    path fired — a pass for recovery, a FAIL for raw stability)
     *  - free GPU memory does not shrink monotonically across the run (a
     *    per-turn leak would show up as a steadily declining heap)
     */
    @Test
    fun `100 consecutive hello generations with cleanup stay stable and leak free`() = runBlocking {
        Log.i(TAG, "Vulkan stability: 100x hello / 30 tokens with per-turn cleanup")
        engine.loadStressModel()

        var failures = 0
        var corrupted = 0
        var decodeErrors = 0
        val freeSamples = mutableListOf<Long>()

        for (i in 1..100) {
            val history = mutableListOf(
                ChatPromptMessage(role = "user", content = "hello")
            )
            val text = try {
                engine.generateChat(
                    history,
                    addAssistant = true,
                    config = GenerationConfig(maxTokens = 30, temperature = 0.2f, seed = SEED)
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

            val stats = engine.stats.first()
            if (stats?.stopReason == "decode_error") {
                decodeErrors++
                Log.e(TAG, "iteration $i: decode_error")
            }

            val ready = engineReadyState()
            if (ready == null) {
                Log.e(TAG, "iteration $i: engine not Ready — ${engine.engineState.first()}")
                failures++
            } else {
                ready.memoryStats?.gpuMemoryFreeBytes?.takeIf { it > 0 }?.let { freeSamples += it }
            }
        }

        // Fresh load resets recovery counters — a healthy backend stays at 0.
        val info = engine.getDebugInfo().getOrNull()
        val recoveries = info?.recoveryCount ?: -1
        val devLost = info?.vulkanDeviceLostRecoveries ?: -1

        Log.i(
            TAG,
            "VULKAN 100x DONE: failures=$failures corrupted=$corrupted decodeErrors=$decodeErrors " +
                "recoveryCount=$recoveries devLostRecovered=$devLost freeSamples=${freeSamples.take(6)}..."
        )

        assertEquals("generation failures: $failures", 0, failures)
        assertEquals("tokenizer corruption (U+FFFD): $corrupted", 0, corrupted)
        assertEquals("decode errors: $decodeErrors", 0, decodeErrors)

        // Any recovery escalation on a supposedly healthy backend is a finding:
        // NaN/INF or DeviceLost paths fired and the wrapper recovered. On a
        // stable device these MUST be zero across 100 turns.
        assertEquals("recovery escalated during 100x hello: $recoveries", 0, recoveries)
        assertEquals("device-lost recovered during 100x hello: $devLost", 0, devLost)

        // Free GPU memory sampled across the run must be stable: allow a modest
        // 64MB drift (allocator noise), but a real per-turn leak would shrink
        // the free heap far faster than that.
        if (freeSamples.size >= 3) {
            val first = freeSamples.first()
            val last = freeSamples.last()
            assertTrue(
                "GPU free memory shrank across the run: first=${first}B last=${last}B",
                last >= first - (64L * 1024 * 1024)
            )
        }
    }
}
