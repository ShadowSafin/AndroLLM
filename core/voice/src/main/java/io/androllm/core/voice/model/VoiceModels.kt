package io.androllm.core.voice.model

/**
 * Asset paths of the bundled sherpa-onnx models.
 *
 * The models live under `app/src/main/assets/voice/` and are packaged into the
 * APK (see the `downloadVoiceModels` Gradle task in app/build.gradle.kts).
 * Keeping all references here means the voice layer never hard-codes a path.
 */
object VoiceModels {

    /** Keyword spotting — sherpa-onnx-kws-zipformer-gigaspeech-3.3M (mobile/int8). */
    const val KWS_ENCODER = "voice/kws/encoder.onnx"
    const val KWS_DECODER = "voice/kws/decoder.onnx"
    const val KWS_JOINER = "voice/kws/joiner.onnx"
    const val KWS_TOKENS = "voice/kws/tokens.txt"
    const val KWS_KEYWORDS = "voice/kws/keywords.txt"

    /** Streaming ASR — sherpa-onnx-streaming-zipformer-en-20M (int8). */
    const val ASR_ENCODER = "voice/asr/encoder.onnx"
    const val ASR_DECODER = "voice/asr/decoder.onnx"
    const val ASR_JOINER = "voice/asr/joiner.onnx"
    const val ASR_TOKENS = "voice/asr/tokens.txt"

    /** Offline TTS — sherpa-onnx-vits-ljs (English, single speaker). */
    const val TTS_MODEL = "voice/tts/model.onnx"
    const val TTS_TOKENS = "voice/tts/tokens.txt"
    const val TTS_LEXICON = "voice/tts/lexicon.txt"

    const val SAMPLE_RATE = 16000
    const val FEATURE_DIM = 80
}
