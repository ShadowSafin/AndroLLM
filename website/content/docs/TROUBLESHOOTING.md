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

### Dependency Resolution Failures

**Symptoms:** Gradle can't resolve `litertlm-android` or `litert-android`

**Solution:**
1. Ensure `google()` is in `settings.gradle.kts` (`pluginManagement` and `dependencyResolutionManagement`)
2. Check network access to `maven.google.com`
3. Run `./gradlew --refresh-dependencies`

### Firebase Plugin Fails Without google-services.json

**Symptoms:** `File google-services.json is missing`

**Solution:** Place a valid `google-services.json` in `app/`, or comment out the Google Services plugin for local-only development.

---

## Runtime Issues

### GPU Delegate Fails / Slow Generation

**Symptoms:**
- Generation runs but is slow — the engine is on the CPU backend
- Log shows `AndroLLM-Engine` backend fallback events
- `recoveryCount` in diagnostics keeps rising

**Cause:** OpenCL driver issues, insufficient GPU memory, or a device without a working OpenCL implementation.

**Solution:**
1. Check Developer screen → Hardware Info for `backend` (`GPU` vs `CPU`), `gpuFree`, `gpuTotal`, `recoveryCount`
2. The engine automatically falls back to CPU — this is safe, just slower
3. Close other GPU-intensive apps to free memory
4. If the issue persists, the device's GPU driver may have a bug — try updating the OS

### Context Overflow ("Input token ids are too long")

**Symptoms:**
- Generation fails with an error mentioning `Input token ids are too long`
- Large conversations suddenly stop generating

**Cause:** The rendered prompt (system prompt + memories + tool advertisement + conversation) exceeds the model's context window from container metadata (e.g. 4096 for Qwen2.5-1.5B, 2048 for Qwen3-0.6B).

**Solution:**
1. Start a new conversation (Chat drawer → New conversation)
2. Use conversation summaries to compress history
3. The engine already caps the tool advertisement (4500-char cap for small Qwen families) — larger system prompts from memory context are the usual cause
4. Check the model's metadata context in the Models screen before long prompts

### Generation Produces Garbled Output

**Symptoms:**
- Generated text contains repetition loops or nonsense
- Output stops mid-word repeatedly

**Cause:** Numerical instability during decoding, often triggered by:
- Very high temperature values (> 2.0)
- Context length exceeding the model's metadata limit
- A corrupt or partial container file

**Solution:**
1. Check logs under `AndroLLM-Engine` for backend fallbacks or decode errors
2. Reduce temperature to 0.8–1.2 range
3. Respect the model's context limit (see above)
4. Re-download the model if the file may be corrupted (SHA-256 verified at download)
5. Try a different model from the catalog

### Model Fails to Load

**Symptoms:**
- "Failed to load model" error
- Log shows a `ModelCompatibilityException` or `ModelLoadException`
- Model shows as "Downloaded" but won't load

**Cause:**
- `.litertlm` container is corrupted or incomplete
- Container family/architecture is unsupported (`ModelCompatibilityException`)
- File is not actually a `.litertlm` container (e.g. a renamed GGUF)
- Insufficient RAM

**Solution:**
1. Verify the file passes `LiteRtValidator` (Models screen shows validation results) and `ModelInspector` metadata reading
2. Check supported families/architectures (see [MODEL_SUPPORT.md](MODEL_SUPPORT.md)) — families come from container metadata
3. Check available RAM vs. model requirements (see Model Catalog)
4. Re-download the model if the file is corrupted
5. Note: GGUF files are **inspection-only** — the app identifies them but cannot run them

### Insufficient RAM

**Symptoms:**
- Model fails to load with OOM error
- App crashes during model loading
- System kills the app (low-memory killer)

**Solution:**
1. Check device RAM in Settings → Developer Options → Device Info
2. Choose smaller catalog models (Qwen3-0.6B class for 2 GB, Qwen2.5-1.5B / Gemma 3 1B class for 3–4 GB)
3. Unload unused models: Models screen → tap unloaded model
4. Close other apps to free RAM
5. Restart the device if RAM is fragmented

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
- GPU/backend diagnostics
- Log export
- Benchmark tools
- Memory diagnostics

### Check App Logs
Settings → Developer Options → Logs & Diagnostics → Export. Share this file when reporting bugs. Key logcat tags: `AndroLLM-Engine` (engine), `ChatViewModel` (chat flow), `Voice` (voice pipeline).

### Force Stop and Restart
If the app behaves strangely, force stop it (Settings → Apps → AndroLLM → Force Stop) and relaunch. This clears any leaked runtime sessions.

### Reinstall
As a last resort, uninstall and reinstall. This clears all local data including models — you'll need to re-download them.