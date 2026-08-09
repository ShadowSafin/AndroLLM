package io.androllm.core.tools.trace

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One executed tool call with its turn context. This is exactly what the
 * Tool Debug screen renders: prompt → tool → arguments → status → result →
 * error → timing → final LLM output.
 */
data class ToolExecutionTrace(
    val id: String,
    val at: Long,
    /** The user prompt of the turn that triggered this call. */
    val prompt: String? = null,
    val toolName: String,
    /** Rendered arguments, e.g. `app=Discord`. */
    val arguments: String = "",
    /** "ok" | "failed" | "blocked" */
    val status: String,
    /** The [io.androllm.core.tools.api.ToolResult.summary] fed back to the LLM. */
    val result: String = "",
    val error: String? = null,
    val durationMs: Long = 0,
    /** Final assistant text of the turn, attached on completion. */
    val llmOutput: String? = null
)

/**
 * Bounded, in-memory log of every tool execution across chat and voice.
 * The chat layers call [beginTurn]/[endTurn] so each trace carries its user
 * prompt and the final LLM output; the executor records each call. Never
 * grows beyond [MAX_ENTRIES].
 */
@Singleton
class ToolExecutionTraceStore @Inject constructor() {

    private val MAX_ENTRIES = 200
    private val lock = Any()
    private var counter = 0L

    private val _traces = MutableStateFlow<List<ToolExecutionTrace>>(emptyList())
    val traces: StateFlow<List<ToolExecutionTrace>> = _traces.asStateFlow()

    @Volatile private var currentPrompt: String? = null
    private val inFlightIds = mutableListOf<String>()

    /** Call at the start of a chat turn so tool traces get the prompt. */
    fun beginTurn(prompt: String?) {
        synchronized(lock) {
            currentPrompt = prompt?.take(240)
            inFlightIds.clear()
        }
    }

    /** Call when the turn's assistant text is finalised — attaches it. */
    fun endTurn(llmOutput: String?) {
        synchronized(lock) {
            val ids = inFlightIds.toList()
            val output = llmOutput?.take(600)
            currentPrompt = null
            inFlightIds.clear()
            if (ids.isEmpty() || output == null) return
            _traces.value = _traces.value.map { trace ->
                if (trace.id in ids) trace.copy(llmOutput = output) else trace
            }
        }
    }

    /** Records one executed tool call (newest first). */
    fun record(
        toolName: String,
        arguments: String,
        status: String,
        result: String,
        error: String? = null,
        durationMs: Long = 0
    ) {
        synchronized(lock) {
            val trace = ToolExecutionTrace(
                id = "${System.currentTimeMillis()}_${counter++}",
                at = System.currentTimeMillis(),
                prompt = currentPrompt,
                toolName = toolName,
                arguments = arguments.take(300),
                status = status,
                result = result.take(400),
                error = error?.take(200),
                durationMs = durationMs
            )
            inFlightIds += trace.id
            _traces.value = (listOf(trace) + _traces.value).take(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) {
            _traces.value = emptyList()
            inFlightIds.clear()
            currentPrompt = null
        }
    }

    /**
     * Never-blank reply builder (STEP 8/12): grounds a reply in the most
     * recent tool execution. Shared by the chat and voice layers so the
     * fallback format lives in exactly one place.
     */
    fun lastTurnSummary(): String {
        val last = _traces.value.firstOrNull()
            ?: return "I've completed the action you asked for."
        val headline = if (last.status == "ok") "Done" else "I ran into a problem"
        val detail = last.result.ifBlank { last.toolName }.take(280)
        return "$headline — $detail"
    }
}
