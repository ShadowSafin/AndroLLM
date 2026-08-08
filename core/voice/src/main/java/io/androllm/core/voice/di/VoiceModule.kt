package io.androllm.core.voice.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.core.voice.asr.SherpaRecognizer
import io.androllm.core.voice.asr.SpeechRecognizer
import io.androllm.core.voice.tts.OfflineTtsEngine
import io.androllm.core.voice.tts.PiperSpeechSynthesizer
import io.androllm.core.voice.tts.SpeechSynthesizer
import io.androllm.core.voice.vad.SherpaVad
import io.androllm.core.voice.vad.VoiceActivityDetector
import io.androllm.core.voice.wakeword.OpenWakeWordEngine
import io.androllm.core.voice.wakeword.WakeWordEngine
import javax.inject.Singleton

/**
 * Hilt bindings for the 100% offline voice-assistant engines.
 *
 * Wake-word detection (OpenWakeWord), streaming speech recognition (Sherpa-ONNX ASR),
 * voice activity detection (Sherpa VAD), and text-to-speech (Piper TTS) are all
 * powered by ONNX runtime mobile.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    @Singleton
    abstract fun bindWakeWordEngine(impl: OpenWakeWordEngine): WakeWordEngine

    @Binds
    @Singleton
    abstract fun bindSpeechRecognizer(impl: SherpaRecognizer): SpeechRecognizer

    @Binds
    @Singleton
    abstract fun bindOfflineTtsEngine(impl: PiperSpeechSynthesizer): OfflineTtsEngine

    @Binds
    @Singleton
    abstract fun bindSpeechSynthesizer(impl: PiperSpeechSynthesizer): SpeechSynthesizer

    @Binds
    @Singleton
    abstract fun bindVoiceActivityDetector(impl: SherpaVad): VoiceActivityDetector
}