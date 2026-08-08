package io.androllm.core.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.voice.model.VoiceSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voice_settings",
    // The process can be killed mid-write (e.g. by the OS while the assistant
    // holds the microphone + models); a corrupt prefs file must reset to
    // defaults instead of crashing the settings screen with a
    // CorruptionException on every open.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

private const val PHRASE_SEPARATOR = "|"

/**
 * Singleton wrapper around the voice-assistant preferences DataStore.
 */
@Singleton
class VoiceSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val ENABLE_WAKE_WORD = booleanPreferencesKey("enable_wake_word")
        val WAKE_PHRASES = stringPreferencesKey("wake_phrases")
        val SENSITIVITY = floatPreferencesKey("sensitivity")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val SILENCE_TIMEOUT = intPreferencesKey("silence_timeout_ms")
        val LANGUAGE = stringPreferencesKey("language")
        val AUTO_LANG_DETECT = booleanPreferencesKey("auto_language_detection")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val SPEAKING_SPEED = floatPreferencesKey("speaking_speed")
        val PITCH = floatPreferencesKey("pitch")
        val VOLUME = floatPreferencesKey("volume")
        val OFFLINE_ONLY = booleanPreferencesKey("offline_only")
        val CONTINUOUS = booleanPreferencesKey("continuous_conversation")
        val AUTO_READ = booleanPreferencesKey("auto_read_answers")
        val CLOUD_FALLBACK = booleanPreferencesKey("cloud_fallback")
        val LOW_LATENCY = booleanPreferencesKey("low_latency_mode")
        val NOISE_SUPPRESSION = booleanPreferencesKey("noise_suppression")
        val ECHO_CANCELLATION = booleanPreferencesKey("echo_cancellation")
    }

    private val dataStore: DataStore<Preferences> = context.voiceDataStore

    val settings: Flow<VoiceSettings> = dataStore.data.map(::fromPreferences)

    suspend fun current(): VoiceSettings = settings.first()

    /**
     * Reads and writes inside a single [edit] transaction, so concurrent
     * updates (e.g. rapid slider drags, each launching its own coroutine) can
     * never lose a change to a read-before-write race.
     */
    suspend fun update(transform: (VoiceSettings) -> VoiceSettings) {
        dataStore.edit { prefs ->
            writeTo(prefs, transform(fromPreferences(prefs)))
        }
    }

    private fun fromPreferences(p: Preferences): VoiceSettings = VoiceSettings(
        enabled = p[Keys.ENABLED] ?: false,
        enableWakeWord = p[Keys.ENABLE_WAKE_WORD] ?: true,
        wakePhrases = wakePhrasesFrom(p)
            .split(PHRASE_SEPARATOR)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .ifEmpty { VoiceSettings().wakePhrases },
        sensitivity = (p[Keys.SENSITIVITY] ?: VoiceSettings().sensitivity)
            .coerceIn(VoiceSettings.MIN_SENSITIVITY, VoiceSettings.MAX_SENSITIVITY),
        batterySaver = p[Keys.BATTERY_SAVER] ?: false,
        chargingOnly = p[Keys.CHARGING_ONLY] ?: false,
        silenceTimeoutMs = (p[Keys.SILENCE_TIMEOUT] ?: VoiceSettings().silenceTimeoutMs)
            .coerceIn(VoiceSettings.MIN_SILENCE_TIMEOUT_MS, VoiceSettings.MAX_SILENCE_TIMEOUT_MS),
        language = p[Keys.LANGUAGE] ?: "en",
        autoLanguageDetection = p[Keys.AUTO_LANG_DETECT] ?: true,
        ttsVoice = p[Keys.TTS_VOICE] ?: "Kore",
        speakingSpeed = (p[Keys.SPEAKING_SPEED] ?: VoiceSettings().speakingSpeed)
            .coerceIn(VoiceSettings.MIN_SPEED, VoiceSettings.MAX_SPEED),
        pitch = (p[Keys.PITCH] ?: VoiceSettings().pitch)
            .coerceIn(VoiceSettings.MIN_PITCH, VoiceSettings.MAX_PITCH),
        volume = (p[Keys.VOLUME] ?: VoiceSettings().volume)
            .coerceIn(VoiceSettings.MIN_VOLUME, VoiceSettings.MAX_VOLUME),
        offlineOnly = p[Keys.OFFLINE_ONLY] ?: false,
        continuousConversation = p[Keys.CONTINUOUS] ?: false,
        autoReadAnswers = p[Keys.AUTO_READ] ?: true,
        cloudFallback = p[Keys.CLOUD_FALLBACK] ?: true,
        lowLatencyMode = p[Keys.LOW_LATENCY] ?: false,
        noiseSuppression = p[Keys.NOISE_SUPPRESSION] ?: true,
        echoCancellation = p[Keys.ECHO_CANCELLATION] ?: true
    )

    private fun wakePhrasesFrom(p: Preferences): String {
        val raw = p.asMap()[Keys.WAKE_PHRASES]
        return when (raw) {
            // Legacy builds persisted wake phrases as a string set; reading
            // that through the String key used to throw a ClassCastException
            // that crashed the settings screen on every open.
            is Set<*> -> raw.joinToString(PHRASE_SEPARATOR)
            else -> raw as? String ?: ""
        }
    }

    private fun writeTo(p: MutablePreferences, s: VoiceSettings) {
        p[Keys.ENABLED] = s.enabled
        p[Keys.ENABLE_WAKE_WORD] = s.enableWakeWord
        p[Keys.WAKE_PHRASES] = s.wakePhrases
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .joinToString(PHRASE_SEPARATOR)
        p[Keys.SENSITIVITY] = s.sensitivity
        p[Keys.BATTERY_SAVER] = s.batterySaver
        p[Keys.CHARGING_ONLY] = s.chargingOnly
        p[Keys.SILENCE_TIMEOUT] = s.silenceTimeoutMs
        p[Keys.LANGUAGE] = s.language
        p[Keys.AUTO_LANG_DETECT] = s.autoLanguageDetection
        p[Keys.TTS_VOICE] = s.ttsVoice
        p[Keys.SPEAKING_SPEED] = s.speakingSpeed
        p[Keys.PITCH] = s.pitch
        p[Keys.VOLUME] = s.volume
        p[Keys.OFFLINE_ONLY] = s.offlineOnly
        p[Keys.CONTINUOUS] = s.continuousConversation
        p[Keys.AUTO_READ] = s.autoReadAnswers
        p[Keys.CLOUD_FALLBACK] = s.cloudFallback
        p[Keys.LOW_LATENCY] = s.lowLatencyMode
        p[Keys.NOISE_SUPPRESSION] = s.noiseSuppression
        p[Keys.ECHO_CANCELLATION] = s.echoCancellation
    }
}
