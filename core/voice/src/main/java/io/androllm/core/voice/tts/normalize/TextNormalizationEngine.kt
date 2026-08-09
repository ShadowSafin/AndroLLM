package io.androllm.core.voice.tts.normalize

import io.androllm.core.voice.model.VoiceSettings
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Facade used by the TTS engine: owns the normalization pipeline and the
 * persisted settings snapshot (refreshed by the voice service whenever
 * settings change), and logs per-stage debug traces when debug mode is on.
 *
 * Performance: all regexes precompiled once, disabled stages skipped
 * entirely — typical sentences stay well under 20 ms. Never on the UI
 * thread (invoked from the TTS thread pool).
 */
@Singleton
class TextNormalizationEngine @Inject constructor() {

    @Volatile
    private var settingsSnapshot: NormalizationSettings = NormalizationSettings.ALL_ENABLED

    private val pipeline = TextNormalizationPipeline(
        stages = TextNormalizationPipeline.defaultStages(),
        settingsProvider = { settingsSnapshot }
    )

    /** Call whenever persisted voice settings change. */
    fun onSettingsChanged(s: VoiceSettings) {
        settingsSnapshot = NormalizationSettings.from(s)
    }

    fun normalize(input: String): NormalizationResult {
        val snapshot = settingsSnapshot
        if (input.isBlank() || !snapshot.enabled) return NormalizationResult(input)
        val start = System.nanoTime()
        val result = pipeline.process(input)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        if (snapshot.debug) {
            for (trace in result.traces) {
                Timber.i("TN [${trace.stageId}]: '${trace.input}' → '${trace.output}'")
            }
            Timber.i("TN done in ${elapsedMs}ms: '${result.text}'")
        }
        return result
    }

    /** Explicit-settings probe (used by tests; leaves the live snapshot alone). */
    fun with(settings: NormalizationSettings, input: String): NormalizationResult =
        TextNormalizationPipeline(
            stages = TextNormalizationPipeline.defaultStages(),
            settingsProvider = { settings }
        ).process(input)
}