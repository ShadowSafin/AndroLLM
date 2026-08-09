# Building AndroLLM

Complete guide to building AndroLLM from source.

---

## Prerequisites

### Required Software

| Component | Minimum Version | Notes |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) | Latest stable preferred |
| JDK | 17 | Auto-managed by Gradle toolchain |
| Android SDK | API 34 | `compileSdk 34` |
| Android NDK | r26 (26.1.10909125) | Required for native engine build |
| CMake | 3.22.1+ | Bundled with Android Studio |
| Vulkan SDK | Latest stable | Required for host-side GLSL shader compilation |

### Recommended Hardware

- **RAM**: 16 GB minimum (8 GB may work but builds will be slow)
- **Storage**: 10 GB free (for SDK, NDK, build cache, and model downloads)
- **CPU**: 4+ cores (build parallelization scales with core count)

---

## Environment Setup

### 1. Install Android Studio

Download from [android.studio.google.com](https://developer.android.com/studio).

During installation, ensure these components are selected:
- Android SDK Platform 34
- Android SDK Build-Tools 34.x
- Android SDK Command-line Tools
- Android NDK (Side by side) → select version 26.1.10909125

### 2. Install Vulkan SDK

Download from [LunarG](https://vulkan.lunarg.com/sdk/home#windows).

After installation, set the environment variable:

**Windows:**
```batch
set VULKAN_SDK=C:\Lib\vulkan\xxxx\x64
```

**Linux/macOS:**
```bash
export VULKAN_SDK=$HOME/VulkanSDK/1.3.xxx/x86_64
```

### 3. Configure local.properties

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
ndk.dir=/path/to/Android/Sdk/ndk/26.1.10909125
```

The NDK path must point to the exact version 26.1.10909125.

### 4. Firebase Configuration (Optional for Local Build)

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

To rebuild just the native library (fastest for native code changes):

```bash
./gradlew :engine:build
```

### Emulator Support (x86_64 ABI)

The default build targets `arm64-v8a` only. For emulator testing:

```bash
./gradlew :engine:build \
  -PandrollmAbis=arm64-v8a,x86_64
```

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

If you want to enable R8 for production, set `isMinifyEnabled = true` in the respective `build.gradle.kts` files and add appropriate keep rules.

---

## Common Build Issues

### Vulkan Shader Compilation Fails

**Symptom**: `Vulkan is enabled but the host shader compiler (glslc) was not found`

**Solution**:
1. Install the Vulkan SDK from [LunarG](https://vulkan.lunarg.com/)
2. Verify `glslc` is in `%VULKAN_SDK%\Bin` (Windows) or `$VULKAN_SDK/Bin` (Linux/macOS)
3. Ensure `VULKAN_SDK` environment variable is set before running Gradle
4. If building on a headless CI server, install the Vulkan SDK headers-only package

### NDK Version Mismatch

**Symptom**: `NDK version mismatch: expected 26.1.10909125 but found 25.x.x`

**Solution**:
```bash
# List installed NDK versions
$ANDROID_HOME/ndk-toolchain/bin/clang --version

# Install the correct version via SDK Manager
sdkmanager "ndk;26.1.10909125"
```

### Out of Memory During Build

**Symptom**: `Java heap space` or build daemon OOM

**Solution** — increase Gradle JVM heap in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=512m
```

### LLVM/Host Compiler Not Found (Windows)

**Symptom**: `No host C/C++ compiler found for the Vulkan shader generator`

**Solution**: Install a host toolchain. Options:
- **MSVC** (comes with Visual Studio Build Tools)
- **MinGW-w64** (GCC/Clang for Windows)
- Ensure the compiler is on PATH before Android Studio's NDK clang

```bash
# Verify compilers are accessible
gcc --version
g++ --version
```

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
| `:engine:build` | Rebuild native library only |
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
4. Set `VULKAN_SDK` in the workflow environment
5. Run `./gradlew assembleDebug spotlessCheck detekt`

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
      - run: echo "VULKAN_SDK=$GITHUB_WORKSPACE/vulkan-sdk" >> $GITHUB_ENV
      - run: ./gradlew assembleDebug spotlessCheck
```

See [TESTING.md](TESTING.md) for testing guidance.
