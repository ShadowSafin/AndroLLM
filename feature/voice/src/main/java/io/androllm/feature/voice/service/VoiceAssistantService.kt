package io.androllm.feature.voice.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.common.getOrNull
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.models.Conversation
import io.androllm.core.models.Message
import io.androllm.core.models.MessageOrigin
import io.androllm.core.models.MessageRole
import io.androllm.core.models.ThemeMode
import io.androllm.core.navigation.Routes
import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.core.voice.asr.SpeechRecognizer
import io.androllm.core.voice.audio.AudioPlayer
import io.androllm.core.voice.audio.AudioRecorder
import io.androllm.core.voice.model.VoiceModels
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.core.voice.tts.OfflineTtsEngine
import io.androllm.core.voice.vad.Vad
import io.androllm.core.voice.wakeword.WakeWordEngine
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import io.androllm.feature.voice.VoiceAssistantController
import io.androllm.feature.voice.VoicePhase
import io.androllm.feature.voice.commands.VoiceCommand
import io.androllm.feature.voice.commands.VoiceCommandRouter
import io.androllm.feature.voice.ui.VoiceOverlayWindow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Foreground service that keeps the assistant alive.
 *
 * Owns the full voice loop — wake word → streaming STT → (local or cloud)
 * inference → sentence-level TTS — including barge-in interruption. Runs as a
 * microphone foreground service so Android keeps it alive and the notification
 * shows "Listening for 'Hey Andro'" with a tap-to-disable action.
 *
 * Nothing here touches the UI thread: audio capture, sherpa inference and
 * network I/O all run inside [scope] (Default dispatcher).
 */
@AndroidEntryPoint
class VoiceAssistantService : Service() {

    @Inject lateinit var voiceSettingsStore: VoiceSettingsStore
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var controller: VoiceAssistantController
    @Inject lateinit var wakeWordEngine: WakeWordEngine
    @Inject lateinit var recognizer: SpeechRecognizer
    @Inject lateinit var ttsEngine: OfflineTtsEngine
    @Inject lateinit var chatManager: io.androllm.feature.voice.chat.ChatManager
    @Inject lateinit var memoryManager: MemoryManager
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var messageRepository: MessageRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var recorder: AudioRecorder? = null
    private val audioPlayer = AudioPlayer(this)
    private var overlay: VoiceOverlayWindow? = null

    // ── Gemini Live-style overlay turn state ────────────────────────────────
    /** Conversation that holds the last completed turn (for "Open Conversation"). */
    @Volatile private var lastConversationId: String? = null
    /** Set by the overlay Cancel button; handleTurn checks it between deltas. */
    @Volatile private var cancelRequested: Boolean = false
    /** Ticker that feeds [VoiceAssistantController.setElapsedMs]. */
    private var elapsedJob: Job? = null
    /** Monotonic start time of the current turn (elapsed timer). */
    @Volatile private var turnStartMs: Long = 0L

    /**
     * "Mute" suppresses wake-word listening until [VoiceCommand.Unmute] is
     * spoken. It is in-memory only (resets on service restart) because it is a
     * transient "do not interrupt me" signal, not a preference.
     */
    @Volatile private var muted: Boolean = false

    /** Notification text last posted — dedupe so live metric emissions don't spam. */
    @Volatile private var lastNotifiedText: String? = null

    override fun onCreate() {
        super.onCreate()
        VoiceNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Timber.i("VoiceAssistantService: stop requested")
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForegroundCompat()
                startLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        // Kill every child coroutine (generation jobs, persistence, the state
        // collector) so no inference or DB write outlives the service.
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val ok = runCatching {
            val notification = VoiceNotifications.build(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    VoiceNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(VoiceNotifications.NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)
        if (!ok) {
            // A failed foreground start (e.g. mic permission revoked while the
            // process was dead, or a sticky restart) must not crash the app —
            // Android would kill the service anyway; stop ourselves cleanly.
            Timber.w("VoiceAssistantService: startForeground failed — stopping")
            stopSelf()
        }
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }

        // The floating overlay + notification follow the controller state.
        // A single failed emission (window/permission) must never kill the
        // collector coroutine — that would take the whole process down.
        scope.launch {
            controller.state.collect { state ->
                runCatching {
                    // Gemini Live-style overlay: show it while a wake→answer
                    // turn is in flight (turnActive) and the user hasn't
                    // dismissed it — NOT for the idle wake-word listening
                    // state. Respects settings.autoOpenOverlay.
                    val settings = voiceSettingsStore.current()
                    val shouldShow = state.active && state.turnActive &&
                        settings.autoOpenOverlay && !state.overlayDismissed
                    if (shouldShow) {
                        // Guard on `overlay == null` (synchronous) rather than
                        // `isShowing` (async — the view is only set after the
                        // window is posted to the main thread). The old check
                        // let every rapid state emission create ANOTHER window,
                        // stacking duplicate overlays.
                        if (overlay == null) {
                            overlay = VoiceOverlayWindow(this@VoiceAssistantService, voiceSettingsStore)
                            overlay?.show(controller)
                        }
                    } else {
                        overlay?.hide()
                        overlay = null
                    }
                    val text = when {
                        !state.active -> "Assistant is off"
                        state.phase == VoicePhase.RECEIVING_AUDIO ||
                            state.phase == VoicePhase.LISTENING -> "Listening\u2026"
                        state.phase == VoicePhase.STARTING_STT -> "Transcribing\u2026"
                        state.phase == VoicePhase.THINK -> "Thinking\u2026"
                        state.phase == VoicePhase.GENERATING -> "Responding\u2026"
                        state.phase == VoicePhase.SPEAK -> "Speaking\u2026"
                        else -> "Listening for \u201CHey Andro\u201D"
                    }
                    // The live mic metrics (RMS/amplitude) update on EVERY
                    // audio frame, which re-fires this collector constantly.
                    // Only re-post when the visible text actually changes —
                    // otherwise the notification spam-reposts itself.
                    if (text != lastNotifiedText) {
                        lastNotifiedText = text
                        NotificationManagerCompat.from(this@VoiceAssistantService)
                            .notify(VoiceNotifications.NOTIFICATION_ID, VoiceNotifications.build(this@VoiceAssistantService, text))
                    }
                }.onFailure { Timber.e(it, "Overlay/notification update failed") }
            }
        }

        // Overlay button intents → service actions. Runs for the whole service
        // lifetime so a button press never blocks on the state collector.
        scope.launch {
            controller.overlayEvents.collect { event ->
                when (event) {
                    io.androllm.feature.voice.VoiceOverlayEvent.Cancel -> {
                        Timber.i("Overlay: cancel requested")
                        cancelRequested = true
                        audioPlayer.stopNow()
                    }

                    io.androllm.feature.voice.VoiceOverlayEvent.ToggleMute -> {
                        controller.setMuted(!controller.state.value.muted)
                        if (controller.state.value.muted) audioPlayer.stopNow()
                        Timber.i("Overlay: mute toggled -> %s", controller.state.value.muted)
                    }

                    io.androllm.feature.voice.VoiceOverlayEvent.OpenChat -> {
                        Timber.i("Overlay: open chat")
                        launchMain(Routes.CHAT)
                    }

                    io.androllm.feature.voice.VoiceOverlayEvent.OpenConversation -> {
                        val id = lastConversationId
                        Timber.i("Overlay: open conversation %s", id)
                        if (!id.isNullOrBlank()) launchMain(Routes.chatDetail(id)) else launchMain(Routes.CHAT)
                        controller.dismissOverlay()
                    }

                    io.androllm.feature.voice.VoiceOverlayEvent.Close -> {
                        Timber.i("Overlay: close requested")
                        controller.dismissOverlay()
                        overlay?.hide()
                        overlay = null
                    }
                }
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        stopElapsedTicker()
        recorder?.stop()
        recorder = null
        audioPlayer.stopNow()
        overlay?.hide()
        overlay = null
        controller.setActive(false)
        controller.resetAll()
    }

    /**
     * Feeds the overlay's elapsed-time chip while a turn is in flight.
     * Stops on its own the moment the turn flag drops.
     */
    private fun startElapsedTicker() {
        stopElapsedTicker()
        elapsedJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                controller.setElapsedMs(SystemClock.elapsedRealtime() - turnStartMs)
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = null
        controller.setElapsedMs(0L)
    }

    // ── The main loop ────────────────────────────────────────────────────────

    /**
     * The loop runs on a background dispatcher for the whole service lifetime;
     * an exception in ANY stage (sherpa decode, inference, memory RAG, DB) must
     * end the loop gracefully instead of crashing the app process.
     */
    private suspend fun runLoop() {
        try {
            runLoopImpl()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ends the loop and releases the mic; never rethrows (a voice-stage
            // failure must not crash the app process). Catching Throwable also
            // covers OOM, where releasing the mic is exactly right.
            Timber.e(e, "Voice loop failed — stopping assistant gracefully")
            recorder?.stop()
            recorder = null
            // Order matters: setActive() clears the error field, and resetAll()
            // rebuilds the snapshot — so surface the error last.
            controller.setActive(false)
            controller.resetAll()
            controller.setError("Assistant stopped: ${e.message}")
        }
    }

    private suspend fun runLoopImpl() {
        Timber.i("VoiceService initialized")
        val initial = voiceSettingsStore.current()
        if (!initial.enabled) {
            controller.setActive(false)
            return
        }
        if (initial.chargingOnly && !isCharging()) {
            controller.setActive(false)
            controller.setError("Charging-only mode \u2014 plug in to enable the assistant")
            return
        }

        Timber.i("Voice engines starting")
        if (!wakeWordEngine.ensureInitialized()) {
            controller.setActive(false)
            controller.setError("Wake word model missing — rebuild with bundled voice models")
            return
        }
        if (!recognizer.ensureInitialized()) {
            controller.setActive(false)
            controller.setError("No speech recognition model installed — download one in Settings")
            return
        }
        Timber.i("Wake + Whisper models loaded")

        val rec = AudioRecorder(
            noiseSuppression = initial.noiseSuppression,
            echoCancellation = initial.echoCancellation
        )
        val started = runCatching { rec.start() }.getOrDefault(false)
        if (!started) {
            controller.setActive(false)
            controller.setError("Microphone unavailable — grant microphone permission")
            return
        }
        recorder = rec
        controller.resetAll()
        controller.setActive(true)
        Timber.i("AudioRecord started: 16000Hz MONO PCM16")

        var resumeListening = false
        var framesCount = 0L

        while (currentCoroutineContext().isActive) {
            val settings = voiceSettingsStore.current()
            if (!settings.enabled) break
            if (settings.chargingOnly && !isCharging()) break

            if (!resumeListening) {
                // ── WAKE (OpenWakeWord) ──
                controller.resetTurn()
                controller.setPhase(VoicePhase.LISTENING)
                controller.updateDebugMetrics(
                    micOwner = "OpenWakeWord",
                    onnxStatus = "whisper.cpp ready",
                    threshold = settings.sensitivity
                )
                wakeWordEngine.startSession(settings.wakePhrases)
                Timber.i("STATE: LISTENING -> RUNNING INFERENCE (Wake Engine Active)")

                val wake = awaitWakeWord(rec) { rms, maxAmp, confidence ->
                    framesCount++
                    controller.updateDebugMetrics(
                        micRms = rms,
                        maxAmplitude = maxAmp,
                        confidenceScore = confidence,
                        framesReceived = framesCount,
                        micOwner = "OpenWakeWord"
                    )
                }
                if (wake == null) break

                Timber.i("STATE: WAKE DETECTED -> $wake")
                controller.setPhase(VoicePhase.WAKE_DETECTED)
                controller.setWakeWord(wake)

                // ── Gemini Live-style turn: show the overlay, tick the timer ──
                controller.setTurnActive(true)
                turnStartMs = SystemClock.elapsedRealtime()
                val modelLabel = runCatching { chatManager.activeModelLabel() }.getOrNull() ?: ("" to "")
                controller.setModelInfo(modelLabel.first, modelLabel.second)
                startElapsedTicker()
                if (settings.playStartSound) VoiceSounds.playStart()
            }
            resumeListening = false

            // ── LISTEN / RECORDING (Whisper.cpp) ──
            Timber.i("STATE: STARTING STT -> Listening for user speech")
            controller.setPhase(VoicePhase.RECEIVING_AUDIO)
            controller.updateDebugMetrics(micOwner = "whisper.cpp")

            // Step 3: audio pipeline config.
            Timber.tag("STT").i(
                "Recording REQUESTED: %dHz MONO PCM16 | device granted=%dHz | buffer=%dB (%dms) | chunk=%d samples/%.0fms | engine=%s model=%s",
                VoiceModels.SAMPLE_RATE, rec.actualSampleRate, rec.bufferSizeBytes,
                if (rec.actualSampleRate > 0) rec.bufferSizeBytes * 1000 / (rec.actualSampleRate * 2) else 0,
                CHUNK_SAMPLES, CHUNK_MS.toFloat(),
                recognizer.engineLabel, settings.whisperModel
            )
            Timber.tag("STT").i(
                "Recording STARTED: language=%s translate=%s threads=%d beam=%d temp=%.2f max=%ds streaming=%s",
                settings.sttLanguage, settings.sttTranslate, settings.sttThreads,
                settings.sttBeamSize, settings.sttTemperature, settings.sttMaxSeconds, settings.sttStreaming
            )

            val transcript = awaitUtterance(rec, settings) { rms, maxAmp ->
                framesCount++
                controller.updateDebugMetrics(
                    micRms = rms,
                    maxAmplitude = maxAmp,
                    framesReceived = framesCount,
                    micOwner = "whisper.cpp"
                )
            }
            if (transcript == null) break
            val trimmed = transcript.trim()
            // Guard against noise-only transcripts: never send a junk/empty string to
            // the provider as a "question". Always acknowledge the turn so the
            // user isn't left staring at "receiving audio".
            if (trimmed.length < 2) {
                Timber.i("STT: no usable transcript='%s'", trimmed.take(40))
                stopElapsedTicker()
                controller.setTurnActive(false)
                controller.setTranscript("")
                controller.setPhase(VoicePhase.STARTING_STT)
                kotlinx.coroutines.delay(TRANSCRIBE_HOLD_MS)
                if (settings.autoReadAnswers && !controller.state.value.muted) {
                    controller.setAnswer(CATCH_FALLBACK)
                    speakSentences(rec, listOf(CATCH_FALLBACK), settings)
                } else {
                    controller.setAnswer(CATCH_FALLBACK)
                }
                drainMicEcho(rec, POST_SPEECH_GUARD_MS)
                continue
            }

            controller.setTranscript(trimmed)
            controller.setPartialTranscript("")
            // 📝 Transcribing beat: show the finalized transcript for a moment
            // (Listening → Transcribing → Thinking). The overlay stays open
            // for the WHOLE turn — listening, transcribing, thinking,
            // responding and speaking — and closes on its own when the turn
            // ends (turnActive drops; the window owner hides it).
            controller.setPhase(VoicePhase.STARTING_STT)
            Timber.i("STT: transcript='%s'", trimmed.take(80))
            kotlinx.coroutines.delay(TRANSCRIBE_HOLD_MS)

            // ── THINK + SPEAK (ChatManager & Piper TTS) ──
            Timber.i("STATE: GENERATING -> ChatManager routing")
            controller.setPhase(VoicePhase.THINK)
            controller.updateDebugMetrics(micOwner = "Released")

            val interrupted = handleTurn(rec, trimmed, settings)

            // ── Turn finished: stop the timer, park the overlay, play end chime ──
            stopElapsedTicker()
            controller.setTurnActive(false)
            if (!interrupted && settings.playEndSound) VoiceSounds.playEnd()
            // The mic hears the tail of the spoken answer as echo; let it
            // decay before wake-listening resumes so the assistant doesn't
            // re-trigger on its own voice.
            if (!interrupted) drainMicEcho(rec, POST_SPEECH_GUARD_MS)

            if (interrupted) {
                resumeListening = true
                controller.setPhase(VoicePhase.RECEIVING_AUDIO)
                continue
            }

            if (settings.continuousConversation && !settings.batterySaver) {
                resumeListening = true
                continue
            }
            controller.setPhase(VoicePhase.DONE)
        }

        rec.stop()
        controller.updateDebugMetrics(micOwner = "Released", onnxStatus = "Idle")
        controller.setActive(false)
        controller.resetAll()
    }

    /** Consumes mic chunks until a wake word fires. */
    private suspend fun awaitWakeWord(
        rec: AudioRecorder,
        onAudioStats: (rms: Float, maxAmp: Float, confidence: Float) -> Unit
    ): String? {
        var lastLogTime = 0L
        while (currentCoroutineContext().isActive) {
            val chunk = try {
                rec.chunks.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }

            // Audio metrics calculation
            var sumSq = 0.0
            var maxAmp = 0.0f
            for (s in chunk) {
                sumSq += (s * s).toDouble()
                val absS = kotlin.math.abs(s)
                if (absS > maxAmp) maxAmp = absS
            }
            val rms = kotlin.math.sqrt(sumSq / chunk.size.coerceAtLeast(1)).toFloat()

            val keyword = wakeWordEngine.feed(chunk)
            val score = if (keyword != null) 1.0f else (if (maxAmp > 0.05f) 0.15f else 0.02f)

            onAudioStats(rms, maxAmp, score)

            val now = System.currentTimeMillis()
            if (now - lastLogTime > 1000) {
                lastLogTime = now
                Timber.i("AUDIO METRICS: RMS=%.4f, MaxAmp=%.4f | KWS Confidence=%.2f", rms, maxAmp, score)
            }

            if (keyword != null) return keyword
        }
        return null
    }

    /**
     * Records the user's utterance (until trailing silence / max duration) and
     * transcribes it with whisper.cpp. Live partials are shown while speaking.
     */
    private suspend fun awaitUtterance(
        rec: AudioRecorder,
        settings: VoiceSettings,
        onAudioStats: (rms: Float, maxAmp: Float) -> Unit
    ): String? {
        val session = try {
            recognizer.startSession(
                language = settings.sttLanguage,
                translate = settings.sttTranslate,
                numThreads = settings.sttThreads,
                beamSize = settings.sttBeamSize,
                temperature = settings.sttTemperature,
                maxSeconds = settings.sttMaxSeconds,
                streamingEnabled = settings.sttStreaming
            )
        } catch (e: Throwable) {
            Timber.e(e, "STT: session start failed")
            return null
        }

        var lastPartial = ""
        var lastPartialRun = 0L
        var lastMetricLog = 0L
        var chunkCount = 0L
        var sumSqTotal = 0.0
        var peakAmp = 0.0f
        var firstSpeechAt = -1L
        var sawSpeech = false
        var quietChunks = 0
        // Ambient noise floor observed while the user is silent (before speech
        // starts). The endpoint threshold is derived from it, so room hum or a
        // fan that sits above the old fixed constant can no longer keep the
        // session "recording" until the hard cap.
        var ambientFloor = ENDPOINT_SILENCE_RMS
        var speechPeak = 0f
        val startedAt = SystemClock.elapsedRealtime()
        val maxDurationMs = (settings.sttMaxSeconds * 1000L).coerceAtMost(UTTERANCE_MAX_SECONDS * 1000L)
        val silenceQuietChunks = (settings.silenceTimeoutMs / CHUNK_MS).toInt().coerceIn(2, 30)
        // The wake chime echoes into the mic for ~0.5s; ignore that window so
        // it never counts as "speech" (which would let the endpoint fire while
        // the user is still paused before their question).
        val startGuardUntil = startedAt + START_GUARD_MS
        var stopReason = "cancelled"

        // Partials run on whisper's own dispatcher; the recording loop keeps
        // appending audio while a partial is being computed.
        val partialScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lastPartialRef = java.util.concurrent.atomic.AtomicReference("")

        while (currentCoroutineContext().isActive) {
            val chunk = try {
                rec.chunks.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                stopReason = "channel-closed"
                Timber.tag("STT").w(
                    "Recording STOPPED REASON=channel-closed after %dms",
                    SystemClock.elapsedRealtime() - startedAt
                )
                break
            }

            // Step 3/5: per-chunk audio stats.
            var sumSq = 0.0
            var maxAmp = 0.0f
            for (s in chunk) {
                sumSq += (s * s).toDouble()
                val absS = kotlin.math.abs(s)
                if (absS > maxAmp) maxAmp = absS
            }
            val rms = kotlin.math.sqrt(sumSq / chunk.size.coerceAtLeast(1)).toFloat()
            chunkCount++
            sumSqTotal += sumSq
            if (maxAmp > peakAmp) peakAmp = maxAmp
            onAudioStats(rms, maxAmp)
            session.append(chunk)

            val nowRt = SystemClock.elapsedRealtime()
            // Track the quietest observation before speech starts: that is the
            // room's ambient noise floor used for the sentence-end threshold.
            if (!sawSpeech && rms > 0f && rms < ambientFloor) ambientFloor = rms
            // Real speech only counts after the wake chime guard window.
            if (!sawSpeech && nowRt >= startGuardUntil && rms > SPEECH_RMS) {
                sawSpeech = true
                firstSpeechAt = nowRt - startedAt
            }
            if (sawSpeech && rms > speechPeak) speechPeak = rms

            // Step 5: live partials (sliding-window whisper decode), only while
            // real speech is on the mic.
            if (settings.sttStreaming && sawSpeech && nowRt - lastPartialRun > PARTIAL_EVERY_MS) {
                lastPartialRun = nowRt
                partialScope.launch {
                    val res = runCatching { session.partial() }.getOrNull() ?: return@launch
                    val text = res.text.trim()
                    if (text.isNotBlank() && text != lastPartialRef.get()) {
                        lastPartialRef.set(text)
                        controller.setPartialTranscript(text)
                        Timber.tag("STT").i("partial: '%s' (inference %dms)", text.take(60), res.inferenceMs)
                    }
                }
            }

            // Step 3: periodic volume metric (once per second).
            if (nowRt - lastMetricLog > 1000) {
                lastMetricLog = nowRt
                Timber.tag("STT").i(
                    "metric: rms=%.4f peak=%.4f chunks=%d elapsed=%dms firstSpeech=%s",
                    rms, maxAmp, chunkCount, nowRt - startedAt,
                    if (sawSpeech) "${firstSpeechAt}ms" else "not-yet"
                )
            }

            // Sentence-end detection: the user paused. The silence threshold is
            // adaptive (ambient floor + a fraction of their speaking level), so
            // it ends ~0.8s after the last word and never on room noise alone.
            val endpointRms = maxOf(
                ENDPOINT_MIN_RMS,
                ambientFloor * ENDPOINT_AMBIENT_MULT,
                speechPeak * ENDPOINT_PEAK_RATIO
            ).coerceAtMost(SPEECH_RMS * 0.9f)
            if (sawSpeech && nowRt - firstSpeechAt >= MIN_SPEECH_MS && rms < endpointRms) {
                if (++quietChunks >= silenceQuietChunks) {
                    stopReason = "endpoint"
                    break
                }
            } else {
                quietChunks = 0
            }

            // Hard cap — never record forever.
            if (nowRt - startedAt >= maxDurationMs) {
                stopReason = "max-duration"
                break
            }

            // No speech within a reasonable window (forgotten microphone, the
            // wake word fired on a sound): give up early instead of recording
            // for the full cap.
            if (!sawSpeech && nowRt - startedAt > NO_SPEECH_TIMEOUT_MS) {
                stopReason = "no-speech"
                break
            }
        }

        partialScope.cancel()
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        val avgRms = kotlin.math.sqrt(sumSqTotal / (chunkCount * 3200).coerceAtLeast(1)).toFloat()
        Timber.tag("STT").i(
            "Recording STOPPED REASON=%s | duration=%dms | chunks=%d | avgRMS=%.4f peak=%.4f | firstSpeech=%dms | samples=%d",
            stopReason, durationMs, chunkCount, avgRms, peakAmp, firstSpeechAt, session.sampleCount
        )

        val result = runCatching { session.finish() }.getOrElse {
            Timber.e(it, "STT: transcription failed")
            null
        }
        session.release()
        val text = stripWakePrefix(result?.text?.trim() ?: "")
        Timber.tag("STT").i(
            "RAW TRANSCRIPT: '%s' | inference=%dms | duration=%dms",
            text.take(100), result?.inferenceMs ?: 0L, durationMs
        )
        if (text.isBlank()) {
            Timber.tag("STT").w("STT: no speech recognized")
            return ""
        }
        return text
    }

    /**
     * Routes one transcript. Returns true when the turn was interrupted by a
     * barge-in (the caller should listen again).
     */
    private suspend fun handleTurn(rec: AudioRecorder, transcript: String, settings: VoiceSettings): Boolean {
        val command = VoiceCommandRouter.match(transcript)
        if (command != null) {
            controller.setPhase(VoicePhase.THINK)
            val ack = executeLocalCommand(command)
            if (settings.autoReadAnswers && ack.isNotBlank()) {
                controller.setAnswer(ack)
                val interrupted = speakSentences(rec, listOf(ack), settings)
                if (interrupted) return true
            }
            return false
        }

        // Thinking state stays visible until the first token arrives (the
        // overlay's mascot shrinks + glows while the provider warms up).
        controller.setPhase(VoicePhase.THINK)
        cancelRequested = false
        val deltas = Channel<String>(Channel.UNLIMITED)
        // Speech chunks produced by [SentenceAssembler], consumed by the TTS
        // coroutine INDEPENDENTLY of generation (true streaming TTS: speak a
        // chunk while the model keeps generating the rest). Each chunk carries
        // its offset in the answer text so the overlay can highlight it.
        val speechQueue = Channel<SpokenChunk>(Channel.UNLIMITED)
        // True while OUR OWN speech is playing; the generation loop skips
        // barge-in VAD then, so the assistant never cuts itself off on echo.
        val speechActive = AtomicBoolean(false)

        val genJob = scope.launch {
            try {
                // Step 9: prove the exact transcript reaches the chat pipeline.
                // Whatever the recognizer produced is what the model must see.
                Timber.tag("STT").i(
                    "STEP9: sending EXACT transcript to ChatManager (origin=VOICE): '%s' (%d chars)",
                    transcript, transcript.length
                )
                chatManager.sendMessageStream(
                    content = transcript,
                    origin = MessageOrigin.VOICE,
                    lowLatencyMode = settings.lowLatencyMode
                ).collect { delta ->
                    deltas.trySend(delta)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.setError("Generation error: ${e.message}")
            }
        }

        // ── Streaming TTS consumer ──
        // Pulls chunks from the speech queue the moment they form, synthesizes
        // and plays each one immediately while generation continues. Drains
        // the mic during playback so the echo backlog never reaches the next
        // stage; barge-in is deliberately off while our own voice plays.
        val ttsJob = if (settings.autoReadAnswers && !controller.state.value.muted) {
            scope.launch {
                if (!ttsEngine.ensureInitialized()) {
                    Timber.w("TTS: engine not initialized — skipping speech (text still streams in overlay)")
                    return@launch
                }
                try {
                    for (chunk in speechQueue) {
                        if (cancelRequested || !currentCoroutineContext().isActive) break
                        val samples = ttsEngine.synthesize(chunk.text, settings.speakingSpeed)
                        if (samples == null || samples.isEmpty()) {
                            Timber.w("TTS: synthesize returned null/empty for '%s' — text still shown in overlay", chunk.text.take(40))
                            continue
                        }
                        controller.setPhase(VoicePhase.SPEAK)
                        // Stays true for the WHOLE speech session (all chunks):
                        // between chunks the mic hears the speaker's room reverb
                        // for a beat — clearing it early would let barge-in VAD
                        // cancel generation on our own echo. Only re-enabled
                        // when the queue is drained and generation is done.
                        speechActive.set(true)
                        // Karaoke highlight: the overlay lights up this chunk's
                        // words as they are spoken (timing estimated from the
                        // audio duration — the Piper engine gives no timestamps).
                        val durationMs = (samples.size.toLong() * 1000L) / ttsEngine.sampleRate
                        controller.setSpokenRange(chunk.startOffset, chunk.startOffset + chunk.text.length)
                        Timber.i("TTS: speaking %d samples @ %d Hz for '%s'", samples.size, ttsEngine.sampleRate, chunk.text.take(40))
                        val playJob = scope.launch { audioPlayer.play(samples, ttsEngine.sampleRate) }
                        val progressJob = scope.launch { driveWordProgress(chunk, durationMs) }
                        while (playJob.isActive) {
                            withTimeoutOrNull(300) { rec.chunks.receive() } ?: continue
                        }
                        playJob.join()
                        progressJob.cancel()
                        // More text is still streaming but nothing left to speak:
                        // return to the streaming-text state.
                        if (!genJob.isCompleted && speechQueue.isEmpty) {
                            controller.setPhase(VoicePhase.GENERATING)
                        }
                    }
                } finally {
                    // Speech session over: re-enable barge-in for any remaining
                    // generation or the next turn, and clear the highlight.
                    speechActive.set(false)
                    controller.clearSpoken()
                }
            }
        } else {
            null
        }

        val answer = StringBuilder()
        val assembler = SentenceAssembler()
        // Barge-in while the assistant is thinking: only real speech near the
        // mic (RMS > 0.02) cancels generation. The default 0.005 fires on
        // ambient noise, cancelling generation and looping STT→GENERATING.
        val vad = Vad(threshold = GENERATION_BARGE_IN_THRESHOLD)
        var interrupted = false
        var gotFirstDelta = false
        var lastDeltaAt = System.currentTimeMillis()
        var lastFlushAt = System.currentTimeMillis()
        while (!genJob.isCompleted || !deltas.isEmpty) {
            // Barge-in VAD — skipped while our own speech is playing (the mic
            // would otherwise hear the speaker and cancel the answer).
            if (!speechActive.get()) {
                var drained = 0
                while (drained < 20 && !speechActive.get() && currentCoroutineContext().isActive) {
                    val chunk = rec.chunks.tryReceive().getOrNull() ?: break
                    if (vad.process(chunk)) {
                        interrupted = true
                        break
                    }
                    drained++
                }
                if (interrupted) break
            }

            val delta = withTimeoutOrNull(1000) { deltas.receive() }
            if (delta == null) {
                // No delta for a full second: if the provider is silent for
                // too long (connect stall, dead stream), surface a fallback
                // instead of letting the overlay sit on "Responding…" forever.
                if (!gotFirstDelta && System.currentTimeMillis() - lastDeltaAt > FIRST_TOKEN_TIMEOUT_MS) {
                    Timber.w("Generation: no first token within %d ms — falling back", FIRST_TOKEN_TIMEOUT_MS)
                    break
                }
                // Model paused mid-sentence (thinking): flush any partial
                // chunk so speech keeps flowing (~1-2 s rule).
                if (ttsJob != null && gotFirstDelta &&
                    System.currentTimeMillis() - lastFlushAt > STREAM_FLUSH_IDLE_MS
                ) {
                    assembler.flush()?.let { speechQueue.trySend(it) }
                    lastFlushAt = System.currentTimeMillis()
                }
                continue
            }
            gotFirstDelta = true
            lastDeltaAt = System.currentTimeMillis()
            if (cancelRequested) {
                interrupted = true
                break
            }
            // First token: Thinking → Generating (tokens now streaming live).
            if (controller.state.value.phase == VoicePhase.THINK) {
                controller.setPhase(VoicePhase.GENERATING)
            }
            answer.append(delta)
            // Live-stream the answer text into the overlay as tokens arrive.
            controller.setAnswer(answer.toString())
            // Feed the chunker; every completed chunk is queued for TTS
            // immediately (sentence end / clause / word count). Only when TTS
            // is on — muted/silent mode never double-buffers the answer.
            if (ttsJob != null) {
                assembler.feed(delta).forEach { speechQueue.trySend(it) }
            }
        }

        // Stream tail: flush the final partial sentence so it's still spoken.
        if (ttsJob != null) {
            assembler.flush(force = true)?.let { speechQueue.trySend(it) }
        }

        if (!interrupted && !gotFirstDelta) {
            // The provider never produced a token. Cancel the job so the
            // fallback below runs immediately.
            Timber.w("Generation: no deltas received — cancelling job")
            genJob.cancel()
            genJob.join()
            interrupted = false
        }

        if (interrupted) {
            genJob.cancel()
            ttsJob?.cancel()
            audioPlayer.stopNow()
        } else {
            if (genJob.isActive) {
                // A provider that stalled without emitting anything must not
                // block the fallback forever — the FIRST_TOKEN_TIMEOUT already
                // broke the loop above; cancel and move on.
                genJob.cancelAndJoin()
            }
            // Remember where this turn lives so the overlay's "Open
            // Conversation" button can deep-link into it.
            lastConversationId = runCatching {
                conversationRepository.observeActive().first().firstOrNull()?.id
            }.getOrNull()

            val fullAnswer = answer.toString()
            if (fullAnswer.isNotBlank()) {
                controller.setAnswer(fullAnswer)
                // Speech and generation ran concurrently; close the queue and
                // wait only for the speech tail before the turn ends. Only
                // claim the Speaking state when TTS actually played (muted or
                // autoReadAnswers off stays on Generating with the full text).
                speechQueue.close()
                ttsJob?.join()
                if (ttsJob != null && controller.state.value.phase == VoicePhase.GENERATING) {
                    controller.setPhase(VoicePhase.SPEAK)
                }
            } else if (controller.state.value.error == null) {
                speechQueue.close()
                ttsJob?.cancel()
                // Safety net: a turn ended with NO real text (silent hang or
                // blank stream) — surface a friendly fallback so the overlay
                // never sits silent.
                val fallback = "I couldn't generate a response. Make sure a model is loaded or a cloud provider is added in settings."
                controller.setAnswer(fallback)
                if (settings.autoReadAnswers && !controller.state.value.muted) {
                    speakSentences(rec, listOf(fallback), settings)
                }
            } else {
                speechQueue.close()
                ttsJob?.cancel()
            }
        }
        return interrupted
    }

    /**
     * Drives the overlay's word-level karaoke highlight while [chunk] plays.
     *
     * The Piper engine provides no word timestamps, so timing is estimated:
     * each word's share of the chunk's total audio duration is proportional
     * to its character length (the classic "karaoke" approximation). Runs on
     * its own coroutine and is cancelled when playback ends or is interrupted.
     */
    private suspend fun driveWordProgress(chunk: SpokenChunk, durationMs: Long) {
        val words = chunk.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || durationMs <= 0) return
        val totalWeight = words.sumOf { it.length + 1 }
        val startedAt = SystemClock.elapsedRealtime()
        var lastIndex = -1
        while (currentCoroutineContext().isActive) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= durationMs) break
            val progress = elapsed.toFloat() / durationMs
            // Find the word whose cumulative weight covers the current progress.
            var acc = 0f
            var index = 0
            for (i in words.indices) {
                acc += (words[i].length + 1).toFloat() / totalWeight
                if (progress <= acc) {
                    index = i
                    break
                }
                index = i
            }
            if (index != lastIndex) {
                lastIndex = index
                controller.setSpokenWordIndex(index)
            }
            kotlinx.coroutines.delay(60)
        }
        // Chunk finished — clear the active word (whole sentence stays lit).
        controller.setSpokenWordIndex(-1)
    }

    /**
     * Speaks sentences one by one while watching the mic for barge-in.
     * Returns true when the user started talking (interrupted).
     */
    private suspend fun speakSentences(rec: AudioRecorder, sentences: List<String>, settings: VoiceSettings): Boolean {
        if (!ttsEngine.ensureInitialized()) {
            Timber.w("TTS: engine not initialized — skipping speech (text still shown in overlay)")
            return false
        }
        var offset = 0
        for (sentence in sentences) {
            if (!currentCoroutineContext().isActive) return true
            val samples = ttsEngine.synthesize(sentence, settings.speakingSpeed)
            if (samples == null || samples.isEmpty()) {
                Timber.w("TTS: synthesize returned null/empty for '%s' — text still shown in overlay", sentence.take(40))
                offset += sentence.length
                continue
            }
            // Karaoke highlight for the non-streaming fallback path too.
            val durationMs = (samples.size.toLong() * 1000L) / ttsEngine.sampleRate
            controller.setSpokenRange(offset, offset + sentence.length)
            Timber.i("TTS: speaking %d samples @ %d Hz for '%s'", samples.size, ttsEngine.sampleRate, sentence.take(40))
            val playJob = scope.launch { audioPlayer.play(samples, ttsEngine.sampleRate) }
            val progressJob = scope.launch { driveWordProgress(SpokenChunk(sentence, offset), durationMs) }
            offset += sentence.length
            // Drain the mic while speaking so the next stage never sees a
            // backlog of echo. Barge-in is deliberately disabled: the mic
            // hears the assistant's own voice through the speaker, and ANY
            // energy threshold fires on that continuous echo — cutting the
            // answer mid-sentence and re-triggering a new turn from the echo
            // transcript. The overlay Cancel button (audioPlayer.stopNow) is
            // the interrupt path instead.
            while (playJob.isActive) {
                withTimeoutOrNull(300) { rec.chunks.receive() } ?: continue
            }
            playJob.join()
            progressJob.cancel()
        }
        controller.clearSpoken()
        return false
    }

    /**
     * Discards mic audio after the assistant speaks so the echo/reverb of its
     * own voice can decay before wake-listening or continuous STT resumes.
     * Drains for up to [maxMs] but exits early once the mic has been quiet
     * (below speech energy) for a short stretch — so a fast user reply is not
     * swallowed by the guard.
     */
    private suspend fun drainMicEcho(rec: AudioRecorder, maxMs: Long) {
        val end = SystemClock.elapsedRealtime() + maxMs
        // Never exit before this instant — the speaker's tail is still
        // ringing during the first ~half second even if a chunk reads quiet.
        val minDrainUntil = SystemClock.elapsedRealtime() + 500L
        var quietChunks = 0
        while (SystemClock.elapsedRealtime() < end && currentCoroutineContext().isActive) {
            val chunk = withTimeoutOrNull(200) { rec.chunks.receive() } ?: continue
            var energy = 0.0
            for (s in chunk) energy += s.toDouble() * s
            energy /= chunk.size.coerceAtLeast(1)
            if (energy < 0.004) {
                if (SystemClock.elapsedRealtime() >= minDrainUntil && ++quietChunks >= 2) break
            } else {
                quietChunks = 0
            }
        }
    }

    // ── Local commands ───────────────────────────────────────────────────────

    private suspend fun executeLocalCommand(command: VoiceCommand): String = when (command) {
        VoiceCommand.OpenSettings -> {
            launchMain(Routes.SETTINGS)
            "Opening settings."
        }

        VoiceCommand.OpenModels -> {
            launchMain(Routes.MODELS)
            "Opening the models screen."
        }

        VoiceCommand.DeleteConversation -> {
            val active = conversationRepository.observeActive().first().firstOrNull()
            if (active != null) {
                conversationRepository.deleteById(active.id)
                "Conversation deleted."
            } else {
                "There is no conversation to delete."
            }
        }

        VoiceCommand.SummarizeChat -> summarizeActiveConversation()

        VoiceCommand.StartNewChat -> {
            val active = conversationRepository.observeActive().first().firstOrNull()
            if (active != null) conversationRepository.deleteById(active.id)
            "Starting a new chat."
        }

        VoiceCommand.StopSpeaking -> {
            interruptActiveTurn()
            "Stopped."
        }

        VoiceCommand.Mute -> {
            muted = true
            "Muted. Say \"unmute\" when you're done."
        }

        VoiceCommand.Unmute -> {
            muted = false
            "Listening again."
        }

        VoiceCommand.EnableOfflineMode -> {
            voiceSettingsStore.update { it.copy(offlineOnly = true, cloudFallback = false) }
            "Offline mode is on. I will only use the local model."
        }

        VoiceCommand.DisableOfflineMode -> {
            voiceSettingsStore.update { it.copy(offlineOnly = false, cloudFallback = true) }
            "Offline mode is off. Cloud fallback is on."
        }

        VoiceCommand.DisableVoice -> {
            voiceSettingsStore.update { it.copy(enabled = false) }
            // Send ourselves a stop intent; the service tears itself down on
            // the next onStartCommand when ACTION_STOP arrives.
            runCatching {
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            "Voice assistant disabled."
        }

        VoiceCommand.SwitchTheme -> {
            val current = settingsRepository.getSettings().getOrNull() ?: io.androllm.core.models.AppSettings()
            val next = when (current.theme) {
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.SYSTEM
                ThemeMode.SYSTEM -> ThemeMode.LIGHT
            }
            settingsRepository.updateTheme(next)
            "Theme switched to ${next.name.lowercase()}."
        }
    }

    /**
     * Cancels the current turn as if a barge-in fired: stops the audio
     * player, cancels the generation job, and lets [handleTurn] return.
     */
    private fun interruptActiveTurn() {
        runCatching { audioPlayer.stopNow() }
    }

    private suspend fun summarizeActiveConversation(): String {
        val active = conversationRepository.observeActive().first().firstOrNull() ?: return "There is no conversation to summarize."
        val messages = messageRepository.observeByConversationId(active.id).first()
        if (messages.isEmpty()) return "This conversation has no messages yet."
        val recent = messages.takeLast(10).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(400)}" }
        return "Conversation summary:\n${recent.take(200)}..."
    }

    private fun launchMain(route: String) {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Routes.EXTRA_NAV_ROUTE, route)
            } ?: return
            startActivity(intent)
        }
    }

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Removes a leading wake word ("hey andro") from a transcript. The KWS
     * lookback deliberately replays the keyword tail so the question start is
     * captured; the residue is cosmetic and must not reach the model.
     */
    private fun stripWakePrefix(text: String): String {
val m = WAKE_PREFIX.find(text) ?: return text
        return text.substring(m.range.last + 1).trim()
    }

    companion object {
        const val ACTION_START = "io.androllm.voice.START"
        const val ACTION_STOP = "io.androllm.voice.STOP"

        /** Spoken reply whenever speech was heard but not understood. */
        private const val CATCH_FALLBACK = "Sorry — I didn't catch that. Could you say it again?"

        /** Leading wake word ("hey andro") at the start of a transcript. */
        private val WAKE_PREFIX = Regex(
            """^\s*(?i:(?:(?:hey|hai|hei|hi)\s+)?(?:andro\w*|andrew\w*|android\w*))[,.!?\s]*"""
        )

        /** One mic chunk duration in ms (3200 samples @ 16 kHz). */
        private const val CHUNK_MS = 200L

        /** One mic chunk in samples (200 ms @ 16 kHz). */
        private const val CHUNK_SAMPLES = 3200

        /** How often a streaming partial transcription is attempted (ms). */
        private const val PARTIAL_EVERY_MS = 1000L

        /** Wake chime echo window that must not count as speech (ms). */
        private const val START_GUARD_MS = 700L

        /** Chunk RMS above which the mic counts as real speech. */
        private const val SPEECH_RMS = 0.04f

        /** Quiet baseline for the sentence-end silence threshold. */
        private const val ENDPOINT_SILENCE_RMS = 0.02f
        /** Sentence ends when silence drops below ambient noise × this. */
        private const val ENDPOINT_AMBIENT_MULT = 2.5f
        /** …and below the spoken peak × this (soft speakers still end). */
        private const val ENDPOINT_PEAK_RATIO = 0.07f
        /** Hardest floor the adaptive endpoint ever uses. */
        private const val ENDPOINT_MIN_RMS = 0.012f
        /** Never end on silence before this much speech was heard (ms). */
        private const val MIN_SPEECH_MS = 600L

        /** Give up waiting for speech after this long (no endpoint yet). */
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L

        /**
         * Hard cap for a single STT recording turn. If an endpoint never fires
         * (background noise, no speech), the turn force-stops after this long
         * instead of recording forever (Step 4).
         */
        private const val UTTERANCE_MAX_SECONDS = 30

        /**
         * Max time to wait for the first generated token. Cloud providers can
         * stall on connect; after this the assistant speaks a friendly
         * fallback instead of sitting on "Responding…" forever.
         */
        private const val FIRST_TOKEN_TIMEOUT_MS = 12_000L

        /**
         * How long to ignore the mic after the assistant finishes speaking so
         * the echo/reverb of its own voice can't re-trigger the wake word or
         * be transcribed by the continuous-conversation STT as a fake
         * question.
         */
        private const val POST_SPEECH_GUARD_MS = 2_000L

        /** Real-speech RMS threshold for cancelling generation on barge-in. */
        private const val GENERATION_BARGE_IN_THRESHOLD = 0.02f

        /**
         * If the provider pauses mid-sentence for this long without new
         * tokens, flush the partial chunk to TTS so speech keeps flowing
         * instead of stalling until the next punctuation mark.
         */
        private const val STREAM_FLUSH_IDLE_MS = 1_200L

        /** How long the overlay holds the 📝 Transcribing beat after STT. */
        private const val TRANSCRIBE_HOLD_MS = 400L
    }
}
