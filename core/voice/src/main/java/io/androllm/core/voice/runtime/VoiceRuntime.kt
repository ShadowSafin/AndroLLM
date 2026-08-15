package io.androllm.core.voice.runtime

import io.androllm.core.runtime.Runtime
import io.androllm.core.runtime.RuntimeCategory
import io.androllm.core.runtime.RuntimeStatus
import io.androllm.core.voice.VoiceSettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the voice assistant runtime (wake word → ASR → TTS, fully
 * offline) into the central [io.androllm.core.runtime.RuntimeRegistry].
 * Mirrors the persisted [VoiceSettingsStore]; it never starts or stops any
 * voice engine.
 */
@Singleton
class VoiceRuntime @Inject constructor(
    private val settingsStore: VoiceSettingsStore
) : Runtime {

    override val id = "voice"
    override val displayName = "Voice Assistant"
    override val category = RuntimeCategory.VOICE
    override val description = "Fully offline voice pipeline: wake word (sherpa-onnx KWS) → ASR (whisper.cpp) → TTS (Piper)."

    override suspend fun status(): RuntimeStatus = runCatching {
        val settings = settingsStore.current()
        if (settings.enabled) {
            RuntimeStatus(
                true,
                "Enabled — ${if (settings.enableWakeWord) "wake word on" else "wake word off"}, " +
                    "${settings.sttEngine} STT, ${settings.ttsVoice} TTS"
            )
        } else {
            RuntimeStatus(
                available = false,
                summary = "Disabled in Settings",
                detail = "Enable Voice Assistant in Settings → Voice to use wake word, STT and TTS."
            )
        }
    }.getOrElse { e ->
        RuntimeStatus(false, "Status check failed", e.message ?: e.javaClass.simpleName)
    }
}
