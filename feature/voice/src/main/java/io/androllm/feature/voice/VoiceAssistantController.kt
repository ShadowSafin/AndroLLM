package io.androllm.feature.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val onnxStatus: String = "Idle"
)

/**
 * Single source of truth for everything the voice assistant shows.
 */
@Singleton
class VoiceAssistantController @Inject constructor() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

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
            error = null
        )
    }

    /** Brings state back to a clean snapshot. */
    fun resetAll() {
        _state.value = VoiceUiState(active = _state.value.active)
    }
}
