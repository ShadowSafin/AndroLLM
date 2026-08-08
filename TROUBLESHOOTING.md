# Troubleshooting Guide

Common issues and their solutions in AndroLLM.

---

## Build Issues

### Vulkan SDK Not Found

**Symptoms:**
```
Vulkan is enabled but the host shader compiler (glslc) was not found.
Install the Vulkan SDK, set VULKAN_SDK, or configure with -DANDROLLM_VULKAN=OFF.
```

**Cause:** The Vulkan SDK is not installed or `VULKAN_SDK` environment variable is not set.

**Solution:**
1. Download and install the Vulkan SDK from [LunarG](https://vulkan.lunarg.com/sdk/home)
2. Set the environment variable:
   - **Windows**: `set VULKAN_SDK=C:\Lib\vulkan\1.3.xxx\x64`
   - **Linux**: `export VULKAN_SDK=$HOME/VulkanSDK/1.3.xxx/x86_64`
3. Restart Android Studio
4. Rebuild

**Alternative:** If you don't need Vulkan for development:
```bash
./gradlew :engine:build -PandrollmVulkan=OFF
```
This produces a CPU-only build (not recommended for production).

---

### NDK Version Mismatch

**Symptoms:**
```
NDK manifest version mismatch: expected 26.1.10909125 but found 25.2.9519653
```

**Cause:** The installed NDK version doesn't match what the project expects.

**Solution:**
```bash
# Install the correct NDK version
$ANDROID_HOME/tools/bin/sdkmanager "ndk;26.1.10909125"

# Verify installation
ls $ANDROID_HOME/ndk/
```

If multiple NDK versions are installed, ensure `local.properties` points to the correct one:
```properties
ndk.dir=/path/to/Android/Sdk/ndk/26.1.10909125
```

---

### Host Compiler Not Found (Windows)

**Symptoms:**
```
No host C/C++ compiler found for the Vulkan shader generator.
Install a host toolchain (MSVC, GCC or LLVM-MinGW) and put it on PATH.
```

**Cause:** The Vulkan shader generator needs a native host compiler (not the cross-compiler).

**Solution:**
1. Install MSVC via Visual Studio Build Tools (select "C++ build tools")
2. Or install MinGW-w64 and add to PATH
3. Verify:
   ```bash
   gcc --version
   g++ --version
   ```
4. Ensure the compiler is on PATH **before** Android Studio's NDK clang

---

### Gradle Daemon OOM

**Symptoms:** Build fails with `Java heap space` or `Out of memory`

**Solution:** Increase Gradle JVM heap in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
org.gradle.caching=true
```

---

## Runtime Issues

### Vulkan Device Lost

**Symptoms:**
- Generation starts normally then crashes with `VK_ERROR_DEVICE_LOST`
- App log shows: `[VulkanDiag] devLostRecovered=N`
- Model stops responding mid-generation

**Cause:** GPU driver crash, thermal throttling, or insufficient VRAM on the device.

**Solution:**
1. The engine should automatically recover by recreating the context
2. If recovery fails, it falls back to CPU (check logs for `cpuSessionFallback=true`)
3. Reduce context length in Model Parameters sheet
4. Close other GPU-intensive apps
5. If the issue persists, the device's GPU driver may have a bug — try updating the OS

**Diagnostics:** Check Settings → Developer Options → Vulkan Diagnostics for `backend`, `gpuFree`, `gpuTotal`, `recovery=N`, `devLostRecovered=M`.

---

### NaN / INF Logits

**Symptoms:**
- Generated text contains garbled characters or repetition loops
- Log shows: `corruption detected: nan logits` or `invalid token`
- Generation stops unexpectedly

**Cause:** Numerical instability during decoding, often triggered by:
- Very high temperature values (> 2.0)
- Extremely low top-k values (1–3) with certain model architectures
- Context length exceeding model's training limit
- Model file corruption

**Solution:**
1. The engine attempts automatic recovery (context recreation → CPU fallback)
2. Check logs for `recoveryCount=N` — if > 3, the model may be incompatible
3. Reduce temperature to 0.8–1.2 range
4. Increase top-k to 40–50
5. Verify the GGUF file integrity (re-download if necessary)
6. Use a different quantization (Q5_K_M or Q8_0 are more numerically stable than IQ1_IQ)

**Prevention:** Always validate GGUF files before loading using the built-in validator.

---

### Second-Prompt Corruption

**Symptoms:**
- First prompt generates correctly
- Second prompt produces garbled or nonsensical output
- KV cache position appears incorrect in debug logs

**Cause:** Context shift boundary issue — the diff-based multi-turn continuation doesn't properly handle the transition when the context is nearly full.

**Solution:**
1. The engine should auto-trigger a full re-render when `pos_check >= nCtx - 4`
2. If this doesn't happen, manually reset the conversation (Chat drawer → New conversation)
3. Reduce context length setting for the model
4. Enable automatic conversation summarization (planned feature)

**Diagnostics:** Check `EngineDebugInfo` in developer screen for `chatPosition`, `nCtx`, `nLoaded`.

---

### Model Fails to Load

**Symptoms:**
- "Failed to load model" error
- Log shows: `ggml_load: unknown architecture` or `invalid magic`
- Model shows as "Downloaded" but won't load

**Cause:**
- GGUF file is corrupted or incomplete
- Model architecture is not supported by the vendored llama.cpp
- File is not actually a GGUF (wrong extension)
- Insufficient RAM

**Solution:**
1. Verify the file is a valid GGUF:
   ```bash
   # Check magic bytes: should be 0x46554747 ("GGUF")
   xxd -l 16 /path/to/model.gguf
   ```
2. Check supported architectures in `SupportedArchitectures.kt` (137 architectures)
3. Check available RAM vs. model requirements (see Model Catalog)
4. Re-download the model if the file is corrupted
5. Try a different quantization level (heavier quants like Q8_0 may need more RAM)

---

### Insufficient RAM

**Symptoms:**
- Model fails to load with OOM error
- App crashes during model loading
- System kills the app (low-memory killer)

**Solution:**
1. Check device RAM in Settings → Developer Options → Device Info
2. Choose smaller models (fewer parameters, lighter quantization)
3. Unload unused models: Models screen → tap unloaded model
4. Close other apps to free RAM
5. Restart the device if RAM is fragmented

**RAM guidelines by model size (quantization Q4_K_M):**

| Parameters | Approx. RAM | Min Device RAM |
|---|---|---|
| 0.5B | ~0.4 GB | 2 GB |
| 1.5B | ~1.0 GB | 3 GB |
| 3B | ~2.0 GB | 4 GB |
| 7B | ~4.5 GB | 6 GB |
| 14B | ~9.0 GB | 12 GB |

These are estimates. Actual usage varies by architecture and context length.

---

### Unsupported GGUF File Type

**Symptoms:**
- "Unsupported format" error when selecting a model file
- Catalog shows format as "SAFETENSORS" or "PYTORCH"

**Solution:** Convert the model to GGUF format using llama.cpp's convert scripts:
```bash
python llama.cpp/convert.py /path/to/model --outtype gq4_K_M
```
Or download a pre-converted GGUF from HuggingFace.

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
1. **SHA fingerprint issue**: The debug keystore SHA-256 must be registered in the Firebase Console
   ```bash
   # Get debug keystore SHA-256
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android -rfc
   ```
   Add the hash to Firebase Console → Project Settings → Your Apps → Add Fingerprint
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
- GPU rendering debugging
- Log export
- Benchmark tools
- Memory diagnostics

### Check App Logs
Settings → Developer Options → Logs & Diagnostics → Export. Share this file when reporting bugs.

### Force Stop and Restart
If the app behaves strangely, force stop it (Settings → Apps → AndroLLM → Force Stop) and relaunch. This clears any leaked native handles.

### Reinstall
As a last resort, uninstall and reinstall. This clears all local data including models — you'll need to re-download them.
