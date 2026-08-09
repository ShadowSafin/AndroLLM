package io.androllm.core.voice.tts.normalize

import io.androllm.core.voice.model.VoiceSettings

/**
 * Per-stage toggles for the text-normalization pipeline (mirrored 1:1 into
 * the Settings UI). All stages default to enabled.
 */
data class NormalizationSettings(
    val enabled: Boolean = true,
    val numbers: Boolean = true,
    val dates: Boolean = true,
    val currency: Boolean = true,
    val units: Boolean = true,
    val math: Boolean = true,
    val emoji: Boolean = true,
    val urlsEmails: Boolean = true,
    val phones: Boolean = true,
    val abbreviations: Boolean = true,
    val debug: Boolean = false
) {
    companion object {
        val ALL_ENABLED = NormalizationSettings()

        /** Snapshot from persisted voice settings. */
        fun from(s: VoiceSettings): NormalizationSettings = NormalizationSettings(
            enabled = s.tnEnabled,
            numbers = s.tnNumbers,
            dates = s.tnDates,
            currency = s.tnCurrency,
            units = s.tnUnits,
            math = s.tnMath,
            emoji = s.tnEmoji,
            urlsEmails = s.tnUrlsEmails,
            phones = s.tnPhones,
            abbreviations = s.tnAbbreviations,
            debug = s.tnDebug
        )
    }
}