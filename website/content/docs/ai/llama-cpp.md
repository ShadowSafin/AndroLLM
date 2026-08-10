# llama.cpp Integration Guide

Technical guide to how AndroLLM integrates with the llama.cpp inference engine.

---

## Overview

AndroLLM vendors a **stock (unpatched) copy of llama.cpp** and builds it into a single shared library `libandrollm_llama.so` using the Android NDK. A JNI bridge exposes model loading, token generation, chat templating, and diagnostics to the Kotlin application layer.

---

## Native Build Configuration

**CMakeLists.txt** at [`engine/src/main/cpp/CMakeLists.txt`](../../engine/src/main/cpp/CMakeLists.txt):

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(androllm_llama CXX)
set(CMAKE_CXX_STANDARD 17)

# Vendored llama.cpp (stock, never patched)
set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# Build settings
set(BUILD_SHARED_LIBS OFF)              # Static link into our .so
set(LLAMA_BUILD_COMMON   ON)            # Include chat templates, sampling, args
set(LLAMA_BUILD_EXAMPLES OFF)           # Strip examples
set(LLAMA_BUILD_SERVER   OFF)
set(LLAMA_BUILD_TESTS    OFF)
set(LLAMA_BUILD_TOOLS    OFF)
set(LLAMA_BUILD_MTMD     OFF)           # No multi-modal
set(GGML_OPENMP          OFF)           # No OpenMP (NDK incompatibility)
set(GGML_LLAMAFILE       OFF)           # No llamafile support
set(GGML_NATIVE          OFF)           # No native CPU dispatch
set(GGML_CPU_ALL_VARIANTS OFF)

# ARM64 KleidiAI microkernels (Snapdragon optimization)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON)
endif()

# Vulkan backend (enabled by default, requires host Vulkan SDK)
option(ANDROLLM_VULKAN "Build the ggml Vulkan backend" ON)
if(ANDROLLM_VULKAN)
    find_program(ANDROLLM_GLSLC_EXECUTABLE NAMES glslc glslc.exe
        HINTS "$ENV{VULKAN_SDK}/Bin" "$ENV{VULKAN_SDK}/bin")
    # ... SPIRV-Headers and host toolchain setup
    set(GGML_VULKAN ON)
endif()

# JNI bridge
add_library(androllm_llama SHARED native_api.cpp)
target_compile_options(androllm_llama PRIVATE -O3 -fno-finite-math-only)
target_link_libraries(androllm_llama PRIVATE llama llama-common android log)
```

**Output ABI:** `arm64-v8a` by default. Override with `-PandrollmAbis=arm64-v8a,x86_64` for emulator testing.

---

## JNI Bridge Design

**File:** [`engine/src/main/java/io/androllm/engine/jni/LlamaJniBridge.kt`](../../engine/src/main/java/io/androllm/engine/jni/LlamaJniBridge.kt)

### Handle-Based Architecture

Every native function takes a `Long` handle representing a pointer to a `LlamaEngine` struct:

```kotlin
object LlamaJniBridge {
    private const val LIBRARY_NAME = "androllm_llama"
    
    fun interface TokenCallback {
        fun onToken(delta: String, finished: Boolean)
    }
    
    // Lifecycle
    external fun nativeCreate(configJson: String): Long
    external fun nativeLoadModel(engineHandle: Long, modelPath: String, loadConfigJson: String)
    external fun nativeIsLoaded(engineHandle: Long): Boolean
    external fun nativeModelInfo(engineHandle: Long): String
    external fun nativeUnload(engineHandle: Long)
    external fun nativeRelease(engineHandle: Long)
    
    // Generation
    external fun nativeGenerate(
        handle: Long, prompt: String, cfgJson: String, cb: TokenCallback
    )
    external fun nativeGenerateChat(
        handle: Long, msgsJson: String, addAssistant: Boolean, cfgJson: String, cb: TokenCallback
    )
    external fun nativeCancel(handle: Long)
    
    // Chat template
    external fun nativeApplyChatTemplate(
        handle: Long, sysPrompt: String, userMsg: String, assistantPrefix: String
    ): String
    external fun nativeApplyChatTemplateEx(
        handle: Long, sysPrompt: String, userMsg: String, assistantPrefix: String,
        enableThinking: Boolean
    ): String  // Qwen2.5/Qwen3 thinking mode
    
    // Reset
    external fun nativeResetChat(handle: Long)
    
    // Diagnostics
    external fun nativeWarmUp(handle: Long): String
    external fun nativeGetMemoryStats(handle: Long): String
    external fun nativeBenchmark(handle: Long, iterations: Int, cb: TokenCallback): String
    external fun nativeMemoryPeak(handle: Long): Long
    external fun nativeVulkanAvailable(): Boolean
    
    // Embedding (separate handle)
    external fun nativeLoadEmbeddingModel(handle: Long, modelPath: String): String
    external fun nativeEmbeddingLoaded(handle: Long): Boolean
    external fun nativeEmbeddingDim(handle: Long): Int
    external fun nativeEmbed(handle: Long, texts: Array<String>): String
    external fun nativeUnloadEmbeddingModel(handle: Long)
}
```

### UTF-8 Encoding Handling

Java strings are UTF-16 encoded. To preserve emoji and CJK characters through the JNI boundary:

```cpp
// In native_api.cpp
std::wstring javaStrToWString(JNIEnv* env, jstring jstr) {
    const jchar* jchars = env->GetStringChars(jstr, nullptr);
    size_t len = env->GetStringLength(jstr);
    std::wstring wstr(jchars, jchars + len);
    env->ReleaseStringChars(jstr, jchars);
    // Convert wchar_t (UTF-16) → std::string (UTF-8)
    return utf16ToUtf8(wstr);
}
```

---

## Native LlamaEngine Structure

**File:** [`engine/src/main/cpp/native_api.cpp`](../../engine/src/main/cpp/native_api.cpp)

```cpp
struct LlamaEngine {
    // Model and context ownership
    common_init_result_ptr initResult;   // Holds llama_model*
    llama_context_ptr ctxOwner;          // Holds llama_context*
    common_sampler* sampler;             // Fresh per-request sampler
    
    // Chat state (persists across turns)
    common_chat_templates_ptr chatTmpls;
    std::vector<common_token> chatMsgs;
    size_t chatPosition = 0;
    size_t systemPromptEnd = 0;
    
    // Embedding model (separate handle)
    common_init_result_ptr embedInitResult;
    llama_model* embedModel = nullptr;
    llama_context* embedCtx = nullptr;
    
    // Corruption recovery counters
    int recoveryCount = 0;
    int cpuSessionFallback = 0;
    int vulkanDeviceLostRecoveries = 0;
    bool vulkanDeviceLost = false;
    
    // Diagnostic fields
    size_t gpuMemoryAllocatedBytes = 0;
    size_t gpuMemoryFreeBytes = 0;
    size_t gpuMemoryTotalBytes = 0;
    int gpuLayersUsed = 0;
    std::string backendReason;
    std::string vulkanValidationStatus;
};
```

---

## Context and Chat Lifecycle

### Multi-Turn Strategy: KV Cache as Conversation State

The engine maintains a single `llama_context` across all conversation turns. The **KV cache IS the conversation history**. This avoids the expensive full re-prefill on every turn.

#### Continuation (Normal New Message)

```
1. Render ONLY the new user message + assistant prefix with Jinja template
2. Tokenize the diff
3. Prefill-decode starting at chatPosition
4. Append tokens to chatMsgs
5. Advance chatPosition by newly_generated_tokens
6. Return accumulated output tokens
```

#### Full Re-Render (Special Cases)

Triggered when:
- First turn of conversation
- User edits/deletes a previous message
- User regenerates a response
- System prompt changes
- Conversation is reset

```
1. Reset chatPosition to 0
2. Render ALL messages with their Jinja templates
3. Prefill the entire sequence
4. Continue decoding from position 0
```

#### Context Shift (Memory Management)

When `pos_check >= nCtx - 4`:
```
1. Discard oldest tokens AFTER the system prompt
2. Shift remaining tokens left in the KV cache (in-place)
3. Adjust chatPosition accordingly
4. Continue decoding from the shifted position
```

This preserves the system prompt and recent conversation while dropping older turns.

---

## Generation Config Mapping

Kotlin `GenerationConfig` → C++ `common_sampler` parameters:

| Kotlin Field | C++ Parameter | Default | Description |
|---|---|---|---|
| `temperature` | `temp` | 0.8 | Sampling temperature |
| `topK` | `top_k` | 40 | Top-k sampling |
| `topP` | `top_p` | 0.9 | Nucleus sampling threshold |
| `minP` | `min_p` | 0.0 | Min-p sampling threshold |
| `typicalP` | `typical_p` | 1.0 | Locally typical sampling |
| `mirostat` | `mirostat` | 0 | Mirostat mode (0=off, 1=v1, 2=v2) |
| `mirostatTau` | `mirostat_tau` | 5.0 | Mirostat target entropy |
| `mirostatEta` | `mirostat_eta` | 0.1 | Mirostat learning rate |
| `dryMultiplier` | `dry_multiplier` | 0.0 | DRY repetition penalty multiplier |
| `dryBase` | `dry_base` | 1.75 | DRY repetition penalty base |
| `dryAllowedLength` | `dry_allowed_length` | 2 | DRY allowed length |
| `dryPenaltyLastN` | `dry_penalty_last_n` | 0 | DRY penalty span (0 = context length) |
| `seed` | `seed` | -1 | Random seed (-1 = random) |
| `grammar` | `grammar` | "" | EBNF grammar string |
| `stopSequences` | `scan_seqs` | [] | Stop sequence strings |

---

## Streaming Architecture

Token streaming uses Java `Callback` interface passed through JNI:

```kotlin
// Kotlin side
fun generateChatStream(...): Flow<Result<StreamChunk>> = flow {
    val callback = object : LlamaJniBridge.TokenCallback {
        override fun onToken(delta: String, finished: Boolean) {
            trySend(Result.success(StreamChunk(delta, finished)))
        }
    }
    // Native call is blocking — runs in background coroutine
    backgroundScope.launch {
        LlamaJniBridge.nativeGenerateChat(handle, msgsJson, addAssistant, cfgJson, callback)
    }
}
```

```cpp
// C++ side — called from llama.cpp decode loop
static void token_callback(void* user_data, const struct llama_token token, float /*score*/, const char* /*text*/, void* /*more*/) {
    auto* cb = reinterpret_cast<TokenCallback*>(user_data);
    std::string text = token_to_string(token);
    // Post to Java callback (requires JNIEnv from attached thread)
    JNIEnv* env = getJNIEnv();
    env->CallVoidMethod(cb->obj, cb->mid, jdelta, jfinished);
}
```

The UI throttles token display to ~60fps (16ms interval) to avoid O(n²) string copying in Compose.

---

## Cancel Mechanism

```kotlin
fun cancel(): Result<Unit> {
    LlamaJniBridge.nativeCancel(engineHandle)
    return Result.success(Unit)
}
```

```cpp
// Sets atomic flag checked in the decode loop
extern "C" JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeCancel(
    JNIEnv* env, jclass, jlong handle) {
    auto* eng = reinterpret_cast<LlamaEngine*>(handle);
    eng->cancelFlag.store(true, std::memory_order_relaxed);
}
```

The decode loop checks `cancelFlag` between batches and exits early if set.

---

## Model Loading and Validation

### GGUF Validation (Pre-Native)

Before calling native code, the Kotlin `GgufValidator` reads the GGUF header:

```kotlin
object GgufValidator {
    private const val GGUF_MAGIC = 0x46554747  // "GGUF"
    
    fun validateHeader(filePath: String): GgufValidationResult {
        // Read magic, version, tensor count, metadata
        // Extract: general.architecture, general.name, general.file_type
        //         <arch>.context_length, general.license
        // Validate: magic == GGUF_MAGIC, version in {2, 3}
        //            architecture in SupportedArchitectures
        //            file_type in valid ftype range (0-42)
    }
}
```

### Native Model Loading Steps

```cpp
// nativeLoadModel
1. Parse LoadConfig JSON (contextLength, batchSize, gpuLayers)
2. Store path/config for corruption recovery
3. call checkVulkan() → VulkanInfo
4. Build common_params (n_gpu_layers = -1 means all if Vulkan available)
5. If Vulkan unavailable: force n_gpu_layers = 0
6. Load model-only: common_init_from_params(params, model_only=true)
7. Create engine context: createEngineContext(eng)
   - Builds llama_context_params from stored load config
   - Creates fresh context with empty KV cache
   - Initializes chat templates from model metadata
8. Run GPU-vs-CPU correctness validation (if layers offloaded > 0)
9. Report vulkanValidationStatus
10. Update gpuMemoryAllocatedBytes from llama_model_size + llama_state_get_size
```

---

## Thread Safety

### Mutex Serialization

The `DefaultEngineRepository` adds a `Mutex` to serialize `generate` and `generateQuiet` calls:

```kotlin
@Singleton
class DefaultEngineRepository @Inject constructor(
    private val engine: InferenceEngine
) : EngineRepository {
    private val generationMutex = Mutex()
    
    override suspend fun generateChat(...) {
        generationMutex.withLock {
            engine.generateChat(...)
        }
    }
    
    override suspend fun generateQuiet(...) {
        generationMutex.withLock {
            engine.generateQuiet(...)
        }
    }
}
```

This prevents race conditions between:
- Interactive chat generation
- Background memory extraction (which also calls the engine)
- Concurrent benchmark calls

### Coroutine Threading

- JNI calls block the calling coroutine
- Streaming callbacks post to the calling thread via `JNIEnv` attachment
- The engine uses a dedicated backend thread for long-running operations
- **Never call JNI from the main thread during active generation** (the mutex prevents this)

---

## Embedding Model

A separate native handle is used for embedding models:

```kotlin
external fun nativeLoadEmbeddingModel(handle: Long, modelPath: String): String
external fun nativeEmbeddingLoaded(handle: Long): Boolean
external fun nativeEmbeddingDim(handle: Long): Int
external fun nativeEmbed(handle: Long, texts: Array<String>): String
external fun nativeUnloadEmbeddingModel(handle: Long)
```

The embedding model is typically a smaller GGUF file (e.g., `BAAI/bge-small-en-v1.5` converted to GGUF). It shares the same `libandrollm_llama.so` library but uses a separate `llama_context` instance.

---

## Performance Characteristics

| Operation | Typical Time (7B Q4, Vulkan) | Typical Time (7B Q4, CPU) |
|---|---|---|
| Model load | 8–15 sec | 10–20 sec |
| First token (prefill) | 200–500 ms | 500–1500 ms |
| Token generation | 25–60 ms/token | 100–300 ms/token |
| Context reset | 10–30 ms | 10–30 ms |
| Warm-up (shader compile) | 2–5 sec (one-time) | N/A |

Actual performance depends heavily on device hardware, model architecture, and quantization. Use the built-in benchmark tool for accurate measurements.

---

## Error Handling

| Error | Source | Handling |
|---|---|---|
| GGUF validation failure | `GgufValidator` | Kotlin-level error before native call |
| Model load failure | llama.cpp `llama_load_model_from_file` | Caught in JNI, returned as `Result.Failure` |
| Context creation failure | llama.cpp `llama_new_context_with_model` | Caught, returned as error |
| NaN/INF logits | Custom check in decode loop | Corruption recovery escalation |
| Invalid token | Token validation in decode loop | Same as above |
| Degenerate repetition | Repetition penalty check | Same as above |
| Vulkan device lost | `std::system_error` catch | Context recreation → CPU fallback |
| Out of memory | `std::bad_alloc` / OOM | Returned as error to caller |

---

## Testing the Engine

See [TESTING.md](../TESTING.md) for engine-specific test guidance. Key test classes:

- `EngineRepositoryTest` — Repository-level generate/fail tests
- `NoOpInferenceEngineTest` — Fallback engine for environments without native lib
- `DefaultEngineRepositoryStressTest` — Concurrent generation serialization
- `GgufValidatorTest` — Header parsing edge cases
- `MemoryEstimatorTest` — RAM prediction accuracy
