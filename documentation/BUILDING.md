# Building AndroLLM

Complete guide to building AndroLLM from source.

---

## Prerequisites

### Required Software

| Component | Minimum Version | Notes |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) | Latest stable preferred |
| JDK | 17 | Auto-managed by Gradle toolchain |
| Android SDK | Platform 36, Build-Tools 36.x | `compileSdk 36`, `targetSdk 36` |

**No NDK, CMake, or Vulkan SDK are required.** The inference engine (LiteRT-LM
+ LiteRT) ships as prebuilt AARs from Google Maven and is consumed as ordinary
Kotlin/Java dependencies. The only native build in the repo is the `:whisper`
module (whisper.cpp STT), whose NDK toolchain is bundled with the Android SDK.

### Recommended Hardware

- **RAM**: 16 GB minimum (8 GB may work but builds will be slow)
- **Storage**: 10 GB free (for SDK, Gradle cache, and model downloads)
- **CPU**: 4+ cores (build parallelization scales with core count)

---

## Environment Setup

### 1. Install Android Studio

Download from [android.studio.google.com](https://developer.android.com/studio).

During installation, ensure these components are selected:
- Android SDK Platform 36
- Android SDK Build-Tools 36.x
- Android SDK Command-line Tools
- Android NDK (Side by side) — only needed for the `:whisper` module

### 2. Configure local.properties

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

### 3. Firebase Configuration (Optional for Local Build)

For a full build including Firebase features, place your `google-services.json` in `app/`. A stub is not sufficient — the Firebase plugin will fail without it.

To build without Firebase (local development only), comment out the Google Services plugin in `build.gradle.kts`.

---

## Building

### Debug Build (Fastest Iteration)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

For a signed release APK, configure signing in `gradle.properties` or `local.properties`:

```properties
# gradle.properties
ANDROLLM_STORE_FILE=/path/to/keystore.jks
ANDROLLM_STORE_PASSWORD=your_store_password
ANDROLLM_KEY_ALIAS=your_key_alias
ANDROLLM_KEY_PASSWORD=your_key_password
```

⚠️ **Security warning**: Never commit keystore files or passwords to version control. Add `*.jks` and `*.keystore` to `.gitignore`.

### Engine-Only Build

To rebuild just the engine module (fastest iteration on engine code):

```bash
./gradlew :engine:build
```

### Device Requirements

The APK targets **arm64-v8a only**. There is no x86_64 build — use a real
arm64 device (or an arm64 emulator image) for testing.

---

## Build Variants

| Variant | Minify | Debuggable | Use Case |
|---|---|---|---|
| `debug` | No | Yes | Development, testing |
| `release` | No | No | Production, distribution |

Note: R8/ProGuard shrinking is **disabled** in this project (`isMinifyEnabled = false` in all modules). This means:
- Faster builds (no desugaring overhead)
- Larger APK size
- No obfuscation

If you want to enable R8 for production, set `isMinifyEnabled = true` in the respective `build.gradle.kts` files and add appropriate keep rules for the LiteRT-LM / LiteRT AARs.

---

## Common Build Issues

### Out of Memory During Build

**Symptom**: `Java heap space` or build daemon OOM

**Solution** — increase Gradle JVM heap in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=512m
```

### Firebase Plugin Fails

**Symptom**: `google-services.json` missing error during configuration phase

**Solution**: Add a real `google-services.json` to `app/`, or remove the Google Services plugin for local-only builds.

### First Build Slow

**Symptom**: Long initial build while resolving dependencies

**Solution**: Normal — LiteRT-LM (`litertlm-android:0.16.0`) and LiteRT (`litert:2.2.0`) AARs plus the Gradle dependency graph must be downloaded once. Subsequent builds are incremental.

---

## Gradle Tasks Reference

| Task | Description |
|---|---|
| `assembleDebug` | Build debug APK |
| `assembleRelease` | Build release APK |
| `test` | Run all JVM unit tests |
| `connectedAndroidTest` | Run instrumented tests on connected device |
| `spotlessCheck` | Check code formatting |
| `spotlessApply` | Fix code formatting |
| `detekt` | Run static analysis |
| `:engine:build` | Build the engine module (unit tests + AAR) |
| `downloadVoiceModels` | Redownload voice ONNX models |
| `dependencies` | Print dependency tree |
| `app:lint` | Run Android lint checks |
| `app:lintVitalRelease` | Run vital lint for release builds |

---

## Clean Build

If you encounter persistent build issues:

```bash
# Stop all Gradle daemons
./gradlew --stop

# Remove build outputs
./gradlew clean

# Invalidate caches in Android Studio
# File → Invalidate Caches → Invalidate and Restart

# Fresh build
./gradlew assembleDebug
```

---

## CI/CD

This project currently has **no automated CI/CD pipeline**. Builds are performed locally. To set up CI:

1. Create a GitHub Actions workflow in `.github/workflows/build.yml`
2. Use `actions/setup-java@v4` with Java 17
3. Cache the Gradle installation and dependencies
4. Run `./gradlew assembleDebug spotlessCheck detekt`

Example workflow skeleton:
```yaml
name: Build
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin
      - run: ./gradlew assembleDebug spotlessCheck
```

See [TESTING.md](TESTING.md) for testing guidance.