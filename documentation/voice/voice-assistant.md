# Voice Assistant Architecture

Deep dive into the offline voice assistant system.

---

## Overview

The AndroLLM voice assistant provides a hands-free, fully offline interaction layer. The entire pipeline — from wake word detection to speech synthesis — runs on-device using sherpa-onnx ONNX models. No audio is ever transmitted to external servers.

---

## Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Voice Assistant Pipeline                      │
│                                                                 │
│  [Mic @ 16kHz] → AudioRecorder → Channel<FloatArray>           │
│                          │                                      │
│          ┌───────────────┼───────────────┐                      │
│          ▼               ▼               ▼                      │
│   WakeWordEngine     SpeechRecognizer  VoiceActivityDetector   │
│   (KWS zipformer)    (ASR streaming)   (Energy-based VAD)      │
│          │               │               │                      │
│          ▼               ▼               │                      │
│   WAKE_DETECTED   RECEIVING_AUDIO       │                      │
│          │               │               │                      │
│          └───────┬───────┘               │                      │
│                  ▼                       │                      │
│          VoiceCommandRouter              │                      │
│          (12 local commands)             │                      │
│                  │                       │                      │
│                  ▼                       │                      │
│            ChatManager                   │                      │
│        ┌───────┴───────┐                 │                      │
│        ▼               ▼                 │                      │
│   Local LLM        Cloud LLM             │                      │
│  (llama.cpp)      (LiteLLM)              │                      │
│        │               │                 │                      │
│        └───────┬───────┘                 │                      │
│                ▼                         │                      │
│          SentenceAssembler               │                      │
│          (split on . ! ? \n)             │                      │
│                │                         │                      │
│                ▼                         │                      │
│          PiperSpeechSynthesizer           │                      │
│          (VITS-LJSpeech @ 22050Hz)       │                      │
│                │                         │                      │
│                ▼                         │                      │
│            AudioPlayer                   │                      │
│         (AudioTrack MODE_STREAM)         │                      │
│                │                         │                      │
│                ▼                         │                      │
│            [Speaker Output]              │                      │
│                ▲                         │                      │
│                │ VAD barge-in            │                      │
│                └─────────────────────────┘                      │
│                                                                 │
│  Foreground Service (notification #42)                           │
│  System Overlay (TYPE_APPLICATION_OVERLAY)                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Breakdown

### AudioRecorder

**Location:** `core/voice/src/main/java/io/androllm/core/voice/audio/AudioRecorder.kt`

- **Sample rate:** 16,000 Hz mono PCM 16-bit
- **Chunk size:** 3,200 samples (200 ms per chunk)
- **Output:** `Channel<FloatArray>` (unlimited capacity)
- **Thread:** Dedicated daemon thread named `"voice-capture"`
- **Normalization:** `shortValue / 32768.0f` → float range [-1.0, 1.0]
- **Audio source:** `VOICE_RECOGNITION` when noise suppression or echo cancellation is enabled; otherwise `MIC`
- **Failure handling:** Returns `false` from `start()` if `AudioRecord.getMinBufferSize()` fails or state != `STATE_INITIALIZED`

```kotlin
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val chunkSize: Int = 3200  // 200ms at 16kHz
) {
    private val channel = Channel<FloatArray>(Channel.UNLIMITED)
    private val audioRecord = AudioRecord(
        AudioSource.VOICE_RECOGNITION,
        sampleRate,
        ChannelFormat.MONO.value,
        AudioEncoding.PCM_16BIT,
        chunkSize * 2  // 2 bytes per sample
    )
    
    fun start(): Boolean
    fun stop()
    val chunks: Flow<FloatArray> get() = channel.stream
}
```

### AudioPlayer

**Location:** `core/voice/src/main/java/io/androllm/core/voice/audio/AudioPlayer.kt`

- **Playback:** `AudioTrack.MODE_STREAM`
- **Sample rate:** 22,050 Hz (Piper output rate)
- **Format:** Mono PCM 16-bit
- **Usage:** `AudioManager.STREAM_MUSIC`
- **Interruption:** `stopNow()` immediately halts playback for barge-in

```kotlin
class AudioPlayer {
    fun play(samples: FloatArray, sampleRate: Int = 22050): AudioTrack
    fun stopNow()  // Instant interrupt for barge-in
}
```

### Wake Word Engine

**Location:** `core/voice/src/main/java/io/androllm/core/voice/wakeword/`

| Class | Role |
|---|---|
| `WakeWordEngine` | Interface: `feed(chunk)`, `startSession(phrases)`, `ensureInitialized()` |
| `SherpaOnnxWakeWordEngine` | Implementation using sherpa-onnx `KeywordSpotter` |
| `OpenWakeWordEngine` | Hilt-bound public wrapper (delegates to SherpaOnnx) |

**Model details:**
- Model: `sherpa-onnx-kws-zipformer-zh-en-3M` (zipformer2 KWS)
- Files: `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`, `keywords.txt`
- Location: `app/src/main/assets/voice/kws/`
- Keywords: ARPABET phoneme sequences for "HEY ANDROID" and "OKAY ANDROID"
  ```
  HH EY1 AE1 N D R OY2 D @HEY_ANDROID
  OW2 K EY1 AE1 N D R OY2 D @OKAY_ANDROID
  ```

**Detection logic:**
1. Feed each 200ms chunk to `spotter.feed(chunk)`
2. Run `decode()` until `isReady()` returns false
3. Check `result.keyword` — if non-blank, direct match
4. Token phoneme matching as fallback: tokens containing "HH", "EY", "AE", "OW", or "ANDR"
5. On detection: `s.reset(st)` clears the stream decoder state

**Note:** Despite the class name `OpenWakeWordEngine`, the current implementation uses sherpa-onnx KWS, not actual OpenWakeWord TFLite models. OpenWakeWord TFLite support is marked as future.

### Speech Recognizer (ASR)

**Location:** `core/voice/src/main/java/io/androllm/core/voice/asr/`

| Class | Role |
|---|---|
| `SpeechRecognizer` | Interface: `startSession()`, `feed(chunk)`, `finalText()` |
| `SherpaOnnxStreamingRecognizer` | Implementation using sherpa-onnx `OnlineRecognizer` |
| `SherpaRecognizer` | Hilt-bound wrapper |
| `GeminiStreamingRecognizer` | Cloud-based fallback (via Gemini API) |

**Model details:**
- Model: `sherpa-onnx-streaming-zipformer-en-20M` (int8 quantized)
- Files: `encoder.onnx`, `decoder.onnx`, `tokens.txt`
- Location: `app/src/main/assets/voice/asr/`
- Language: English
- Archive size: ~8 MB

**Endpoint configuration (3 rules for trailing silence):**
```kotlin
val endpointRules = listOf(
    EndpointRule(minTrailingSilence = 2.0f, minUtteranceLength = 0.3f, mustContainNonSilence = true),
    EndpointRule(minTrailingSilence = 1.0f, minUtteranceLength = 3.0f),
    EndpointRule(minTrailingSilence = 2.0f, minUtteranceLength = 5.0f)
)
```

**Usage pattern:**
```kotlin
recognizer.startSession()  // Creates new OnlineStream
while (!endpoint) {
    val partial = recognizer.feed(chunk)  // 200ms floats @ 16kHz
    controller.setPartialTranscript(partial)
}
val finalText = recognizer.finalText()
```

### Voice Activity Detection (VAD)

**Location:** `core/voice/src/main/java/io/androllm/core/voice/vad/`

| Class | Role |
|---|---|
| `Vad` | Energy-based VAD implementation |
| `SherpaVad` | Interface wrapper for DI |
| `VoiceActivityDetector` | Interface definition |

**Algorithm:**
```kotlin
class Vad {
    private var speechActive = false
    private var quietFrames = 0
    
    fun process(samples: FloatArray): Boolean {
        val energy = samples.map { it * it }.average()
        if (energy > 0.005f) {
            speechActive = true
            quietFrames = 0
        } else {
            quietFrames++
            if (quietFrames >= 4) speechActive = false  // hangover ≈ 0.8s
        }
        return speechActive
    }
}
```

- **Threshold:** 0.005 RMS (for 16kHz float audio)
- **Hangover:** 4 quiet frames (~0.8 seconds) to prevent flapping
- **Purpose:** Barge-in detection during TTS playback and LLM generation

### Text-to-Speech (Piper)

**Location:** `core/voice/src/main/java/io/androllm/core/voice/tts/`

| Class | Role |
|---|---|
| `OfflineTtsEngine` | Interface: `synthesize(text, speed)` |
| `SherpaOnnxOfflineTtsEngine` | Implementation using sherpa-onnx `OfflineTts` |
| `PiperSpeechSynthesizer` | Hilt-bound wrapper implementing both interfaces |
| `GeminiOfflineTtsEngine` | Cloud-based fallback (not currently used) |

**Model details:**
- Model: VITS-LJSpeech (`sherpa-onnx-vits-ljs`)
- Files: `model.onnx`, `tokens.txt`, `lexicon.txt`
- Location: `app/src/main/assets/voice/tts/`
- Size: ~114 MB
- **Lazy loaded** — stays unloaded until first synthesis request, saving ~114 MB RAM during listening phase
- Output: Mono float PCM at 22,050 Hz sample rate

**Synthesis parameters:**
```kotlin
val config = OfflineTtsConfig(
    noiseScale = 0.667f,
    noiseScaleW = 0.8f,
    lengthScale = 1.0f,
    silenceScale = 0.8f
)
// Speed range: 0.5x to 2.0x
```

### Sentence Assembler

**Location:** `feature/voice/src/main/java/io/androllm/feature/voice/service/SentenceAssembler.kt`

Splits streaming LLM output into complete sentences so TTS can speak while generation continues:

```kotlin
object SentenceAssembler {
    fun addDelta(delta: String): List<String> {
        // Split on sentence boundaries: . ! ? newline
        // Return completed sentences; keep incomplete tail
    }
}
```

This enables **overlap**: TTS speaks sentence N while the LLM generates sentences N+1, N+2, etc.

---

## State Machine

**Location:** `feature/voice/src/main/java/io/androllm/feature/voice/VoiceAssistantController.kt`

### VoicePhase Enum

| Phase | Meaning | Transition To |
|---|---|---|
| `IDLE` | Assistant off or not started | `LISTENING` (when started) |
| `LISTENING` | Waiting for wake word | `WAKE_DETECTED` |
| `WAKE_DETECTED` | Wake word just fired | `RECEIVING_AUDIO` |
| `RECEIVING_AUDIO` | ASR capturing speech | `THINK` / `GENERATING` |
| `THINK` | Preparing to generate (local) | `GENERATING` |
| `GENERATING` | LLM producing tokens | `SPEAK` |
| `SPEAK` | TTS about to start | `SPEAKING` |
| `SPEAKING` | Audio playback active | `DONE` / `LISTENING` |
| `DONE` | Turn complete (non-continuous) | `LISTENING` (next cycle) |

### State Transitions in VoiceAssistantService

```
onStartCommand(ACTION_START)
  └─▶ startForegroundCompat()
      └─▶ startLoop()
            └─▶ runLoop()
                  ├─ [LISTENING] awaitWakeWord(rec)
                  │     └─▶ WAKE_DETECTED
                  ├─ [RECEIVING_AUDIO] awaitUtterance(rec)
                  │     └─▶ THINK / GENERATING
                  ├─ [SPEAK/SPEAKING] speakSentences(...)
                  │     ├─▶ VAD barge-in check → LISTENING
                  │     └─▶ DONE (if !continuousConversation)
                  └─▶ Loop back to LISTENING
```

---

## Command Router

**Location:** `feature/voice/src/main/java/io/androllm/feature/voice/commands/VoiceCommandRouter.kt`

Deterministic transcript-to-command matcher for 12 local commands that don't require LLM processing:

| Transcript Pattern | Command | Action |
|---|---|---|
| "mute" | MUTE | Toggle microphone |
| "unmute" | UNMUTE | Re-enable microphone |
| "stop speaking" / "quiet" | STOP_SPEAKING | Cancel TTS playback |
| "new chat" / "new conversation" | NEW_CHAT | Clear current conversation |
| "open settings" | OPEN_SETTINGS | Navigate to Settings |
| "open models" | OPEN_MODELS | Navigate to Models |
| "switch theme" | SWITCH_THEME | Cycle Light/Dark/System |
| "delete conversation" | DELETE_CONVERSATION | Remove current conversation |
| "summarize" / "summarize chat" | SUMMARIZE_CHAT | Generate conversation summary |
| "enable offline mode" | ENABLE_OFFLINE | Disable cloud providers |
| "disable offline mode" | DISABLE_OFFLINE | Re-enable cloud providers |
| "enable voice" | ENABLE_VOICE | Start voice assistant |
| "disable voice" | DISABLE_VOICE | Stop voice assistant |

Commands are matched via substring comparison against the final transcript. If no command matches, the transcript is routed to the LLM.

---

## Foreground Service

**Location:** `feature/voice/src/main/java/io/androllm/feature/voice/service/VoiceAssistantService.kt`

### Service Declaration

```xml
<service
    android:name=".service.VoiceAssistantService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

### Permissions Required

| Permission | Android Version | Purpose |
|---|---|---|
| `RECORD_AUDIO` | All | Microphone access |
| `FOREGROUND_SERVICE` | All | Run as foreground service |
| `FOREGROUND_SERVICE_MICROPHONE` | Android 14+ | Specific foreground service type |
| `POST_NOTIFICATIONS` | Android 13+ | Show persistent notification |
| `SYSTEM_ALERT_WINDOW` | All | Floating overlay UI (optional) |

### Notification

- **Notification ID:** 42
- **Channel ID:** `voice_assistant`
- **Title:** "Listening for 'Hey Andro'"
- **Action:** Disable button stops the service
- **Visibility:** Foreground service requirement on Android 14+

### Lifecycle

```
onCreate()
  └─▶ VoiceNotifications.ensureChannel()  [create notification channel]

onStartCommand(ACTION_START)
  └─▶ startForegroundCompat()
  └─▶ startLoop()
        └─▶ runLoop()  [main while loop]

onStartCommand(ACTION_STOP)
  └─▶ stopLoop()
  └─▶ stopForeground(STOP_FOREGROUND_REMOVE)
  └─▶ stopSelf()

onDestroy()
  └─▶ stopLoop()
  └─▶ scope.cancel()  [cancels all coroutines]
```

---

## Battery Optimization

### Challenges

Android background execution limits vary significantly by manufacturer:

| Manufacturer | Restriction Level | Voice Assistant Impact |
|---|---|---|
| Stock Android / Pixel | Minimal | Works reliably |
| Samsung | Moderate | May kill service after 10–30 min |
| Xiaomi / HyperOS | Aggressive | Requires whitelist in battery settings |
| Huawei / EMUI | Very aggressive | Background execution often blocked |
| OnePlus / OxygenOS | Moderate | Similar to Samsung |

### Mitigations

1. **Battery saver mode** in voice settings:
   - Single-threaded operation
   - Disables continuous conversation mode
   - Reduces CPU/GPU pressure
   
2. **Foreground service**: The persistent notification makes the service visible to the OS, reducing likelihood of aggressive killing

3. **User education**: The app should guide users to whitelist it in their device's battery optimization settings

🚧 **Planned:** Automatic detection of aggressive battery optimizers with a setup guide

---

## Testing the Voice Assistant

### Manual Test Checklist

- [ ] Wake word "Hey Andro" triggers transition from LISTENING to WAKE_DETECTED
- [ ] Speaking after wake word shows streaming transcript in overlay
- [ ] Silence endpoint stops recording and routes to LLM
- [ ] LLM response is displayed and spoken via TTS
- [ ] Saying something while TTS plays triggers barge-in
- [ ] Local commands ("mute", "new chat") work without LLM
- [ ] Service survives screen-off (on devices that allow it)
- [ ] Notification persists while service is running
- [ ] Toggling off in settings stops the service cleanly

### Diagnostics

Check these logs for voice issues:
- `Voice` tag — service lifecycle events
- `Engine` tag — any LLM routing decisions
- Android logcat for `AudioRecord` errors (permission denied, buffer too small)

---

## Voice + Tool Calling (Agent Integration)

The voice assistant is a full participant in the [AI Agent Platform](../agent/agent-platform.md).
Voice turns route through `ChatManager.sendMessageStream`, which shares the
same `ToolRunCoordinator`, executor, planner, and trace store as typed chat:

- **Multi-step spoken tasks** — "check the weather and text Mom if it will
  rain" runs the same plan → execute → re-plan workflow as chat.
- **Spoken confirmations** — high-risk actions (SMS, calls, email) are
  announced aloud (*"Do you want me to send the SMS to Mom? Say yes to
  confirm…"*) and the assistant listens for a yes/no reply. The spoken
  decision runs concurrently with the chat confirmation card — whichever
  answers first wins. A muted or broken voice surface abstains, leaving the
  card in charge. Controlled by **Settings → Automation → Voice
  confirmations**.
- **Tool status** — "Running 2 tool calls…" style status is surfaced in the
  overlay while tools execute.
- **Voice recorder tool** — `record_voice` captures audio on demand
  (`RECORD_AUDIO`, requested through the confirmation flow).
- **Normalized TTS** — every spoken reply is passed through the
  [text normalization pipeline](text-normalization.md) so numbers, dates,
  currencies and out-of-lexicon words are pronounced correctly.

---

## Planned Voice Features

| Feature | Status | Notes |
|---|---|---|
| Multi-language ASR (Chinese, Japanese, Korean) | 🚧 Planned | New sherpa-onnx model packages needed |
| Voice cloning TTS (Pocket, ZipVoice) | 🔮 Future | Additional ONNX models |
| Punctuation restoration | 🚧 Planned | Via sherpa-onnx punctuation module |
| Speaker diarization | 🔮 Future | Separate ONNX model; UI pending |
| Custom wake phrases | 🚧 Planned | Allow user-defined phoneme sequences |
| Streaming LLM responses to TTS mid-generation | ✅ Implemented | SentenceAssembler handles this |
| Spoken confirmations for tool actions | ✅ Implemented | Voice responder in ToolConfirmationManager |
| Text normalization for TTS output | ✅ Implemented | Stage-based pipeline; see text-normalization.md |
