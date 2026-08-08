package io.androllm.feature.voice.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
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
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private val audioPlayer = AudioPlayer()
    private var overlay: VoiceOverlayWindow? = null

    /**
     * "Mute" suppresses wake-word listening until [VoiceCommand.Unmute] is
     * spoken. It is in-memory only (resets on service restart) because it is a
     * transient "do not interrupt me" signal, not a preference.
     */
    @Volatile private var muted: Boolean = false

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
                    if (state.active) {
                        if (overlay?.isShowing != true) {
                            overlay = VoiceOverlayWindow(this@VoiceAssistantService)
                            overlay?.show(controller)
                        }
                    } else {
                        overlay?.hide()
                        overlay = null
                    }
                    val text = when {
                        !state.active -> "Assistant is off"
                        state.phase == VoicePhase.LISTEN -> "Listening\u2026"
                        state.phase == VoicePhase.THINK -> "Thinking\u2026"
                        state.phase == VoicePhase.SPEAK -> "Speaking\u2026"
                        else -> "Listening for \u201CHey Andro\u201D"
                    }
                    NotificationManagerCompat.from(this@VoiceAssistantService)
                        .notify(VoiceNotifications.NOTIFICATION_ID, VoiceNotifications.build(this@VoiceAssistantService, text))
                }.onFailure { Timber.e(it, "Overlay/notification update failed") }
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        recorder?.stop()
        recorder = null
        audioPlayer.stopNow()
        overlay?.hide()
        overlay = null
        controller.setActive(false)
        controller.resetAll()
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

        Timber.i("ONNX Runtime initialized")
        if (!wakeWordEngine.ensureInitialized() || !recognizer.ensureInitialized()) {
            controller.setActive(false)
            controller.setError("Voice models missing — rebuild with bundled voice models")
            return
        }
        Timber.i("Wake model loaded successfully")

        recognizer.updateSilenceTimeout(initial.silenceTimeoutMs / 1000f)

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
        Timber.i("Receiving PCM frames...")
        Timber.i("Wake callback registered")
        Timber.i("Waiting for wake word...")

        var resumeListening = false
        var framesCount = 0L

        while (currentCoroutineContext().isActive) {
            val settings = voiceSettingsStore.current()
            if (!settings.enabled) break
            if (settings.chargingOnly && !isCharging()) break
            recognizer.updateSilenceTimeout(settings.silenceTimeoutMs / 1000f)

            if (!resumeListening) {
                // ── WAKE (OpenWakeWord) ──
                controller.resetTurn()
                controller.setPhase(VoicePhase.LISTENING)
                controller.updateDebugMetrics(
                    micOwner = "OpenWakeWord",
                    onnxStatus = "Loaded (int8 zipformer2)",
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
            }
            resumeListening = false

            // ── LISTEN / RECORDING (Sherpa ASR) ──
            Timber.i("STATE: STARTING STT -> Listening for user speech")
            controller.setPhase(VoicePhase.RECEIVING_AUDIO)
            controller.updateDebugMetrics(micOwner = "Sherpa ASR")
            recognizer.startSession()

            val transcript = awaitUtterance(rec) { rms, maxAmp ->
                framesCount++
                controller.updateDebugMetrics(
                    micRms = rms,
                    maxAmplitude = maxAmp,
                    framesReceived = framesCount,
                    micOwner = "Sherpa ASR"
                )
            }
            if (transcript == null) break
            val trimmed = transcript.trim()
            if (trimmed.isEmpty()) continue

            controller.setTranscript(trimmed)
            controller.setPartialTranscript("")

            // ── THINK + SPEAK (ChatManager & Piper TTS) ──
            Timber.i("STATE: GENERATING -> ChatManager routing")
            controller.setPhase(VoicePhase.GENERATING)
            controller.updateDebugMetrics(micOwner = "Released")

            val interrupted = handleTurn(rec, trimmed, settings)
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

    /** Consumes mic chunks into the recognizer until an endpoint fires. */
    private suspend fun awaitUtterance(
        rec: AudioRecorder,
        onAudioStats: (rms: Float, maxAmp: Float) -> Unit
    ): String? {
        var lastPartial = ""
        var lastPartialEmit = 0L
        while (currentCoroutineContext().isActive) {
            val chunk = try {
                rec.chunks.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }

            var sumSq = 0.0
            var maxAmp = 0.0f
            for (s in chunk) {
                sumSq += (s * s).toDouble()
                val absS = kotlin.math.abs(s)
                if (absS > maxAmp) maxAmp = absS
            }
            val rms = kotlin.math.sqrt(sumSq / chunk.size.coerceAtLeast(1)).toFloat()
            onAudioStats(rms, maxAmp)

            val partial = recognizer.feed(chunk)
            if (partial != lastPartial) {
                lastPartial = partial
                val now = System.currentTimeMillis()
                if (now - lastPartialEmit > 100) {
                    lastPartialEmit = now
                    controller.setPartialTranscript(partial)
                }
            }
            if (recognizer.isEndpoint()) {
                return recognizer.finalText()
            }
        }
        return null
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

        controller.setPhase(VoicePhase.THINK)
        val deltas = Channel<String>(Channel.UNLIMITED)
        val genJob = scope.launch {
            try {
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

        val answer = StringBuilder()
        val assembler = SentenceAssembler()
        val vad = Vad()
        var interrupted = false
        while (!genJob.isCompleted || !deltas.isEmpty) {
            var drained = 0
            while (drained < 20 && currentCoroutineContext().isActive) {
                val chunk = rec.chunks.tryReceive().getOrNull() ?: break
                if (vad.process(chunk)) {
                    interrupted = true
                    break
                }
                drained++
            }
            if (interrupted) break

            val delta = withTimeoutOrNull(1000) { deltas.receive() } ?: continue
            answer.append(delta)
            controller.setAnswer(answer.toString())
            val sentences = assembler.feed(delta)
            if (sentences.isNotEmpty()) {
                controller.setPhase(VoicePhase.SPEAK)
                if (settings.autoReadAnswers) {
                    val cut = speakSentences(rec, sentences, settings)
                    if (cut) {
                        interrupted = true
                        break
                    }
                }
            }
        }
        if (!interrupted) {
            val leftover = assembler.drain()
            if (leftover.isNotEmpty()) {
                controller.setPhase(VoicePhase.SPEAK)
                if (settings.autoReadAnswers) {
                    interrupted = speakSentences(rec, listOf(leftover), settings)
                }
            }
        }

        if (interrupted) {
            genJob.cancel()
            audioPlayer.stopNow()
        } else {
            genJob.join()
        }
        return interrupted
    }

    /**
     * Speaks sentences one by one while watching the mic for barge-in.
     * Returns true when the user started talking (interrupted).
     */
    private suspend fun speakSentences(rec: AudioRecorder, sentences: List<String>, settings: VoiceSettings): Boolean {
        val vad = Vad()
        for (sentence in sentences) {
            if (!currentCoroutineContext().isActive) return true
            val samples = ttsEngine.synthesize(sentence, settings.speakingSpeed) ?: continue
            val playJob = scope.launch { audioPlayer.play(samples, ttsEngine.sampleRate) }
            var interrupted = false
            while (playJob.isActive) {
                val chunk = withTimeoutOrNull(300) { rec.chunks.receive() } ?: continue
                if (vad.process(chunk)) {
                    interrupted = true
                    break
                }
            }
            if (interrupted) {
                audioPlayer.stopNow()
                playJob.cancelAndJoin()
                return true
            }
            playJob.join()
        }
        return false
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

    companion object {
        const val ACTION_START = "io.androllm.voice.START"
        const val ACTION_STOP = "io.androllm.voice.STOP"
    }
}
