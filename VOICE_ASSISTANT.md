# Voice Assistant Quick Guide

Quick reference for the offline voice assistant feature.

---

## Activation

Say **"Hey Andro"** or **"Okay Andro"** to wake the assistant.

### Enable Voice Assistant
1. Open Settings → Voice Assistant
2. Toggle "Enable voice assistant"
3. Grant microphone permission when prompted
4. (Optional) Grant overlay permission for floating UI

### Controls While Active

| Say | Action |
|---|---|
| "mute" / "unmute" | Toggle microphone |
| "stop speaking" | Interrupt TTS playback |
| "new chat" | Start fresh conversation |
| "open settings" | Navigate to settings |
| "open models" | Navigate to models |
| "switch theme" | Cycle light/dark |
| "delete conversation" | Remove current chat |
| "summarize chat" | Generate summary |

---

## How It Works

```
"Hey Andro" → Wake word detected → "Yes?" → [you speak]
                                              ↓
                                    Speech recognized
                                              ↓
                                    Sent to LLM (local or cloud)
                                              ↓
                                    Response spoken aloud
```

**All processing is offline.** No audio leaves your device.

---

## Technical Details

- **Wake word**: "Hey Andro", "Okay Andro" (sherpa-onnx KWS, ~3MB)
- **ASR**: English streaming (sherpa-onnx zipformer-en-20M, ~8MB)
- **TTS**: English female voice (Piper VITS-LJS, ~114MB, lazy-loaded)
- **Sample rate**: 16kHz mono recording, 22050Hz TTS output
- **Barge-in**: Yes — say something while the assistant speaks to interrupt

---

## Troubleshooting

| Problem | Solution |
|---|---|
| Wake word not detected | Speak closer, reduce background noise, increase sensitivity |
| No response after wake word | Check mic permission; try "Okay Andro" variant |
| TTS not speaking | Check media volume; wait for model load (~3 sec first time) |
| Service stops unexpectedly | Disable battery optimization for AndroLLM in Android settings |
| Barge-in doesn't work | Ensure VAD threshold is appropriate; speak clearly during playback |

---

## See Also

- [Voice Assistant Architecture](docs/voice/voice-assistant.md) — Full technical deep dive
- [README](README.md) — Feature overview
