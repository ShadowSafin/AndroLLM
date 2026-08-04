// JNI bridge between AndroLLM Kotlin layer and llama.cpp.
//
// Design: This file is a THIN WRAPPER around official llama.cpp APIs.
// It implements NO inference logic. All sampling, tokenization,
// detokenization, chat-templating, and decode-loop logic is handled
// directly by llama.cpp's common library.
//
// Ownership model:
//   common_init_result_ptr  — owns llama_model + llama_context + internal samplers
//   LlamaEngine            — holds the init_result (RAII), plus a cloned
//                             common_sampler for per-request config overrides
//                             and common_chat_templates for chat formatting.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <fstream>
#include <iomanip>
#include <map>
#include <mutex>
#include <sched.h>
#include <unistd.h>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "json-schema-to-grammar.h"
#include <nlohmann/json.hpp>

#ifdef GGML_USE_VULKAN
#include <vulkan/vulkan.h>
#include "ggml-vulkan.h"
#endif

// ---------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------

#define TAG "androllm-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Thread affinity — pin to big cores for best prompt-eval throughput
// ---------------------------------------------------------------------------

static void pin_big_cores() {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 0) return;

    std::vector<std::pair<int,int>> freqs;
    freqs.reserve(n);
    for (int i = 0; i < n; ++i) {
        std::string p = "/sys/devices/system/cpu/cpu" + std::to_string(i)
                        + "/cpufreq/cpuinfo_max_freq";
        std::ifstream f(p);
        int mhz = 0;
        f >> mhz;
        freqs.push_back({mhz, i});
    }
    std::sort(freqs.rbegin(), freqs.rend());

    cpu_set_t cs;
    CPU_ZERO(&cs);
    int count = std::min(6, (int)freqs.size());
    for (int i = 0; i < count; ++i) CPU_SET(freqs[i].second, &cs);
    sched_setaffinity(0, sizeof(cs), &cs);
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

static JavaVM *g_jvm = nullptr;

jint JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static void throw_java(JNIEnv *env, const std::string &msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) { env->ThrowNew(cls, msg.c_str()); env->DeleteLocalRef(cls); }
}

// UTF-8 <-> UTF-16 conversion.
//
// llama.cpp works with raw UTF-8 byte strings, matching llama-cli output.
// JNI's NewStringUTF/GetStringUTFChars use Modified UTF-8, which corrupts
// NUL bytes and 4-byte UTF-8 characters (emoji, CJK ext-B). Convert via the
// standard UTF-16 jchar encoding instead so text round-trips byte-exactly.

static std::u16string utf8_to_utf16(const std::string &in) {
    std::u16string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ) {
        const unsigned char c = (unsigned char)in[i];
        uint32_t cp;
        size_t len;
        if (c < 0x80)       { cp = c & 0x7F;       len = 1; }
        else if (c < 0xE0)  { cp = c & 0x1F;       len = 2; }
        else if (c < 0xF0)  { cp = c & 0x0F;       len = 3; }
        else if (c < 0xF8)  { cp = c & 0x07;       len = 4; }
        else                { cp = 0xFFFD;         len = 1; }

        bool ok = i + len <= in.size();
        for (size_t j = 1; ok && j < len; j++) {
            if (((unsigned char)in[i + j] & 0xC0) != 0x80) ok = false;
            else cp = (cp << 6) | ((unsigned char)in[i + j] & 0x3F);
        }
        if (!ok || (len > 1 && cp < (len == 2 ? 0x80 : len == 3 ? 0x800 : 0x10000)) || cp > 0x10FFFF) {
            cp = 0xFFFD; len = 1;
        }

        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back((char16_t)(0xD800 + (cp >> 10)));
            out.push_back((char16_t)(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back((char16_t)cp);
        }
        i += len;
    }
    return out;
}

static std::string utf16_to_utf8(const char16_t *in, size_t n) {
    std::string out;
    out.reserve(n * 3);
    for (size_t i = 0; i < n; i++) {
        uint32_t cp = (uint32_t)in[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n &&
            in[i + 1] >= 0xDC00 && in[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + ((uint32_t)in[i + 1] - 0xDC00);
            i++;
        }
        if (cp < 0x80) {
            out.push_back((char)cp);
        } else if (cp < 0x800) {
            out.push_back((char)(0xC0 | (cp >> 6)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char)(0xE0 | (cp >> 12)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char)(0xF0 | (cp >> 18)));
            out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

static jstring to_jstring(JNIEnv *env, const std::string &s) {
    std::u16string u = utf8_to_utf16(s);
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), (jsize)u.size());
}

static std::string from_jstring(JNIEnv *env, jstring v) {
    if (!v) return "";
    const jchar *c = env->GetStringChars(v, nullptr);
    if (!c) return "";
    std::string r = utf16_to_utf8(reinterpret_cast<const char16_t *>(c), (size_t)env->GetStringLength(v));
    env->ReleaseStringChars(v, c);
    return r;
}

static std::string json_escape(const std::string &s) {
    std::ostringstream o;
    for (char c : s) {
        switch (c) {
            case '\\': o << "\\\\"; break;
            case '"':  o << "\\\""; break;
            case '\n': o << "\\n";  break;
            case '\r': o << "\\r";  break;
            case '\t': o << "\\t";  break;
            default:   o << c;      break;
        }
    }
    return o.str();
}

static std::string meta_str(const llama_model *m, const char *key) {
    char buf[512];
    int32_t n = llama_model_meta_val_str(m, key, buf, sizeof(buf));
    if (n < 0) return "";
    return std::string(buf, std::min<size_t>(n, sizeof(buf) - 1));
}

// ---------------------------------------------------------------------------
// Minimal JSON parser — only what we need for config/diagnostics
// ---------------------------------------------------------------------------

namespace mini_json {

struct Node {
    enum Type { String, Number, Bool, Array, Null } type = Null;
    std::string str;
    double num = 0;
    bool boolean = false;
    std::vector<std::string> array;
};

class Parser {
    const std::string &in_;
    size_t p_ = 0;
public:
    explicit Parser(const std::string &in) : in_(in) {}

    bool parseObject(std::map<std::string, Node> &out) {
        ws(); if (!eat('{')) return false; ws();
        if (peek() == '}') { p_++; return true; }
        for (;;) {
            ws(); std::string k;
            if (!str(k)) return false;
            ws(); if (!eat(':')) return false; ws();
            Node v; if (!val(v)) return false;
            out[k] = std::move(v);
            ws();
            if (eat(',')) continue;
            return eat('}');
        }
    }

    bool parseArray(std::vector<std::map<std::string, Node>> &out) {
        ws(); if (!eat('[')) return false; ws();
        if (peek() == ']') { p_++; return true; }
        for (;;) {
            ws(); std::map<std::string, Node> obj;
            if (!parseObject(obj)) return false;
            out.push_back(std::move(obj));
            ws();
            if (eat(',')) continue;
            return eat(']');
        }
    }

private:
    char peek() const { return p_ < in_.size() ? in_[p_] : 0; }
    void ws() { while (p_ < in_.size() && (in_[p_]==' '||in_[p_]=='\t'||in_[p_]=='\n'||in_[p_]=='\r')) p_++; }
    bool eat(char c) { if (peek()==c) { p_++; return true; } return false; }

    bool str(std::string &o) {
        if (!eat('"')) return false; o.clear();
        while (p_ < in_.size()) {
            char c = in_[p_++];
            if (c == '"') return true;
            if (c == '\\') { if (p_>=in_.size()) return false; c=in_[p_++]; }
            o += c;
        }
        return false;
    }

    bool val(Node &o) {
        ws(); char c = peek();
        if (c == '"') { o.type = Node::String; return str(o.str); }
        if (c == '[') {
            o.type = Node::Array;
            if (!eat('[')) return false; ws();
            if (eat(']')) return true;
            for (;;) {
                Node v; if (!val(v)) return false;
                if (v.type == Node::String) o.array.push_back(std::move(v.str));
                ws(); if (eat(',')) continue; return eat(']');
            }
        }
        if (!memcmp(&in_[p_],"true",4))  { o.type=Node::Bool; o.boolean=true;  p_+=4; return true; }
        if (!memcmp(&in_[p_],"false",5)) { o.type=Node::Bool; o.boolean=false; p_+=5; return true; }
        if (!memcmp(&in_[p_],"null",4))  { p_+=4; return true; }
        return num(o);
    }

    bool num(Node &o) {
        size_t s = p_;
        while (p_ < in_.size() && (std::isdigit(in_[p_])||in_[p_]=='-'||in_[p_]=='.'||in_[p_]=='e'||in_[p_]=='E')) p_++;
        if (p_ == s) return false;
        o.type = Node::Number;
        o.num = std::atof(in_.substr(s, p_-s).c_str());
        return true;
    }
};

bool parseObject(const std::string &in, std::map<std::string, Node> &out) {
    return Parser(in).parseObject(out);
}

bool parseObjectArray(const std::string &in, std::vector<std::map<std::string, Node>> &out) {
    return Parser(in).parseArray(out);
}

} // namespace mini_json

// ---------------------------------------------------------------------------
// Generation config
// ---------------------------------------------------------------------------

struct GenConfig {
    int maxTokens = 512;
    float temperature = 0.8f;
    float topP = 0.95f;
    int topK = 40;
    float minP = 0.05f;
    float typicalP = 1.0f;          // 1.0 = disabled
    float repetitionPenalty = 1.0f; // 1.0 = disabled
    float presencePenalty = 0.0f;
    float frequencyPenalty = 0.0f;
    float dryMultiplier = 0.0f;     // 0.0 = disabled
    float dryBase = 1.75f;
    int dryAllowedLength = 2;
    int dryPenaltyLastN = -1;       // -1 = context size
    int mirostat = 0;               // 0 = disabled, 1 = v1, 2 = v2
    float mirostatTau = 5.0f;
    float mirostatEta = 0.1f;
    std::string grammar;            // GBNF grammar, empty = none
    std::string jsonSchema;         // JSON schema, empty = none
    bool enableThinking = false;    // enable thinking mode in chat templates (Qwen2.5/Qwen3)
    int64_t seed = -1;
    std::vector<std::string> stopSequences;
};

static GenConfig parseGenConfig(const std::string &json) {
    GenConfig c;
    std::map<std::string, mini_json::Node> obj;
    if (!mini_json::parseObject(json, obj)) return c;
    auto get = [&](const std::string &k) -> const mini_json::Node * {
        auto it = obj.find(k); return it != obj.end() ? &it->second : nullptr;
    };
    if (auto *n = get("maxTokens"))          c.maxTokens = (int)n->num;
    if (auto *n = get("temperature"))        c.temperature = (float)n->num;
    if (auto *n = get("topP"))               c.topP = (float)n->num;
    if (auto *n = get("topK"))               c.topK = (int)n->num;
    if (auto *n = get("minP"))               c.minP = (float)n->num;
    if (auto *n = get("typicalP"))           c.typicalP = (float)n->num;
    if (auto *n = get("repetitionPenalty"))  c.repetitionPenalty = (float)n->num;
    if (auto *n = get("presencePenalty"))    c.presencePenalty = (float)n->num;
    if (auto *n = get("frequencyPenalty"))   c.frequencyPenalty = (float)n->num;
    if (auto *n = get("dryMultiplier"))      c.dryMultiplier = (float)n->num;
    if (auto *n = get("dryBase"))            c.dryBase = (float)n->num;
    if (auto *n = get("dryAllowedLength"))   c.dryAllowedLength = (int)n->num;
    if (auto *n = get("dryPenaltyLastN"))    c.dryPenaltyLastN = (int)n->num;
    if (auto *n = get("mirostat"))           c.mirostat = (int)n->num;
    if (auto *n = get("mirostatTau"))        c.mirostatTau = (float)n->num;
    if (auto *n = get("mirostatEta"))        c.mirostatEta = (float)n->num;
    if (auto *n = get("grammar"); n && n->type == mini_json::Node::String)
        c.grammar = n->str;
    if (auto *n = get("jsonSchema"); n && n->type == mini_json::Node::String)
        c.jsonSchema = n->str;
    if (auto *n = get("enableThinking"))     c.enableThinking = n->boolean;
    if (auto *n = get("seed"))               c.seed = (int64_t)n->num;
    if (auto *n = get("stopSequences"); n && n->type == mini_json::Node::Array)
        c.stopSequences = n->array;
    return c;
}

// Maps a GenConfig onto llama.cpp's sampler parameters. The sampler chain
// matches the llama-cli defaults; the common sampler library handles mirostat
// and grammar internally when their fields are set.
static common_params_sampling buildSamplingParams(const GenConfig &cfg) {
    common_params_sampling sp;

    sp.seed              = cfg.seed >= 0 ? (uint32_t)cfg.seed : LLAMA_DEFAULT_SEED;
    sp.temp              = cfg.temperature;
    sp.top_k             = cfg.topK;
    sp.top_p             = cfg.topP;
    sp.min_p             = cfg.minP;
    sp.typ_p             = cfg.typicalP;
    sp.penalty_last_n    = 64;
    sp.penalty_repeat    = cfg.repetitionPenalty;
    sp.penalty_freq      = cfg.frequencyPenalty;
    sp.penalty_present   = cfg.presencePenalty;
    sp.dry_multiplier     = cfg.dryMultiplier;
    sp.dry_base           = cfg.dryBase;
    sp.dry_allowed_length = cfg.dryAllowedLength;
    sp.dry_penalty_last_n = cfg.dryPenaltyLastN;
    sp.mirostat           = cfg.mirostat;
    sp.mirostat_tau       = cfg.mirostatTau;
    sp.mirostat_eta       = cfg.mirostatEta;
    sp.n_prev             = 64;

    if (!cfg.jsonSchema.empty()) {
        nlohmann::ordered_json schema = nlohmann::ordered_json::parse(cfg.jsonSchema);
        sp.grammar = { COMMON_GRAMMAR_TYPE_OUTPUT_FORMAT, json_schema_to_grammar(schema) };
    } else if (!cfg.grammar.empty()) {
        sp.grammar = { COMMON_GRAMMAR_TYPE_USER, cfg.grammar };
    }

    return sp;
}

// ---------------------------------------------------------------------------
// Engine state
// ---------------------------------------------------------------------------

struct LlamaEngine {
    // ── owned resources (RAII) ──
    common_init_result_ptr  initResult;   // owns model, ctx, internal samplers
    common_sampler         *sampler;      // clone we reconfigure per-request
    common_chat_templates_ptr chatTmpls;

    // ── non-owning accessors (valid while initResult is alive) ──
    llama_model            *model;
    llama_context          *ctx;

    // ── config ──
    llama_context_params    ctxParams{};
    bool                    useFlashAttention = true;
    std::atomic<bool>       cancel{false};
    JavaVM                 *jvm = nullptr;

    // ── GPU stats ──
    int gpuLayersUsed = 0;
    int totalLayers = 0;
    size_t gpuMemoryAllocatedBytes = 0;
    size_t gpuMemoryPeakBytes = 0;
    size_t gpuMemoryFreeBytes = 0;
    size_t gpuMemoryTotalBytes = 0;
    int gpuBufferCount = 0;
    bool gpuInferenceVerified = false;
    std::string gpuName;
    std::string gpuDriverVersion;
    std::string gpuApiVersion;
    std::string backendReason;

    // ── runtime ──
    int promptCount = 0;
    size_t peakMemoryBytes = 0;

    // ── upstream state (matches ai_chat.cpp) ──
    std::vector<common_chat_msg> chatMsgs;   // accumulated messages
    llama_pos chatPosition = 0;              // current position in KV cache
    llama_pos systemPromptEnd = 0;           // position after system prompt

    // ── diagnostics ──
    std::vector<llama_token> lastPromptTokens;
    std::vector<llama_token> lastGeneratedTokens;
    std::string lastPromptText;
    int64_t lastFirstTokenMs = 0;
    std::string lastStopReason;

    LlamaEngine() : sampler(nullptr), model(nullptr), ctx(nullptr) {}

    ~LlamaEngine() { destroy(); }

    void destroy() {
        cancel.store(true);
        // Clear non-owning pointers before releasing owners
        model = nullptr;
        ctx = nullptr;
        if (sampler) { common_sampler_free(sampler); sampler = nullptr; }
        chatTmpls.reset();
        initResult.reset();
        promptCount = 0;
        gpuLayersUsed = 0;
        totalLayers = 0;
        gpuMemoryAllocatedBytes = 0;
        gpuBufferCount = 0;
        gpuInferenceVerified = false;
        backendReason.clear();
        chatMsgs.clear();
        chatPosition = 0;
        systemPromptEnd = 0;
        lastPromptTokens.clear();
        lastGeneratedTokens.clear();
        lastPromptText.clear();
        lastFirstTokenMs = 0;
        lastStopReason.clear();
        peakMemoryBytes = 0;
    }

    void trackMemory() {
        size_t b = 0;
        if (model) b += llama_model_size(model);
        if (ctx)   b += llama_state_get_size(ctx);
        if (b > peakMemoryBytes) peakMemoryBytes = b;
    }
};

// ---------------------------------------------------------------------------
// Vulkan diagnostics
// ---------------------------------------------------------------------------

struct VulkanInfo {
    bool ok = false;
    std::string name, driver, api, reason;
    size_t freeBytes = 0, totalBytes = 0;
};

static std::string vk_ver(uint32_t v) {
    std::ostringstream o;
    o << VK_VERSION_MAJOR(v) << "." << VK_VERSION_MINOR(v) << "." << VK_VERSION_PATCH(v);
    return o.str();
}

static VulkanInfo checkVulkan() {
    VulkanInfo info;
#ifdef GGML_USE_VULKAN
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "AndroLLM";
    app.applicationVersion = 1;
    app.pEngineName = "llama.cpp";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&ci, nullptr, &inst) != VK_SUCCESS) {
        info.reason = "vkCreateInstance failed"; return info;
    }

    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(inst, &count, nullptr) != VK_SUCCESS || count == 0) {
        info.reason = "no Vulkan devices"; vkDestroyInstance(inst, nullptr); return info;
    }

    std::vector<VkPhysicalDevice> devs(count);
    vkEnumeratePhysicalDevices(inst, &count, devs.data());

    uint32_t sel = 0;
    bool foundGpu = false;
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(devs[i], &props);
        if (!foundGpu && props.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) { sel = i; foundGpu = true; }
    }

    VkPhysicalDeviceProperties props{};
    VkPhysicalDeviceMemoryProperties mem{};
    vkGetPhysicalDeviceProperties(devs[sel], &props);
    vkGetPhysicalDeviceMemoryProperties(devs[sel], &mem);
    for (uint32_t h = 0; h < mem.memoryHeapCount; ++h)
        if (mem.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)
            info.totalBytes += mem.memoryHeaps[h].size;
    info.freeBytes = info.totalBytes;
    info.name = props.deviceName;
    info.driver = vk_ver(props.driverVersion);
    info.api = vk_ver(props.apiVersion);
    info.ok = true;
    LOGI("[Vulkan] GPU: %s", info.name.c_str());
    vkDestroyInstance(inst, nullptr);

    // Check ggml-vulkan
    try {
        int n = ggml_backend_vk_get_device_count();
        if (n <= 0 || !llama_supports_gpu_offload()) {
            info.ok = false; info.reason = "ggml-vulkan: no compute device";
        }
    } catch (...) {
        info.ok = false; info.reason = "ggml-vulkan init exception";
    }
#else
    info.reason = "Vulkan not compiled";
#endif
    return info;
}

// ---------------------------------------------------------------------------
// Chat template rendering (delegates entirely to llama.cpp)
// ---------------------------------------------------------------------------

static std::string renderChat(
    const common_chat_templates *tmpls,
    const std::string &msgsJson,
    bool addAssistant,
    std::vector<common_chat_msg> &chatMsgs) {

    std::vector<std::map<std::string, mini_json::Node>> msgs;
    if (!mini_json::parseObjectArray(msgsJson, msgs) || msgs.empty()) {
        LOGE("[Template] bad message JSON"); return "";
    }

    // Clear previous messages for fresh conversation
    chatMsgs.clear();

    // Format each message incrementally using upstream's common_chat_format_single().
    // Each call returns ONLY the newly formatted message, not the full conversation.
    // We must concatenate all results to produce the complete prompt.
    std::string fullPrompt;
    for (size_t i = 0; i < msgs.size(); ++i) {
        auto &m = msgs[i];
        auto r = m.find("role"), c = m.find("content");
        if (r == m.end() || c == m.end() || r->second.str.empty()) {
            LOGE("[Template] msg missing role/content"); return "";
        }
        common_chat_msg newMsg{r->second.str, c->second.str};
        bool isLast = (i + 1 == msgs.size());
        bool addGen = isLast && addAssistant;
        std::string formatted = common_chat_format_single(tmpls, chatMsgs, newMsg, addGen, /*use_jinja=*/false);
        if (formatted.empty()) {
            LOGE("[Template] format_single returned empty for msg %zu role=%s", i, newMsg.role.c_str());
            return "";
        }
        fullPrompt += formatted;
        LOGI("[Template] msg %zu role=%s formatted %zu chars", i, newMsg.role.c_str(), formatted.size());
        chatMsgs.push_back(std::move(newMsg));
    }

    if (fullPrompt.empty()) {
        LOGE("[Template] render returned empty prompt");
        return "";
    }
    LOGI("[Template] rendered prompt (%zu chars): %.80s...",
         fullPrompt.size(), fullPrompt.c_str());
    return fullPrompt;
}

// ---------------------------------------------------------------------------
// JSON stats builders
// ---------------------------------------------------------------------------

static std::string statsJson(
    int64_t promptToks, int64_t genToks,
    int64_t promptMs, int64_t genMs,
    float tps, size_t peakMem, int64_t firstMs,
    const std::string &stopReason) {

    std::ostringstream o;
    o << "{\"promptTokens\":" << promptToks
      << ",\"generatedTokens\":" << genToks
      << ",\"promptTimeMs\":" << promptMs
      << ",\"generationTimeMs\":" << genMs
      << ",\"totalTimeMs\":" << (promptMs + genMs)
      << ",\"tokensPerSecond\":" << tps
      << ",\"memoryPeakBytes\":" << peakMem
      << ",\"firstTokenMs\":" << firstMs
      << ",\"stopReason\":\"" << json_escape(stopReason) << "\"}";
    return o.str();
}

// ---------------------------------------------------------------------------
// Generation — delegates everything to llama.cpp
// ---------------------------------------------------------------------------

static std::string doGenerate(
    LlamaEngine *eng,
    JNIEnv *env,
    jobject callback,
    jmethodID onToken,
    const std::string &prompt,
    const GenConfig &cfg) {

    using clock = std::chrono::steady_clock;

    pin_big_cores();

    if (!eng->model || !eng->ctx || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return "{}";
    }

    eng->promptCount++;
    eng->cancel.store(false);
    eng->trackMemory();

    auto t0 = clock::now();

    // ── Tokenize ──
    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    const bool addBos = llama_vocab_get_add_bos(vocab);
    std::vector<llama_token> tokens =
        common_tokenize(eng->ctx, prompt, /*add_special=*/addBos, /*parse_special=*/true);

    eng->lastPromptTokens = tokens;
    eng->lastPromptText = prompt;
    eng->lastGeneratedTokens.clear();
    eng->lastFirstTokenMs = 0;
    eng->lastStopReason = "max_tokens";

    // Log token IDs for validation
    {
        std::ostringstream ids;
        for (size_t i = 0; i < tokens.size(); i++) {
            if (i) ids << ", ";
            ids << tokens[i];
        }
        LOGI("[Tok] n=%zu ids=[%s]", tokens.size(), ids.str().c_str());
    }

    // ── Always clear KV cache before prefill ──
    // Our architecture re-renders the full conversation each time via renderChat(),
    // so we must start fresh. This matches upstream's pattern of processing system
    // prompt, user prompt, and assistant header as separate decode steps.
    llama_memory_clear(llama_get_memory(eng->ctx), true);

    const uint32_t nCtx = llama_n_ctx(eng->ctx);

    // ── Prefill ──
    const int n_batch = llama_n_batch(eng->ctx);
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    bool prefillOk = true;
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        size_t n = std::min<size_t>(n_batch, tokens.size() - i);
        common_batch_clear(batch);
        for (size_t j = 0; j < n; j++) {
            bool last = (i + j == tokens.size() - 1);
            common_batch_add(batch, tokens[i + j], (llama_pos)(i + j), {0}, last);
        }
        if (llama_decode(eng->ctx, batch) != 0) {
            prefillOk = false;
            break;
        }
    }
    llama_batch_free(batch);

    if (!prefillOk) {
        eng->lastStopReason = "decode_error";
        throw_java(env, "Prompt decode failed");
        return "{}";
    }

    auto t1 = clock::now();
    int64_t promptMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    // ── Reset sampler ──
    common_sampler_reset(eng->sampler);

    // ── Decode loop (upstream pattern) ──
    std::string output;
    int64_t generated = 0;

    for (int i = 0; i < cfg.maxTokens; i++) {
        if (eng->cancel.load()) { eng->lastStopReason = "cancelled"; break; }

        // Context full: shift (keep half the context after system prompt)
        if (llama_memory_seq_pos_max(llama_get_memory(eng->ctx), 0) >= (llama_pos)nCtx - 4) {
            const llama_pos n_discard = (llama_pos)nCtx / 4;
            llama_memory_seq_rm(llama_get_memory(eng->ctx), 0, 0, n_discard);
            llama_memory_seq_add(llama_get_memory(eng->ctx), 0, n_discard, -1, -n_discard);
            LOGI("[Shift] discarded %d tokens from KV cache", n_discard);
        }

        // Sample
        llama_token id = common_sampler_sample(eng->sampler, eng->ctx, -1);
        eng->lastGeneratedTokens.push_back(id);
        common_sampler_accept(eng->sampler, id, true);

        // EOS check
        if (llama_vocab_is_eog(vocab, id)) {
            eng->lastStopReason = "eos";
            LOGI("[Gen] EOS at step=%lld", (long long)generated);
            break;
        }

        // First token timing
        if (generated == 0) {
            auto now = clock::now();
            eng->lastFirstTokenMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - t1).count();
        }

        // Detokenize with special=true (upstream default)
        std::string piece = common_token_to_piece(eng->ctx, id);
        output += piece;

        // Stop sequences
        bool stopped = false;
        for (auto &s : cfg.stopSequences) {
            if (output.size() >= s.size() &&
                output.compare(output.size() - s.size(), s.size(), s) == 0) {
                output.resize(output.size() - s.size());
                stopped = true; break;
            }
        }
        if (stopped) { eng->lastStopReason = "stop_sequence"; break; }

        if (!piece.empty()) {
            jstring jpiece = to_jstring(env, piece);
            env->CallVoidMethod(callback, onToken, jpiece, JNI_FALSE);
            env->DeleteLocalRef(jpiece);
        }
        generated++;

        // Feed token back for next step (upstream pattern: llama_batch_get_one)
        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(eng->ctx, next) != 0) {
            eng->lastStopReason = "decode_error";
            LOGW("[Gen] decode failed at step=%lld", (long long)generated);
            break;
        }
    }

    auto t2 = clock::now();
    int64_t genMs = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    float tps = genMs > 0 ? (float)generated * 1000.f / (float)genMs : 0.f;

    LOGI("[Perf] backend=%s gpu=%d prompt=%lldms(%zu) gen=%lldms(%lld) %.2f tok/s",
         eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU",
         eng->gpuLayersUsed, (long long)promptMs, tokens.size(),
         (long long)genMs, (long long)generated, tps);

    eng->trackMemory();

    // Send final empty delta
    jstring empty = to_jstring(env, "");
    env->CallVoidMethod(callback, onToken, empty, JNI_TRUE);
    env->DeleteLocalRef(empty);

    return statsJson(tokens.size(), generated, promptMs, genMs, tps,
                     eng->peakMemoryBytes, eng->lastFirstTokenMs,
                     eng->lastStopReason);
}

// ---------------------------------------------------------------------------
// Backend validation: comprehensive CPU-vs-Vulkan correctness comparison
// ---------------------------------------------------------------------------
//
// A CPU-only copy of the same GGUF file is loaded (n_gpu_layers = 0) to act as
// the canonical reference. llama-cli runs the exact same ggml-cpu path, so the
// CPU backend is the official reference and the Vulkan backend must reproduce
// it. Every generated token id and the full logit vectors are compared at every
// decode step across greedy, long-context, and stochastic sampling runs. If any
// check diverges, the Vulkan backend is treated as broken and the caller falls
// back to CPU.

static const int        VERIFY_GREEDY_STEPS     = 128;    // greedy tokens per prompt
static const int        VERIFY_SAMPLE_STEPS     = 48;     // stochastic tokens per run
static const int        VERIFY_LONG_CTX         = 256;    // small ctx -> forces KV shift
static const int        VERIFY_LONG_STEPS       = 512;    // generate far beyond ctx size
static const float      VERIFY_LOGITS_TOLERANCE = 2.0f;   // max |cpu-gpu| logit gap allowed
static const uint32_t   VERIFY_SEED             = 12345;

static const char * VERIFY_PROMPTS[] = {
    "Hello",
    "What is Android?",
    "Summarize the history of AI.",
    "Write a Python hello world program.",
    "Explain quantum mechanics in one paragraph.",
};

static const char * VERIFY_LONG_PROMPT =
    "Write a detailed essay on the history of computing from 1940 to the present "
    "day, covering hardware, software, and the major innovations.";

struct VerifyMismatch {
    std::string prompt;    // prompt the mismatch was found in
    std::string test;      // "setup" | "greedy" | "long-context" | "sampling"
    int         step;      // token index within the run
    llama_token cpuToken;  // CPU reference token
    llama_token gpuToken;  // Vulkan token
    float       maxLogitDiff;
    std::string detail;
};

struct VerifyResult {
    bool    passed = false;
    int     greedyPrompts   = 0;
    int     greedySteps     = 0;
    int     longContextSteps = 0;
    int     samplingSteps   = 0;
    float   maxLogitDiff    = 0.0f;
    int64_t durationMs      = 0;
    std::vector<VerifyMismatch> mismatches;
};

// Compare the full logit vectors of the last decoded token on both contexts.
static bool compare_logit_vectors(llama_context * gpu, llama_context * cpu,
                                  int n_vocab, float * maxDiff) {
    const float * lg = llama_get_logits_ith(gpu, -1);
    const float * lc = llama_get_logits_ith(cpu, -1);
    if (!lg || !lc) return false;
    float md = 0.0f;
    for (int i = 0; i < n_vocab; i++) {
        const float d = std::fabs(lg[i] - lc[i]);
        if (d > md) md = d;
    }
    *maxDiff = md;
    return true;
}

// Decode `toks` into `ctx` in one pass, requesting logits for the last token.
static bool prefill_tokens(llama_context * ctx, const std::vector<llama_token> & toks) {
    llama_memory_clear(llama_get_memory(ctx), true);
    llama_batch batch = llama_batch_init((int32_t)toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++) {
        common_batch_add(batch, toks[i], (llama_pos)i, {0}, (i + 1 == toks.size()));
    }
    const int rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    return rc == 0;
}

// Tokenize once and prefill both contexts with the exact same tokens.
static bool prefill_pair(llama_context * gpu, llama_context * cpu, const std::string & text) {
    const bool add_bos = llama_vocab_get_add_bos(llama_model_get_vocab(llama_get_model(gpu)));
    std::vector<llama_token> toks = common_tokenize(gpu, text, add_bos, true);
    if (toks.empty()) return false;
    return prefill_tokens(gpu, toks) && prefill_tokens(cpu, toks);
}

static std::string format_verify_summary(const VerifyResult & vr) {
    std::ostringstream o;
    o << "greedy=" << vr.greedyPrompts << "/5 prompts " << vr.greedySteps << " tokens";
    o << ", long-context=" << vr.longContextSteps << " tokens";
    o << ", sampling=" << vr.samplingSteps << " tokens";
    o << ", max|logits diff|=" << std::fixed << std::setprecision(4) << vr.maxLogitDiff;
    o << ", " << vr.durationMs << "ms";
    return o.str();
}

static std::string format_verify_failures(const VerifyResult & vr) {
    std::ostringstream o;
    for (const auto & m : vr.mismatches) {
        o << " | " << (m.test.empty() ? "setup" : m.test);
        if (!m.prompt.empty()) o << "[" << m.prompt << "]";
        if (m.test != "setup") o << " step=" << m.step;
        o << ": " << m.detail;
        if (m.test != "setup") o << " cpu=" << m.cpuToken << " gpu=" << m.gpuToken;
        o << " logits=" << std::fixed << std::setprecision(4) << m.maxLogitDiff;
    }
    return o.str();
}

// Runs the full validation suite. `gpu_ctx` is the production Vulkan-offloaded
// context; `cpu_model` is the CPU-only reference model (same GGUF). Both run the
// same tokenizer, chat-template settings, context length (per test), flash
// attention setting and seed. `passed` is true only if every check succeeded.
static VerifyResult run_backend_validation(llama_model * gpu_model, llama_model * cpu_model,
                                           llama_context * gpu_ctx, int threads,
                                           llama_flash_attn_type flash_type) {
    VerifyResult vr;
    const int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(gpu_model));
    const auto t0 = std::chrono::steady_clock::now();

    auto add_mismatch = [&](std::string prompt, std::string test, int step,
                            llama_token cpu, llama_token gpu, float diff, std::string detail) {
        vr.mismatches.push_back({std::move(prompt), std::move(test), step,
                                 cpu, gpu, diff, std::move(detail)});
    };

    // ── CPU reference context, mirroring the GPU context's settings ──
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = 2048;
    cp.n_batch         = 512;
    cp.n_threads       = std::max(1, threads);
    cp.n_threads_batch = std::max(1, threads);
    cp.flash_attn_type = flash_type;
    cp.type_k          = GGML_TYPE_F16;
    cp.type_v          = GGML_TYPE_F16;

    llama_context * ref = llama_init_from_model(cpu_model, cp);
    if (!ref) {
        add_mismatch("", "setup", 0, 0, 0, 0.0f, "failed to create CPU reference context");
        return vr;
    }

    LOGI("[Verify] reference ctx=%u gpu ctx=%u threads=%d flash=%s",
         llama_n_ctx(ref), llama_n_ctx(gpu_ctx), cp.n_threads,
         flash_type != LLAMA_FLASH_ATTN_TYPE_DISABLED ? "AUTO" : "OFF");

    // Run one deterministic generation on both contexts, comparing every sampled
    // token and the full logit vectors at every decode step.
    auto run_prompt = [&](const std::string & label, const std::string & text,
                          llama_context * gctx, llama_context * cctx,
                          common_params_sampling & sp, int steps, const char * test) -> bool {
        common_params_sampling s1 = sp, s2 = sp;
        common_sampler * gs = common_sampler_init(gpu_model, s1);
        common_sampler * cs = common_sampler_init(cpu_model, s2);
        if (!gs || !cs) {
            if (gs) common_sampler_free(gs);
            if (cs) common_sampler_free(cs);
            add_mismatch(label, test, 0, 0, 0, 0.0f, "sampler init failed");
            return false;
        }

        bool ok = prefill_pair(gctx, cctx, text);
        if (!ok) {
            add_mismatch(label, test, 0, 0, 0, 0.0f, "prefill failed");
            common_sampler_free(gs);
            common_sampler_free(cs);
            return false;
        }

        LOGI("[Verify] %s: %s (%d steps)", test, label.c_str(), steps);

        for (int step = 0; ok && step < steps; step++) {
            llama_token tg = common_sampler_sample(gs, gctx, -1);
            llama_token tc = common_sampler_sample(cs, cctx, -1);
            common_sampler_accept(gs, tg, true);
            common_sampler_accept(cs, tc, true);

            float md = 0.0f;
            if (!compare_logit_vectors(gctx, cctx, n_vocab, &md)) {
                add_mismatch(label, test, step, tc, tg, 0.0f, "logits unavailable");
                ok = false; break;
            }
            if (md > vr.maxLogitDiff) vr.maxLogitDiff = md;

            if (tg != tc) {
                add_mismatch(label, test, step, tc, tg, md, "token mismatch");
                ok = false; break;
            }
            if (md > VERIFY_LOGITS_TOLERANCE) {
                add_mismatch(label, test, step, tc, tg, md, "logit gap exceeds tolerance");
                ok = false; break;
            }

            if (std::strcmp(test, "greedy") == 0) vr.greedySteps++;
            else if (std::strcmp(test, "long-context") == 0) vr.longContextSteps++;
            else vr.samplingSteps++;

            llama_batch gb = llama_batch_get_one(&tg, 1);
            llama_batch cb = llama_batch_get_one(&tc, 1);
            const int rg = llama_decode(gctx, gb);
            const int rc = llama_decode(cctx, cb);
            if (rg != 0 || rc != 0) {
                add_mismatch(label, test, step, tc, tg, md,
                             "decode failed (gpu=" + std::to_string(rg) +
                             " cpu=" + std::to_string(rc) + ")");
                ok = false; break;
            }
        }

        common_sampler_free(gs);
        common_sampler_free(cs);
        return ok;
    };

    // ── Greedy test: temperature = 0, top_k = 1, top_p = 1, fixed seed ──
    common_params_sampling gsp;
    gsp.seed           = VERIFY_SEED;
    gsp.temp           = 0.0f;
    gsp.top_k          = 1;
    gsp.top_p          = 1.0f;
    gsp.min_p          = 0.0f;
    gsp.penalty_repeat = 1.0f;
    gsp.penalty_last_n = 64;
    gsp.samplers       = { COMMON_SAMPLER_TYPE_TEMPERATURE };

    for (const char * p : VERIFY_PROMPTS) {
        if (!vr.mismatches.empty()) break;
        if (run_prompt(p, p, gpu_ctx, ref, gsp, VERIFY_GREEDY_STEPS, "greedy")) {
            vr.greedyPrompts++;
        }
    }

    // ── Long-context test: generate far beyond a small context (KV shift) ──
    if (vr.mismatches.empty()) {
        llama_context_params lcp = cp;
        lcp.n_ctx   = VERIFY_LONG_CTX;
        lcp.n_batch = 64;
        lcp.n_ubatch = 64;
        llama_context * lg = llama_init_from_model(gpu_model, lcp);
        llama_context * lc = llama_init_from_model(cpu_model, lcp);
        if (lg && lc) {
            LOGI("[Verify] long-context test ctx=%d steps=%d (forces KV shift)",
                 VERIFY_LONG_CTX, VERIFY_LONG_STEPS);
            run_prompt("long-context", VERIFY_LONG_PROMPT, lg, lc, gsp,
                       VERIFY_LONG_STEPS, "long-context");
        } else {
            add_mismatch("", "long-context", 0, 0, 0, 0.0f,
                         lg ? "failed to create long-context CPU ctx"
                            : (lc ? "failed to create long-context GPU ctx"
                                  : "failed to create long-context contexts"));
        }
        if (lg) llama_free(lg);
        if (lc) llama_free(lc);
    }

    // ── Sampling tests: same seed on both backends, exact token match ──
    //    (same logits + same RNG seed must yield identical sampled tokens)
    common_params_sampling ssp;   // temperature / top_k / top_p / min_p / repeat penalty
    ssp.seed           = VERIFY_SEED;
    ssp.temp           = 0.8f;
    ssp.top_k          = 40;
    ssp.top_p          = 0.95f;
    ssp.min_p          = 0.05f;
    ssp.penalty_repeat = 1.1f;
    ssp.penalty_last_n = 64;
    ssp.samplers       = { COMMON_SAMPLER_TYPE_PENALTIES,
                           COMMON_SAMPLER_TYPE_TOP_K,
                           COMMON_SAMPLER_TYPE_TOP_P,
                           COMMON_SAMPLER_TYPE_MIN_P,
                           COMMON_SAMPLER_TYPE_TEMPERATURE };

    common_params_sampling tsp = ssp;   // typical_p
    tsp.typ_p = 0.9f;
    tsp.samplers = { COMMON_SAMPLER_TYPE_PENALTIES,
                     COMMON_SAMPLER_TYPE_TYPICAL_P,
                     COMMON_SAMPLER_TYPE_TOP_P,
                     COMMON_SAMPLER_TYPE_MIN_P,
                     COMMON_SAMPLER_TYPE_TEMPERATURE };

    common_params_sampling msp = ssp;   // mirostat 2.0 (replaces the top-k/p chain)
    msp.mirostat     = 2;
    msp.mirostat_tau = 5.0f;
    msp.mirostat_eta = 0.1f;

    struct SampleRun { const char * name; common_params_sampling * sp; };
    SampleRun runs[] = { { "standard", &ssp }, { "typical", &tsp }, { "mirostat", &msp } };
    for (const auto & run : runs) {
        if (!vr.mismatches.empty()) break;
        LOGI("[Verify] sampling test '%s'", run.name);
        for (const char * p : VERIFY_PROMPTS) {
            if (!vr.mismatches.empty()) break;
            run_prompt(std::string(run.name) + ": " + p, p, gpu_ctx, ref,
                       *run.sp, VERIFY_SAMPLE_STEPS, "sampling");
        }
    }

    llama_free(ref);

    vr.durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    vr.passed = vr.mismatches.empty();
    return vr;
}

// ===========================================================================
// JNI entry points
// ===========================================================================

extern "C" {

// ── nativeCreate ───────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeCreate(
    JNIEnv *env, jobject, jstring configJson) {
try {

    int threads = 4, ctxLen = 4096;
    bool flash = true;
    {
        std::map<std::string, mini_json::Node> obj;
        mini_json::parseObject(from_jstring(env, configJson), obj);
        auto it = obj.end();
        if ((it = obj.find("threads"))           != obj.end()) threads = (int)it->second.num;
        if ((it = obj.find("maxContextLength"))  != obj.end()) ctxLen  = (int)it->second.num;
        if ((it = obj.find("useFlashAttention")) != obj.end()) flash   = it->second.boolean;
    }

    llama_log_set([](enum ggml_log_level lvl, const char *txt, void *) {
        switch (lvl) {
            case GGML_LOG_LEVEL_ERROR: LOGE("%s", txt); break;
            case GGML_LOG_LEVEL_WARN:  LOGW("%s", txt); break;
            default:                   LOGI("%s", txt); break;
        }
    }, nullptr);

    try { common_init(); } catch (...) { LOGW("common_init failed"); }

    auto *eng = new LlamaEngine();
    eng->jvm = g_jvm;
    eng->useFlashAttention = flash;

    llama_context_params p = llama_context_default_params();
    p.n_ctx = ctxLen;
    p.n_threads = threads;
    p.n_threads_batch = threads;
    eng->ctxParams = p;

    return reinterpret_cast<jlong>(eng);

} catch (const std::exception &e) {
    LOGE("[Create] exception: %s", e.what());
    throw_java(env, std::string("Engine creation failed: ") + e.what());
    return 0;
} catch (...) {
    LOGE("[Create] unknown exception");
    throw_java(env, "Engine creation failed (unknown)");
    return 0;
}
}

// ── nativeLoadModel ────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeLoadModel(
    JNIEnv *env, jobject, jlong handle, jstring modelPath, jstring loadCfgJson) {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng) { throw_java(env, "Invalid engine handle"); return; }

    std::string path = from_jstring(env, modelPath);

    int ctxLen = 0, batchSize = 2048, gpuLayers = -1;
    {
        std::map<std::string, mini_json::Node> obj;
        mini_json::parseObject(from_jstring(env, loadCfgJson), obj);
        auto it = obj.end();
        if ((it = obj.find("contextLength")) != obj.end()) ctxLen    = (int)it->second.num;
        if ((it = obj.find("batchSize"))     != obj.end()) batchSize = (int)it->second.num;
        if ((it = obj.find("gpuLayers"))     != obj.end()) gpuLayers = (int)it->second.num;
    }

    // Clean up any previously loaded model
    eng->destroy();

    auto t0 = std::chrono::steady_clock::now();

    // ── Vulkan check ──
    VulkanInfo vk = checkVulkan();
    eng->gpuName = vk.name;
    eng->gpuDriverVersion = vk.driver;
    eng->gpuApiVersion = vk.api;
    eng->gpuMemoryFreeBytes = vk.freeBytes;
    eng->gpuMemoryTotalBytes = vk.totalBytes;

    LOGI("[Load] path=%s vulkan=%s gpuLayers=%d ctx=%d batch=%d",
         path.c_str(), vk.ok ? "YES" : "NO", gpuLayers, ctxLen, batchSize);

    // ── Build common_params ──
    common_params params;
    params.model.path    = path;
    params.n_ctx          = ctxLen <= 0 ? 0 : ctxLen;   // 0 = auto-fit to memory (llama-cli default)
    params.n_batch        = std::max(1024, batchSize);
    params.n_ubatch       = std::min(params.n_batch, 512);
    params.n_gpu_layers   = gpuLayers;
    params.cache_type_k   = GGML_TYPE_F16;
    params.cache_type_v   = GGML_TYPE_F16;
    params.flash_attn_type = eng->useFlashAttention ? LLAMA_FLASH_ATTN_TYPE_AUTO : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    int selectedGpuLayers = gpuLayers;
    if (!vk.ok && gpuLayers != 0) {
        LOGW("[Load] Vulkan unavailable, forcing CPU");
        params.n_gpu_layers = 0;
        selectedGpuLayers = 0;
        eng->backendReason = "Vulkan unavailable: " + vk.reason;
    }

    // ── Load model via official llama.cpp ──
    common_init_result_ptr result;
    try {
        result = common_init_from_params(params);
    } catch (const std::exception &e) {
        if (params.n_gpu_layers != 0) {
            LOGW("[Load] GPU failed (%s) - retrying CPU", e.what());
            params.n_gpu_layers = 0;
            selectedGpuLayers = 0;
            eng->backendReason = std::string("GPU init failed: ") + e.what();
            try { result = common_init_from_params(params); }
            catch (const std::exception &e2) {
                throw_java(env, std::string("Model load failed: ") + e2.what()); return;
            }
        } else {
            throw_java(env, std::string("Model load failed: ") + e.what()); return;
        }
    } catch (...) {
        if (params.n_gpu_layers != 0) {
            params.n_gpu_layers = 0;
            selectedGpuLayers = 0;
            eng->backendReason = "GPU init failed (unknown)";
            try { result = common_init_from_params(params); }
            catch (...) { throw_java(env, "Model load failed (CPU fallback)"); return; }
        } else {
            throw_java(env, "Model load failed"); return;
        }
    }

    if (!result) {
        throw_java(env, "Model init returned null"); return;
    }

    // ── Install a loaded result into the engine ──
    auto install_result = [&](common_init_result_ptr & r, const char * failMsg) -> bool {
        eng->model = r->model();
        eng->ctx   = r->context();
        if (!eng->model || !eng->ctx) {
            eng->model = nullptr; eng->ctx = nullptr;
            r.reset();
            throw_java(env, failMsg);
            return false;
        }
        eng->sampler = common_sampler_clone(r->sampler(0));
        if (!eng->sampler) {
            r.reset();
            throw_java(env, "Sampler creation failed");
            return false;
        }
        eng->initResult = std::move(r);
        try {
            eng->chatTmpls = common_chat_templates_init(eng->model, "");
            if (!eng->chatTmpls) {
                LOGW("[Template] init returned null - model has no embedded chat template");
            } else {
                LOGI("[Template] init succeeded");
            }
        } catch (const std::exception &e) {
            LOGE("[Template] init failed: %s", e.what());
        } catch (...) {
            LOGE("[Template] init failed (unknown exception)");
        }
        const int nL = llama_model_n_layer(eng->model);
        eng->totalLayers = nL;
        eng->gpuLayersUsed = selectedGpuLayers < 0 ? nL : std::min(selectedGpuLayers, nL);
        return true;
    };

    if (!install_result(result, "Model or context creation failed")) return;

    // ── Log model info ──
    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    if (vocab) {
        LOGI("[Model] vocab=%d bos=%d eos=%d add_bos=%d",
             llama_vocab_n_tokens(vocab),
             llama_vocab_bos(vocab), llama_vocab_eos(vocab),
             llama_vocab_get_add_bos(vocab) ? 1 : 0);
    }

    const char *tmpl = llama_model_chat_template(eng->model, nullptr);
    LOGI("[Model] chat_template: %s", tmpl ? tmpl : "(none)");
    LOGI("[Model] template_ready: %s", eng->chatTmpls ? "yes" : "no");

    // ── Backend validation: Vulkan must reproduce a CPU reference exactly ──
    if (eng->gpuLayersUsed > 0) {
        LOGI("[Verify] loading CPU reference model (same GGUF, n_gpu_layers=0)");
        llama_model * cpu_ref_model = nullptr;
        {
            llama_model_params mp = llama_model_default_params();
            mp.n_gpu_layers = 0;   // pure CPU compute reference (official llama-cli path)
            cpu_ref_model = llama_model_load_from_file(path.c_str(), mp);
            if (!cpu_ref_model) {
                LOGW("[Verify] failed to load CPU reference model - validation aborted");
            }
        }

        VerifyResult vr;
        if (cpu_ref_model) {
            LOGI("[Verify] running comprehensive CPU-vs-Vulkan validation (threads=%d)",
                 eng->ctxParams.n_threads);
            vr = run_backend_validation(eng->model, cpu_ref_model, eng->ctx,
                                        eng->ctxParams.n_threads, params.flash_attn_type);
            llama_model_free(cpu_ref_model);
            cpu_ref_model = nullptr;
        } else {
            vr.mismatches.push_back({"", "setup", 0, 0, 0, 0.0f, "CPU reference model load failed"});
        }

        if (vr.passed) {
            eng->gpuInferenceVerified = true;
            eng->backendReason = "Vulkan verified: " + format_verify_summary(vr);
            LOGI("[Verify] PASSED: %s", eng->backendReason.c_str());
        } else {
            const std::string fail = "Vulkan backend failed correctness validation on this device." +
                                     format_verify_failures(vr) + " (" + format_verify_summary(vr) + ")";
            LOGW("[Load] %s", fail.c_str());
            eng->destroy();
            params.n_gpu_layers = 0;
            selectedGpuLayers = 0;
            params.flash_attn_type = eng->useFlashAttention ? LLAMA_FLASH_ATTN_TYPE_AUTO
                                                            : LLAMA_FLASH_ATTN_TYPE_DISABLED;
            try { result = common_init_from_params(params); }
            catch (const std::exception &e) {
                throw_java(env, std::string("CPU fallback after Vulkan validation failed: ") + e.what());
                return;
            }
            if (!result || !install_result(result, "CPU fallback after Vulkan validation failed")) return;
            eng->backendReason = fail;
            LOGW("[Load] fallback backend=CPU layers=%d/%d",
                 eng->gpuLayersUsed, eng->totalLayers);
        }
    } else {
        if (eng->backendReason.empty()) {
            eng->backendReason = "CPU backend (no GPU offload)";
        }
    }

    LOGI("[Load] backend=%s layers=%d/%d ctx=%d batch=%d ubatch=%d flash=%s",
         eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU",
         eng->gpuLayersUsed, eng->totalLayers, params.n_ctx, params.n_batch, params.n_ubatch,
         params.flash_attn_type != LLAMA_FLASH_ATTN_TYPE_DISABLED ? "AUTO" : "OFF");

    auto t1 = std::chrono::steady_clock::now();
    LOGI("[Load] done in %lldms",
         (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());

    // ── GPU memory stats ──
    if (eng->gpuLayersUsed > 0) {
        size_t mSize = llama_model_size(eng->model);
        size_t cSize = llama_state_get_size(eng->ctx);
        eng->gpuMemoryAllocatedBytes = mSize * eng->gpuLayersUsed / std::max(1, eng->totalLayers) + cSize;
        eng->gpuMemoryPeakBytes = eng->gpuMemoryAllocatedBytes;
        eng->gpuBufferCount = 2;
#ifdef GGML_USE_VULKAN
        size_t free = 0, total = 0;
        ggml_backend_vk_get_device_memory(0, &free, &total);
        eng->gpuMemoryFreeBytes = free;
        eng->gpuMemoryTotalBytes = total;
#endif
    }

    eng->cancel.store(false);
    eng->trackMemory();
}

// ── nativeIsLoaded ─────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeIsLoaded(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    return (eng && eng->model && eng->ctx) ? JNI_TRUE : JNI_FALSE;
}

// ── nativeModelInfo ────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeModelInfo(
    JNIEnv *env, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->model) return to_jstring(env, "null");

    char desc[128];
    llama_model_desc(eng->model, desc, sizeof(desc));
    const llama_vocab *v = llama_model_get_vocab(eng->model);
    const char *tmpl = llama_model_chat_template(eng->model, nullptr);

    std::ostringstream o;
    o << "{\"n_ctx_train\":" << llama_model_n_ctx_train(eng->model)
      << ",\"n_vocab\":" << (v ? llama_vocab_n_tokens(v) : 0)
      << ",\"n_layers\":" << llama_model_n_layer(eng->model)
      << ",\"gpuLayers\":" << eng->gpuLayersUsed
      << ",\"cpuLayers\":" << (llama_model_n_layer(eng->model) - eng->gpuLayersUsed)
      << ",\"backend\":\"" << (eng->gpuLayersUsed > 0 ? "vulkan" : "cpu")
      << "\",\"desc\":\"" << json_escape(desc) << "\"";
    if (tmpl) o << ",\"chatTemplate\":\"" << json_escape(tmpl) << "\"";
    o << ",\"architecture\":\"" << json_escape(meta_str(eng->model, "general.architecture")) << "\""
      << ",\"tokenizerModel\":\"" << json_escape(meta_str(eng->model, "tokenizer.ggml.model")) << "\""
      << ",\"generalName\":\"" << json_escape(meta_str(eng->model, "general.name")) << "\""
      << ",\"kvType\":\"F16\""
      << ",\"flashAttn\":\"" << (eng->useFlashAttention ? "AUTO" : "OFF") << "\"}";
    return to_jstring(env, o.str());
}

// ── nativeApplyChatTemplate ────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeApplyChatTemplate(
    JNIEnv *env, jobject, jlong handle, jstring msgJson, jboolean addAssistant) {
try {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->chatTmpls) {
        throw_java(env, "Chat templates not available");
        return to_jstring(env, "");
    }
    std::string rendered = renderChat(
        eng->chatTmpls.get(), from_jstring(env, msgJson),
        addAssistant == JNI_TRUE, eng->chatMsgs);
    LOGI("[Template] rendered %zu bytes", rendered.size());
    return to_jstring(env, rendered);
} catch (const std::exception &e) {
    LOGE("[Template] exception: %s", e.what());
    throw_java(env, std::string("Chat template failed: ") + e.what());
    return to_jstring(env, "");
} catch (...) {
    LOGE("[Template] unknown exception");
    throw_java(env, "Chat template failed (unknown)");
    return to_jstring(env, "");
}
}

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeApplyChatTemplateEx(
    JNIEnv *env, jobject, jlong handle, jstring msgJson, jboolean addAssistant, jboolean /*enableThinking*/) {
try {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->chatTmpls) {
        throw_java(env, "Chat templates not available");
        return to_jstring(env, "");
    }
    std::string rendered = renderChat(
        eng->chatTmpls.get(), from_jstring(env, msgJson),
        addAssistant == JNI_TRUE, eng->chatMsgs);
    LOGI("[Template] rendered %zu bytes", rendered.size());
    return to_jstring(env, rendered);
} catch (const std::exception &e) {
    LOGE("[Template] exception: %s", e.what());
    throw_java(env, std::string("Chat template failed: ") + e.what());
    return to_jstring(env, "");
} catch (...) {
    LOGE("[Template] unknown exception");
    throw_java(env, "Chat template failed (unknown)");
    return to_jstring(env, "");
}
}

// ── nativeResetChat ──────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeResetChat(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng) return;
    eng->chatMsgs.clear();
    eng->chatPosition = 0;
    eng->systemPromptEnd = 0;
    if (eng->ctx) {
        llama_memory_clear(llama_get_memory(eng->ctx), true);
    }
    LOGI("[Chat] reset: cleared messages, position, and KV cache");
}

// ── nativeGenerate ─────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeGenerate(
    JNIEnv *env, jobject, jlong handle,
    jstring prompt, jstring cfgJson, jobject callback) {
try {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->model || !eng->ctx || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return to_jstring(env, "{}");
    }

    GenConfig cfg = parseGenConfig(from_jstring(env, cfgJson));

    // Reconfigure sampler for this request
    {
        common_params_sampling sp = buildSamplingParams(cfg);

        common_sampler_free(eng->sampler);
        eng->sampler = common_sampler_init(eng->model, sp);
        if (!eng->sampler) {
            throw_java(env, "Sampler creation failed");
            return to_jstring(env, "{}");
        }
    }

    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Z)V");
    if (!mid) {
        throw_java(env, "Invalid callback");
        env->DeleteLocalRef(cls);
        return to_jstring(env, "{}");
    }

    std::string result = doGenerate(eng, env, callback, mid,
                                     from_jstring(env, prompt), cfg);

    env->DeleteLocalRef(cls);
    return to_jstring(env, result);

} catch (const std::exception &e) {
    LOGE("[Generate] exception: %s", e.what());
    throw_java(env, std::string("Generation failed: ") + e.what());
    return to_jstring(env, "{}");
} catch (...) {
    LOGE("[Generate] unknown exception");
    throw_java(env, "Generation failed (unknown)");
    return to_jstring(env, "{}");
}
}

// ── nativeCancel ───────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeCancel(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (eng) eng->cancel.store(true);
}

// ── nativeUnload ───────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeUnload(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (eng) eng->destroy();
}

// ── nativeRelease ──────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeRelease(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (eng) delete eng;
}

// ── nativeBenchmark ────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeBenchmark(
    JNIEnv *env, jobject, jlong handle, jint iters, jobject callback) {
try {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->model || !eng->ctx || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return to_jstring(env, "{}");
    }

    GenConfig cfg;
    cfg.maxTokens = 16;
    cfg.temperature = 0.0f;

    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Z)V");
    if (!mid) {
        throw_java(env, "Invalid callback");
        env->DeleteLocalRef(cls);
        return to_jstring(env, "{}");
    }

    float totalTps = 0, bestTps = 0;
    for (int i = 0; i < iters && !eng->cancel.load(); i++) {
        std::string stats = doGenerate(eng, env, callback, mid, "Hello", cfg);
        std::map<std::string, mini_json::Node> obj;
        if (mini_json::parseObject(stats, obj)) {
            float t = 0;
            auto it = obj.find("tokensPerSecond");
            if (it != obj.end()) t = (float)it->second.num;
            totalTps += t;
            bestTps = std::max(bestTps, t);
        }
    }

    env->DeleteLocalRef(cls);

    std::ostringstream o;
    o << "{\"iterations\":" << iters
      << ",\"averageTokensPerSecond\":" << (totalTps / std::max(1, iters))
      << ",\"bestTokensPerSecond\":" << bestTps
      << ",\"averagePromptTokensPerSecond\":0}";
    return to_jstring(env, o.str());

} catch (const std::exception &e) {
    LOGE("[Benchmark] exception: %s", e.what());
    throw_java(env, std::string("Benchmark failed: ") + e.what());
    return to_jstring(env, "{}");
} catch (...) {
    LOGE("[Benchmark] unknown exception");
    throw_java(env, "Benchmark failed (unknown)");
    return to_jstring(env, "{}");
}
}

// ── nativeMemoryPeak ───────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeMemoryPeak(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    return eng ? (jlong)eng->peakMemoryBytes : 0;
}

// ── nativeVulkanAvailable ──────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeVulkanAvailable(
    JNIEnv *, jobject) {
    return checkVulkan().ok ? JNI_TRUE : JNI_FALSE;
}

// ── nativeWarmUp ───────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeWarmUp(
    JNIEnv *env, jobject, jlong handle) {
try {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->model || !eng->ctx || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return to_jstring(env, "{}");
    }

    auto t0 = std::chrono::steady_clock::now();

    llama_memory_clear(llama_get_memory(eng->ctx), true);

    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    const bool addBos = llama_vocab_get_add_bos(vocab);
    std::vector<llama_token> toks = common_tokenize(eng->ctx, "Hi", addBos, true);

    llama_batch batch = llama_batch_init(toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++)
        common_batch_add(batch, toks[i], (llama_pos)i, {0}, true);
    int rc = llama_decode(eng->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) { throw_java(env, "Warm-up decode failed"); return to_jstring(env, "{}"); }

    if (eng->gpuLayersUsed > 0 && !eng->gpuInferenceVerified) {
        eng->gpuInferenceVerified = true;
        LOGI("[WarmUp] GPU inference verified");
    }

    llama_token tok = common_sampler_sample(eng->sampler, eng->ctx, -1);
    common_sampler_accept(eng->sampler, tok, true);
    llama_batch next = llama_batch_get_one(&tok, 1);
    llama_decode(eng->ctx, next);

    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    float tps = 1000.f / (float)std::max((int64_t)1, ms);

    LOGI("[WarmUp] %lldms (%.2f tok/s)", (long long)ms, tps);

    std::ostringstream o;
    o << "{\"warmUpTimeMs\":" << ms << ",\"tokensPerSecond\":" << tps << "}";
    return to_jstring(env, o.str());

} catch (const std::exception &e) {
    LOGE("[WarmUp] exception: %s", e.what());
    throw_java(env, std::string("Warm-up failed: ") + e.what());
    return to_jstring(env, "{}");
} catch (...) {
    LOGE("[WarmUp] unknown exception");
    throw_java(env, "Warm-up failed (unknown)");
    return to_jstring(env, "{}");
}
}

// ── nativeGetMemoryStats ───────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeGetMemoryStats(
    JNIEnv *env, jobject, jlong handle) {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng) return to_jstring(env, "{}");

    size_t mSz = eng->model ? llama_model_size(eng->model) : 0;
    size_t cSz = eng->ctx ? llama_state_get_size(eng->ctx) : 0;
    size_t gpuUsed = eng->gpuMemoryAllocatedBytes;

#ifdef GGML_USE_VULKAN
    if (eng->gpuLayersUsed > 0 && gpuUsed == 0) {
        gpuUsed = mSz * eng->gpuLayersUsed / std::max(1, eng->totalLayers) + cSz;
        size_t f = 0, t = 0;
        ggml_backend_vk_get_device_memory(0, &f, &t);
        eng->gpuMemoryFreeBytes = f;
        eng->gpuMemoryTotalBytes = t;
    }
#endif

    std::ostringstream o;
    o << "{\"modelSizeBytes\":" << mSz
      << ",\"contextSizeBytes\":" << cSz
      << ",\"gpuLayersOffloaded\":" << eng->gpuLayersUsed
      << ",\"totalLayers\":" << eng->totalLayers
      << ",\"backend\":\"" << (eng->gpuLayersUsed > 0 ? "vulkan" : "cpu")
      << "\",\"peakMemoryBytes\":" << eng->peakMemoryBytes
      << ",\"gpuMemoryUsedBytes\":" << gpuUsed
      << ",\"cpuMemoryUsedBytes\":" << (mSz + cSz > gpuUsed ? mSz + cSz - gpuUsed : 0)
      << ",\"gpuMemoryAllocatedBytes\":" << gpuUsed
      << ",\"gpuMemoryPeakBytes\":" << eng->gpuMemoryPeakBytes
      << ",\"gpuMemoryFreeBytes\":" << eng->gpuMemoryFreeBytes
      << ",\"gpuMemoryTotalBytes\":" << eng->gpuMemoryTotalBytes
      << ",\"gpuBufferCount\":" << eng->gpuBufferCount
      << ",\"gpuName\":\"" << json_escape(eng->gpuName)
      << "\",\"gpuDriverVersion\":\"" << json_escape(eng->gpuDriverVersion)
      << "\",\"gpuApiVersion\":\"" << json_escape(eng->gpuApiVersion)
      << "\",\"backendReason\":\"" << json_escape(eng->backendReason)
      << "\",\"gpuInferenceVerified\":" << (eng->gpuInferenceVerified ? "true" : "false")
      << "}";
    return to_jstring(env, o.str());
}

// ── nativeGetDebugInfo ─────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeGetDebugInfo(
    JNIEnv *env, jobject, jlong handle) {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->model) return to_jstring(env, "{}");

    const llama_model *m = eng->model;
    const llama_vocab *v = llama_model_get_vocab(m);
    llama_context *c = eng->ctx;

    char desc[128];
    llama_model_desc(m, desc, sizeof(desc));

    auto tokJson = [](const std::vector<llama_token> &ts) {
        std::ostringstream o; o << "[";
        for (size_t i = 0; i < ts.size(); i++) { if (i) o << ","; o << ts[i]; }
        o << "]"; return o.str();
    };

    std::ostringstream o;
    o << "{\"desc\":\"" << json_escape(desc)
      << "\",\"architecture\":\"" << json_escape(meta_str(m, "general.architecture"))
      << "\",\"tokenizerModel\":\"" << json_escape(meta_str(m, "tokenizer.ggml.model"))
      << "\",\"generalName\":\"" << json_escape(meta_str(m, "general.name"))
      << "\",\"backend\":\"" << (eng->gpuLayersUsed > 0 ? "vulkan" : "cpu")
      << "\",\"gpuName\":\"" << json_escape(eng->gpuName)
      << "\",\"gpuDriverVersion\":\"" << json_escape(eng->gpuDriverVersion)
      << "\",\"gpuApiVersion\":\"" << json_escape(eng->gpuApiVersion)
      << "\",\"gpuLayers\":" << eng->gpuLayersUsed
      << ",\"totalLayers\":" << eng->totalLayers
      << ",\"nCtxTrain\":" << llama_model_n_ctx_train(m)
      << ",\"nCtx\":" << (c ? llama_n_ctx(c) : 0)
      << ",\"nBatch\":" << (c ? llama_n_batch(c) : 0)
      << ",\"nUbatch\":" << eng->ctxParams.n_ubatch
      << ",\"nThreads\":" << eng->ctxParams.n_threads
      << ",\"nVocab\":" << (v ? llama_vocab_n_tokens(v) : 0)
      << ",\"kvType\":\"F16\",\"flashAttn\":\"" << (eng->useFlashAttention ? "AUTO" : "OFF") << "\""
      << ",\"quantization\":\"" << json_escape(llama_ftype_name(llama_model_ftype(m))) << "\""
      << ",\"sampler\":\"" << json_escape(eng->sampler ? common_sampler_print(eng->sampler) : "") << "\""
      << ",\"promptTokens\":" << eng->lastPromptTokens.size()
      << ",\"promptTokenIds\":" << tokJson(eng->lastPromptTokens)
      << ",\"generatedTokens\":" << eng->lastGeneratedTokens.size()
      << ",\"generatedTokenIds\":" << tokJson(eng->lastGeneratedTokens)
      << ",\"firstTokenMs\":" << eng->lastFirstTokenMs
      << ",\"stopReason\":\"" << json_escape(eng->lastStopReason)
      << "\",\"promptText\":\"" << json_escape(eng->lastPromptText)
      << "\",\"modelSizeBytes\":" << llama_model_size(m)
      << ",\"contextSizeBytes\":" << (c ? llama_state_get_size(c) : 0)
      << ",\"peakMemoryBytes\":" << eng->peakMemoryBytes
      << ",\"backendReason\":\"" << json_escape(eng->backendReason)
      << "\",\"gpuInferenceVerified\":" << (eng->gpuInferenceVerified ? "true" : "false")
      << "}";
    return to_jstring(env, o.str());
}

} // extern "C"
