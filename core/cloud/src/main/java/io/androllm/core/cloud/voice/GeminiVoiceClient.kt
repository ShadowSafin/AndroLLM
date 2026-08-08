package io.androllm.core.cloud.voice

import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * Thin Google Gemini client used exclusively by the voice assistant for
 * Speech-to-Text and Text-to-Speech.
 *
 * The chat pipeline is intentionally untouched by this class — the selected
 * provider still handles every reasoning / generation request. Gemini is
 * only ever called here to (a) transcribe the user's microphone audio and
 * (b) synthesize the model's reply into spoken audio.
 *
 * Both endpoints use the public Gemini Developer API
 * (`generativelanguage.googleapis.com`). API keys are read from
 * [SettingsRepository] via the caller and passed in per request so this
 * client never holds credentials.
 */
@Singleton
class GeminiVoiceClient @Inject constructor() {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Live STT/TTS requests are short and bounded; a hard cap keeps a
        // hung network from starving the assistant's main loop.
        .callTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofSeconds(30))
        .build()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Result of a [transcribe] call.
     */
    data class TranscriptResult(val text: String, val confidence: Float = 1.0f)

    /**
     * Result of a [synthesize] call.
     */
    data class SynthesisResult(val samples: FloatArray, val sampleRate: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is SynthesisResult &&
                other.sampleRate == sampleRate &&
                other.samples.contentEquals(samples))

        override fun hashCode(): Int = 31 * sampleRate + samples.contentHashCode()
    }

    /**
     * Transcribes a mono 16 kHz PCM float buffer to text.
     *
     * The audio is encoded as 16-bit signed little-endian PCM and sent to
     * Gemini's multimodal `generateContent` endpoint with a "transcribe this
     * speech" system instruction. Returns the transcript or throws on
     * failure (the caller logs and falls back to silence).
     */
    suspend fun transcribe(
        apiKey: String,
        model: String = DEFAULT_STT_MODEL,
        language: String = "en",
        samples: FloatArray
    ): TranscriptResult = withContext(Dispatchers.IO) {
        require(samples.isNotEmpty()) { "no audio to transcribe" }
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val pcm = floatToPcm16Le(samples)
        val audioBase64 = Base64.encodeToString(pcm, Base64.NO_WRAP)

        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(
                        JsonObject(mapOf("text" to JsonPrimitive(TRANSCRIBE_PROMPT)))
                    )
                })
            })
            put("contents", buildJsonArray {
                add(JsonObject(mapOf(
                    "role" to JsonPrimitive("user"),
                    "parts" to buildJsonArray {
                        add(JsonObject(mapOf(
                            "inline_data" to JsonObject(mapOf(
                                "mime_type" to JsonPrimitive("audio/wav"),
                                "data" to JsonPrimitive(audioBase64)
                            ))
                        )))
                    }
                )))
            })
            put("generationConfig", buildJsonObject {
                put("temperature", JsonPrimitive(0))
                put("maxOutputTokens", JsonPrimitive(256))
            })
        }

        val url = "$BASE_URL/models/$model:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Timber.tag("GeminiVoice").w(
                    "STT %d: %s", response.code, responseBody.take(400)
                )
                throw GeminiVoiceException("STT HTTP ${response.code}: ${responseBody.take(200)}")
            }
            val text = extractText(responseBody) ?: ""
            TranscriptResult(text = text.trim())
        }
    }

    /**
     * Synthesizes [text] into PCM float samples using Gemini TTS.
     *
     * The model returns base64-encoded 24 kHz mono PCM in the response's
     * `inlineData` field; we decode it to a float buffer the audio player can
     * consume directly. The voice assistant calls this once per sentence so
     * the assistant can start speaking while the model is still generating.
     */
    suspend fun synthesize(
        apiKey: String,
        model: String = DEFAULT_TTS_MODEL,
        voiceName: String = DEFAULT_TTS_VOICE,
        text: String
    ): SynthesisResult = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "nothing to synthesize" }
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(JsonObject(mapOf(
                    "role" to JsonPrimitive("user"),
                    "parts" to buildJsonArray {
                        add(JsonObject(mapOf("text" to JsonPrimitive(text))))
                    }
                )))
            })
            put("generationConfig", buildJsonObject {
                put("response_modalities", buildJsonArray {
                    add(JsonPrimitive("AUDIO"))
                })
                put("speech_config", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", JsonPrimitive(voiceName))
                        })
                    })
                })
            })
        }

        val url = "$BASE_URL/models/$model:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Timber.tag("GeminiVoice").w(
                    "TTS %d: %s", response.code, responseBody.take(400)
                )
                throw GeminiVoiceException("TTS HTTP ${response.code}: ${responseBody.take(200)}")
            }
            val (base64Pcm, sampleRate) = extractAudio(responseBody)
                ?: throw GeminiVoiceException("TTS response had no audio data")
            val pcm = Base64.decode(base64Pcm, Base64.DEFAULT)
            val floats = pcm16LeToFloat(pcm)
            SynthesisResult(samples = floats, sampleRate = sampleRate)
        }
    }

    private fun extractText(jsonBody: String): String? {
        val obj = runCatching { json.parseToJsonElement(jsonBody).jsonObject }
            .getOrNull() ?: return null
        val candidates = obj["candidates"]?.jsonArray ?: return null
        for (candidate in candidates) {
            val content = candidate.jsonObject["content"]?.jsonObject ?: continue
            val parts = content["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val text = part.jsonObject["text"]?.jsonPrimitive?.content
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    private fun extractAudio(jsonBody: String): Pair<String, Int>? {
        val obj = runCatching { json.parseToJsonElement(jsonBody).jsonObject }
            .getOrNull() ?: return null
        val candidates = obj["candidates"]?.jsonArray ?: return null
        for (candidate in candidates) {
            val content = candidate.jsonObject["content"]?.jsonObject ?: continue
            val parts = content["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val inline = part.jsonObject["inlineData"]?.jsonObject
                    ?: part.jsonObject["inline_data"]?.jsonObject
                    ?: continue
                val data = inline["data"]?.jsonPrimitive?.content ?: continue
                // Gemini returns 24 kHz mono PCM16LE by default; sample rate is
                // declared in mime type's `rate=` parameter when present.
                val mime = inline["mimeType"]?.jsonPrimitive?.content
                    ?: inline["mime_type"]?.jsonPrimitive?.content
                    ?: ""
                val sampleRate = Regex("rate=(\\d+)").find(mime)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: DEFAULT_TTS_SAMPLE_RATE
                return data to sampleRate
            }
        }
        return null
    }

    private fun floatToPcm16Le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        var j = 0
        while (i < samples.size) {
            val s = samples[i].coerceIn(-1f, 1f)
            val v = (s * 32767f).toInt()
            out[j] = (v and 0xff).toByte()
            out[j + 1] = ((v ushr 8) and 0xff).toByte()
            i++
            j += 2
        }
        return out
    }

    private fun pcm16LeToFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        var i = 0
        var j = 0
        while (j < out.size) {
            val lo = pcm[i].toInt() and 0xff
            val hi = pcm[i + 1].toInt()
            val v = (hi shl 8) or lo
            val signed = if (v >= 0x8000) v - 0x10000 else v
            out[j] = signed / 32767f
            i += 2
            j++
        }
        return out
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_STT_MODEL = "gemini-2.5-flash"
        private const val DEFAULT_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        private const val DEFAULT_TTS_VOICE = "Kore"
        private const val DEFAULT_TTS_SAMPLE_RATE = 24_000
        private const val TRANSCRIBE_PROMPT =
            "You are a speech-to-text transcriber. Transcribe the user's audio " +
                "exactly as spoken. Do not translate, summarize, or answer — " +
                "return only the literal transcript text."
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class GeminiVoiceException(message: String) : RuntimeException(message)