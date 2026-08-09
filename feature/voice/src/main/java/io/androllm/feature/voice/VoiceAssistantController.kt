package io.androllm.feature.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine phases for the always-on voice assistant loop.
 */
enum class VoicePhase {
    IDLE,
    WAKE,
    LISTEN,
    LISTENING,
    RECEIVING_AUDIO,
    RUNNING_INFERENCE,
    WAKE_DETECTED,
    STARTING_STT,
    THINK,
    GENERATING,
    SPEAK,
    SPEAKING,
    DONE
}

/**
 * UI & Real-Time Debugging snapshot of the voice assistant.
 */
data class VoiceUiState(
    val active: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE,
    val wakeWord: String? = null,
    val transcript: String = "",
    val partialTranscript: String = "",
    val answerText: String = "",
    val error: String? = null,

    // Real-Time Debugging Metrics
    val micRms: Float = 0f,
    val maxAmplitude: Float = 0f,
    val confidenceScore: Float = 0f,
    val threshold: Float = 0.25f,
    val framesReceived: Long = 0L,
    val inferenceFps: Float = 0f,
    val micOwner: String = "None",
    val onnxStatus: String = "Idle",

    // ── Gemini Live-style overlay ──────────────────────────────────────────
    /** Human-readable active model label (e.g. "gpt-4o" / "llama-3.1-8b"). */
    val modelName: String = "",
    /** Provider label (e.g. "Local GGUF", "Gemini", "OpenAI", "OpenRouter"). */
    val modelProvider: String = "",
    /** TTS output muted (overlay mute button). */
    val muted: Boolean = false,
    /** Elapsed ms of the current thinking/generating turn (timer chip). */
    val elapsedMs: Long = 0L,
    /** True while a wake→answer turn is in flight (drives overlay visibility). */
    val turnActive: Boolean = false,
    /** User dismissed the overlay for this turn; don't re-show until next wake. */
    val overlayDismissed: Boolean = false,

    // ── Word-level karaoke highlighting (sentence currently being spoken) ──
    /** Start char offset (inclusive) of the spoken chunk inside [answerText]. */
    val spokenStart: Int = -1,
    /** End char offset (exclusive) of the spoken chunk inside [answerText]. */
    val spokenEnd: Int = -1,
    /** Index of the word currently being spoken within the chunk (-1 = none). */
    val spokenWordIndex: Int = -1
)

/**
 * Buttons pressed on the floating overlay. The overlay never touches the
 * service directly — it emits an intent here and the service reacts.
 */
sealed interface VoiceOverlayEvent {
    /** Cancel the in-flight generation + stop TTS. */
    data object Cancel : VoiceOverlayEvent

    /** Toggle spoken answers on/off for this session. */
    data object ToggleMute : VoiceOverlayEvent

    /** Open the normal chat screen (keyboard button). */
    data object OpenChat : VoiceOverlayEvent

    /** Open the conversation that holds this turn's transcript + answer. */
    data object OpenConversation : VoiceOverlayEvent

    /** Close the overlay; the assistant keeps listening in the background. */
    data object Close : VoiceOverlayEvent
}

/**
 * Single source of truth for everything the voice assistant shows.
 */
@Singleton
class VoiceAssistantController @Inject constructor() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Overlay → service intents (buffered, drop-oldest so the UI never blocks). */
    private val _overlayEvents = MutableSharedFlow<VoiceOverlayEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val overlayEvents: SharedFlow<VoiceOverlayEvent> = _overlayEvents.asSharedFlow()

    fun emitOverlayEvent(event: VoiceOverlayEvent) {
        _overlayEvents.tryEmit(event)
    }

    fun setActive(active: Boolean) {
        _state.value = _state.value.copy(active = active, error = null)
    }

    fun setPhase(phase: VoicePhase) {
        _state.value = _state.value.copy(phase = phase)
    }

    fun setWakeWord(word: String) {
        _state.value = _state.value.copy(wakeWord = word)
    }

    fun setTranscript(text: String) {
        _state.value = _state.value.copy(transcript = text)
    }

    fun setPartialTranscript(text: String) {
        _state.value = _state.value.copy(partialTranscript = text)
    }

    fun setAnswer(text: String) {
        _state.value = _state.value.copy(answerText = text)
    }

    fun setError(message: String?) {
        _state.value = _state.value.copy(error = message)
    }

    fun setModelInfo(provider: String, name: String) {
        _state.value = _state.value.copy(modelProvider = provider, modelName = name)
    }

    fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(muted = muted)
    }

    fun setElapsedMs(ms: Long) {
        _state.value = _state.value.copy(elapsedMs = ms)
    }

    /**
     * Marks the answer range currently being spoken (for word highlighting).
     * Also resets the word cursor so a new chunk never renders with the
     * previous chunk's stale word index.
     */
    fun setSpokenRange(start: Int, end: Int) {
        _state.value = _state.value.copy(spokenStart = start, spokenEnd = end, spokenWordIndex = 0)
    }

    /** Advances the karaoke cursor to [index] within the spoken chunk. */
    fun setSpokenWordIndex(index: Int) {
        _state.value = _state.value.copy(spokenWordIndex = index)
    }

    /** Clears the spoken-chunk highlight (turn ended / interrupted). */
    fun clearSpoken() {
        _state.value = _state.value.copy(spokenStart = -1, spokenEnd = -1, spokenWordIndex = -1)
    }

    /**
     * Marks a wake→answer turn as started/finished. Entering a turn resets the
     * dismissed flag so the overlay can re-open on the next wake word.
     */
    fun setTurnActive(active: Boolean) {
        _state.value = _state.value.copy(
            turnActive = active,
            overlayDismissed = if (active) false else _state.value.overlayDismissed
        )
    }

    /** User closed the overlay for this turn. */
    fun dismissOverlay() {
        _state.value = _state.value.copy(overlayDismissed = true)
    }

    fun updateDebugMetrics(
        micRms: Float = _state.value.micRms,
        maxAmplitude: Float = _state.value.maxAmplitude,
        confidenceScore: Float = _state.value.confidenceScore,
        threshold: Float = _state.value.threshold,
        framesReceived: Long = _state.value.framesReceived,
        inferenceFps: Float = _state.value.inferenceFps,
        micOwner: String = _state.value.micOwner,
        onnxStatus: String = _state.value.onnxStatus
    ) {
        _state.value = _state.value.copy(
            micRms = micRms,
            maxAmplitude = maxAmplitude,
            confidenceScore = confidenceScore,
            threshold = threshold,
            framesReceived = framesReceived,
            inferenceFps = inferenceFps,
            micOwner = micOwner,
            onnxStatus = onnxStatus
        )
    }

    /** Clears everything from the previous turn. */
    fun resetTurn() {
        _state.value = _state.value.copy(
            wakeWord = null,
            transcript = "",
            partialTranscript = "",
            answerText = "",
            error = null,
            elapsedMs = 0L,
            spokenStart = -1,
            spokenEnd = -1,
            spokenWordIndex = -1
        )
    }

    /** Brings state back to a clean snapshot. */
    fun resetAll() {
        _state.value = VoiceUiState(active = _state.value.active)
    }
}
