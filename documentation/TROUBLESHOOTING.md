# Troubleshooting Guide

Common issues and their solutions in AndroLLM.

---

## Build Issues

### Gradle Daemon OOM

**Symptoms:** Build fails with `Java heap space` or `Out of memory`

**Solution:** Increase Gradle JVM heap in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
org.gradle.caching=true
```

---

### Firebase Plugin Fails During Sync

**Symptoms:** Build fails with a `google-services.json` missing error

**Solution:** Add a real `google-services.json` to `app/`, or remove the Google Services plugin for local-only builds.

---

### First Build Is Very Slow

**Symptoms:** Long initial build, Gradle downloads for minutes

**Solution:** Normal — the LiteRT-LM (`litertlm-android:0.16.0`) and LiteRT (`litert:2.2.0`) AARs plus the full dependency graph are downloaded once. Subsequent builds are incremental.

---

## Runtime Issues

### Model Fails to Load

**Symptoms:**
- "Failed to load model" error
- Model shows as "Downloaded" but won't load
- `LiteRtValidator` rejects the file

**Cause:**
- `.litertlm` file is corrupted or truncated (bad download)
- The file is not actually a LiteRT container (e.g. a renamed GGUF or safetensors file)
- The embedded `LlmMetadata` proto is unreadable or references an unknown family
- Insufficient RAM (the `ModelResourceGuard` refuses loads that exceed available RAM)

**Solution:**
1. Re-download the model from the catalog or the `litert-community` HuggingFace repo
2. Verify the file extension and size match the catalog entry
3. Check available RAM vs. model requirements (see Model Catalog)
4. Check logs (tag `AndroLLM-Engine`) for the rejection reason — `LiteRtValidator` logs the exact failure

> **Note:** GGUF files can be imported for metadata **inspection only** — the
> LiteRT runtime cannot execute them, and load is rejected.

---

### Hardware Acceleration Not Working / Falls Back to CPU

**Symptoms:**
- `backend=cpu` in Developer Diagnostics even though the device has a GPU/NPU
- Logs show GPU/NPU delegate initialization failure

**Cause:**
- The device's OpenCL drivers are old or buggy (GPU)
- The vendor dispatch library is missing (NPU)
- The delegate is unsupported on this SoC/OS combination
- GPU/NPU memory is insufficient for the model
- `EngineCrashGuard` auto-disabled the backend after 3 consecutive failures

**Solution:**
1. Check Developer screen → Hardware Info: `backend` should read `GPU` or `NPU`
2. Update the device OS/drivers if available
3. Prefer smaller models (Gemma 3 1B, Qwen3 0.6B) which fit memory more easily
4. CPU inference is fully supported — a CPU fallback is not an error
5. If a backend keeps failing, `EngineCrashGuard` will auto-skip it after 3 failures

---

### Corruption Recovery Triggers Repeatedly

**Symptoms:**
- `recoveryCount=N` climbs in the logs
- Output occasionally restarts mid-response

**Cause:** GPU delegate instability (drivers) or corrupted model file.

**Solution:**
1. If `recoveryCount` climbs while `backend=gpu`, switch to CPU (EngineConfig backend override in Developer settings)
2. Re-download the model — corrupted weights can produce garbage that the coherence probe catches
3. Close other GPU-intensive apps

---

### Empty or Garbled Output on Small Qwen Models

**Symptoms:**
- Qwen2.5-1.5B / Qwen3-0.6B return empty or nonsense responses when the user enables many tools
- Response starts fine for short tool lists, degrades with longer ones

**Cause:** Small Qwen repacks overflow their context window when the tool
advertisement is too long (Qwen2.5-1.5B degrades between ~5.7K and ~9.4K chars
of advertisement; Qwen3-0.6B overflows its 2048-token real window with the
full ~2.3K-token list).

**Solution:**
1. The app caps the tool advertisement at **4500 chars** for these families automatically
2. Disable rarely-used tools (agent settings) to shrink the advertisement further
3. Use Gemma 4B+ models if you need the full tool list — they handle it fine

---

### Conversation Stops with "Input token ids are too long"

**Symptoms:**
- Generation fails with `INVALID_ARGUMENT: Input token ids are too long`
- Long chats suddenly stop producing output

**Cause:** The conversation filled the model's KV cache (context window).

**Solution:**
1. This is handled automatically — the engine trims the oldest turns and reseeds the conversation; send the message again
2. If it recurs, start a new conversation or use a model with a larger context (Gemma 4: 8192)
3. Enable conversation summarization to compress history

---

### Model Loads but Output Is Nonsense

**Symptoms:**
- Model loads fine but generates garbage
- Coherence probe (temperature-0 self-test) fails

**Cause:** Corrupted container file or unsupported quantized layout.

**Solution:**
1. Re-download the model
2. Try a different model (e.g. Qwen3 0.6B Mixed Int4 is the most reliable small model)
3. Check that the container matches the family it claims (metadata vs. output)

---

### App Killed by Low Memory

**Symptoms:**
- App disappears from recents while a model is loaded
- `ActivityManager: Killing ... (low memory)` in logcat

**Solution:**
1. Choose smaller models (see catalog RAM guidance)
2. Unload unused models: Models screen → Unload
3. Close other apps before loading large models
4. Restart the device if RAM is fragmented

**RAM guidelines for bundled catalog models:**

| Model | Approx. Download | Min Available RAM |
|---|---|---|
| Qwen3 0.6B Mixed Int4 | ~475 MB | 2 GB |
| Gemma 3 1B Q4 | ~557 MB | 2 GB |
| Qwen2.5 1.5B Q8 | ~1.5 GB | 3 GB |
| DeepSeek R1 Distill 1.5B Q8 | ~1.7 GB | 3.5 GB |
| Gemma 4 E2B | ~2.4 GB | 4 GB |
| Gemma 4 E4B | ~3.4 GB | 6 GB |

---

### Model Download Fails or Hangs

**Symptoms:**
- Download progress stalls at a percentage
- "Download failed" toast

**Solution:**
1. Check network connectivity and storage space (each model needs 475 MB – 3.5 GB free)
2. Retry — downloads are resumable
3. Verify the `litert-community` repo is reachable (HuggingFace / ModelScope)
4. Free space if the download completed but the file is truncated — re-download

---

### Network / Cloud Provider Issues

**Symptoms:**
- "Provider unavailable" or "Connection refused"
- Streaming hangs indefinitely
- 401/403 authentication errors

**Solutions:**
1. Check internet connectivity
2. Verify API key is correct (re-enter in provider settings)
3. Check provider status page (e.g., [status.ai.google](https://status.ai.google/))
4. Test provider health manually in Settings → Cloud Providers → Health Check
5. Check firewall/proxy settings — some networks block AI API endpoints
6. Verify the base URL is correct (must end with `/v1` for LiteLLM compatibility)

---

### Firebase Authentication Failures

**Symptoms:**
- "Sign in with Google failed"
- "SHA-256 certificate fingerprint missing"
- OAuth popup closes immediately

**Solutions:**
1. **SHA fingerprint issue**: All signing keys must be registered in the Firebase Console — **especially the Play App Signing key if you upload AABs**:
   ```bash
   # Debug keystore
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android -rfc
   ```
   **⚠️ AAB via Google Play?** Get the Play signing SHA-256 from **Google Play Console → Setup → App Integrity → App signing key certificate** and add it to Firebase too. Missing this is the #1 cause of "auth works in APK but not in Play Store".
   Add each hash to Firebase Console → Project Settings → Your Apps → Add Fingerprint
2. **Google account not configured**: Add a Google account to the device first
3. **Network error**: Ensure the device can reach `accounts.google.com` and `firebase.google.com`
4. **GitHub OAuth**: Ensure the OAuth app is configured with the correct callback URL

---

### Microphone Permission Denied

**Symptoms:**
- Voice assistant notification shows but no audio captured
- "Microphone permission required" toast

**Solution:**
1. Go to Android Settings → Apps → AndroLLM → Permissions
2. Enable Microphone permission
3. Restart the voice assistant from Settings

On Android 13+, you also need to grant Notification permission for the foreground service notification.

---

### Wake Word Not Detected

**Symptoms:**
- Saying "Hey Andro" produces no response
- Overlay stays in LISTENING phase indefinitely

**Solutions:**
1. Speak clearly, 1–2 meters from the device
2. Reduce background noise
3. Increase wake word sensitivity in Settings → Voice Assistant
4. Try alternative phrases: "Okay Andro", "Andro"
5. Check that the KWS model assets exist: `app/src/main/assets/voice/kws/`
6. Battery saver mode disables continuous wake word listening — disable it temporarily
7. On some devices, background execution limits prevent the service from running — whitelist AndroLLM in battery settings

---

### TTS Not Speaking

**Symptoms:**
- Text appears but no voice output
- "TTS error" in overlay

**Solutions:**
1. Check device volume is not muted
2. Check media volume (not ringtone volume) — TTS uses `AudioManager.STREAM_MUSIC`
3. The TTS model is lazily loaded (~114 MB) — wait a few seconds after first activation
4. Check `app/src/main/assets/voice/tts/` has the model files
5. On some devices, text-to-speech engine conflicts exist — try a different TTS engine in Android settings

---

### Overlay Not Showing

**Symptoms:**
- Voice assistant runs (notification visible) but no floating window
- "Draw over other apps" permission denied

**Solution:**
1. Go to Android Settings → Apps → AndroLLM → Display over other apps
2. Enable "Allow display over other apps"
3. The voice service still functions without the overlay (notification-only mode)

---

### Memory Extraction Not Working

**Symptoms:**
- No memories appear after conversations
- Embedding model fails to load

**Solutions:**
1. Check that the local embedding model (EmbeddingGemma 300M) is downloaded — memory falls back to keyword matching without it
2. Enable memory in Settings → On-device Memory
3. Memories are extracted from exchanges with the local model or a configured cloud provider
4. Check `Memory` log tag for extraction errors

---

## Database Issues

### Database Migration Failed

**Symptoms:**
- App crashes on launch with `IllegalStateException: Encountered a migration problem`
- Log shows Room migration error

**Solution:**
1. Clear app data: Settings → Apps → AndroLLM → Clear Data
2. This resets the database to version 5 fresh
3. ⚠️ This deletes all conversations, memories, and settings

**Prevention:** Always write migration tests when increasing the database version.

---

## General Tips

### Enable Developer Mode
Settings → Developer Options → Enable. This unlocks:
- Backend and GPU diagnostics (`backend`, `gpuFree`, `gpuTotal`, `recoveryCount`)
- Log export
- Benchmark tools
- Memory diagnostics

### Check App Logs
Settings → Developer Options → Logs & Diagnostics → Export. Share this file when reporting bugs. Engine logs use the `AndroLLM-Engine` tag.

### Force Stop and Restart
If the app behaves strangely, force stop it (Settings → Apps → AndroLLM → Force Stop) and relaunch. This clears any leaked engine sessions.

### Reinstall
As a last resort, uninstall and reinstall. This clears all local data including models — you'll need to re-download them.