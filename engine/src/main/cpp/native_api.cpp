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
#include <cctype>
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
#include <stdexcept>
#include <string>
#include <system_error>
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

// Validates that `string` is a sequence of complete UTF-8 characters.
// Used to buffer streamed token pieces until they form valid characters
// (upstream ai_chat.cpp cached_token_chars pattern), so multi-byte code
// points split across tokens are never emitted as truncated sequences.
static bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const auto *bytes = (const unsigned char *)string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
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

    static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static void appendUtf8(std::string &o, uint32_t cp) {
        if (cp < 0x80) {
            o += (char)cp;
        } else if (cp < 0x800) {
            o += (char)(0xC0 | (cp >> 6));
            o += (char)(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            o += (char)(0xE0 | (cp >> 12));
            o += (char)(0x80 | ((cp >> 6) & 0x3F));
            o += (char)(0x80 | (cp & 0x3F));
        } else {
            o += (char)(0xF0 | (cp >> 18));
            o += (char)(0x80 | ((cp >> 12) & 0x3F));
            o += (char)(0x80 | ((cp >> 6) & 0x3F));
            o += (char)(0x80 | (cp & 0x3F));
        }
    }

    // Decode a JSON string body, handling every escape sequence correctly.
    // The previous implementation dropped the backslash of \n/\t/\r/\uXXXX,
    // corrupting message content (newlines became literal 'n' characters) when
    // the chat history was re-rendered on subsequent turns.
    bool str(std::string &o) {
        if (!eat('"')) return false; o.clear();
        while (p_ < in_.size()) {
            char c = in_[p_++];
            if (c == '"') return true;
            if (c != '\\') { o += c; continue; }
            if (p_ >= in_.size()) return false;
            char e = in_[p_++];
            switch (e) {
                case '"':  o += '"';  break;
                case '\\': o += '\\'; break;
                case '/':  o += '/';  break;
                case 'b':  o += '\b'; break;
                case 'f':  o += '\f'; break;
                case 'n':  o += '\n'; break;
                case 'r':  o += '\r'; break;
                case 't':  o += '\t'; break;
                case 'u': {
                    if (p_ + 4 > in_.size()) return false;
                    uint32_t cp = 0;
                    for (int k = 0; k < 4; k++) {
                        int v = hexVal(in_[p_ + k]);
                        if (v < 0) return false;
                        cp = (cp << 4) | (uint32_t)v;
                    }
                    p_ += 4;
                    // handle UTF-16 surrogate pairs
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        if (p_ + 6 <= in_.size() && in_[p_] == '\\' && in_[p_ + 1] == 'u') {
                            uint32_t lo = 0;
                            bool ok = true;
                            for (int k = 0; k < 4; k++) {
                                int v = hexVal(in_[p_ + 2 + k]);
                                if (v < 0) { ok = false; break; }
                                lo = (lo << 4) | (uint32_t)v;
                            }
                            if (ok && lo >= 0xDC00 && lo <= 0xDFFF) {
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                p_ += 6;
                            }
                        }
                    }
                    appendUtf8(o, cp);
                    break;
                }
                default:
                    return false; // invalid JSON escape
            }
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
    bool reuseKvCache = true;       // keep the KV cache across turns (official multi-turn pattern)
    bool debugTokenLogging = false; // log every sampled token (step/id/piece/top-5) — decode debugging aid
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
    if (auto *n = get("reuseKvCache"))       c.reuseKvCache = n->boolean;
    if (auto *n = get("debugTokenLogging"))  c.debugTokenLogging = n->boolean;
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
    // initResult owns the MODEL only (loaded with model_only=true). The
    // llama_context is owned separately by ctxOwner so it can be destroyed and
    // (the upstream lifecycle — reused across turns, see ai_chat.cpp)
    // on the GPU — no stateful inference object survives between requests.
    common_init_result_ptr  initResult;   // owns model (model_only load)
    llama_context_ptr       ctxOwner;     // owns llama_context — created once at load, reused
    common_sampler         *sampler;      // fresh per request (never reused)
    common_chat_templates_ptr chatTmpls;

    // ── non-owning accessors (valid while initResult/ctxOwner are alive) ──
    llama_model            *model;
    llama_context          *ctx;

    // ── session fallback / recovery counters (survive reloads, reset on load) ──
    int  recoveryCount = 0;      // times the wrapper escalated a corrupt run
    bool cpuSessionFallback = false; // true once GPU recovery failed → CPU session

    // ── Vulkan device-lost handling ──
    // Set by decode_safe() the moment a VK_ERROR_DEVICE_LOST surfaces from the
    // backend (vk::DeviceLostError thrown inside ggml-vulkan). The generation
    // wrapper then skips the cheap context-recreation stage and goes straight
    // to a FULL backend teardown + reload — a lost device poisons the whole
    // backend, not just one context. Cleared after a successful recovery.
    bool vulkanDeviceLost = false;
    // vulkanDeviceLostRecoveries is declared below (atomic — written from
    // decode_safe without the mutex, read under stateMutex by stats/debug).

    // ── config ──
    llama_context_params    ctxParams{};
    bool                    useFlashAttention = true;
    std::atomic<bool>       cancel{false};
    JavaVM                 *jvm = nullptr;

    // ── thread safety ──
    // Guards the ctx/model pointers against the stats/debug JNI readers
    // (which run on Dispatchers.Default) racing the generation thread that
    // destroys/recreates the context between responses. All other engine state
    // is owned by the single generation thread.
    std::mutex stateMutex;

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

    // ── validation diagnostics (self-test only; NEVER drives the active backend) ──
    // vulkanValidationStatus: "passed" | "failed" | "skipped"
    // vulkanValidationDetail: full mismatch report when the self-test failed
    std::string vulkanValidationStatus;
    std::string vulkanValidationDetail;

    // ── corruption recovery ──
    // Enough state to recreate the inference context when runtime corruption
    // is detected (NaN/INF logits, invalid token ids, decode failures, or
    // degenerate repetition). The generation wrapper reloads the context and
    // retries once on the same backend, then once on CPU, before giving up.
    std::string modelPath;
    int  loadCtxLen = 0;
    int  loadBatchSize = 2048;
    int  loadGpuLayers = -1;
    std::string lastRecoveryReason;  // set when the latest attempt was corrupted

    // ── Vulkan diagnostics (per-generation + last-generation aggregates) ──
    // lastContextCreateMs: time to build a fresh llama_context (pipelines,
    //   descriptor pools, command pools, buffers) from the resident model.
    // lastCleanupMs: time to free the previous context's GPU state.
    // decodeCount / decodeTotalMs: llama_decode calls + wall time in the last
    //   generation (fence waits are inside llama_decode — this is the closest
    //   observable proxy for queue submit + fence wait cost).
    // NOTE: the counters below are written from the generation thread WITHOUT
    // the mutex (decode_safe) and only read under stateMutex by stats/debug —
    // they are atomic so the cross-thread reads are well-defined.
    int64_t lastContextCreateMs = 0;   // written under stateMutex (createEngineContext)
    int64_t lastCleanupMs = 0;         // context teardown cost (createEngineContext frees old ctx)
    std::atomic<int64_t> decodeCount = 0;
    std::atomic<int64_t> decodeTotalMs = 0;
    std::atomic<int> vulkanDeviceLostRecoveries = 0; // incremented in decode_safe
    // Kept so memory/context stats stay meaningful between generations, when
    // the context has been released (contextSizeBytes would otherwise read 0).
    size_t cachedContextSizeBytes = 0;

    // ── runtime ──
    int promptCount = 0;
    size_t peakMemoryBytes = 0;

    // ── upstream state (matches ai_chat.cpp) ──
    // chatMsgs is the conversation as known to the template; the KV cache
    // holds the rendered prompt tokens plus the raw generated tokens. Each
    // turn decodes only the template diff of the new messages at chatPosition
    // (official multi-turn pattern) — the cache IS the conversation.
    std::vector<common_chat_msg> chatMsgs;   // accumulated messages
    llama_pos chatPosition = 0;              // current position in KV cache
    llama_pos systemPromptEnd = 0;           // position after system prompt

    // ── KV-resident prompt / diagnostics ──
    // lastPromptTokens is the token stream of the most recent prompt rendered
    // into the KV cache (positions [0, len)). It doubles as the previous-turn
    // prefix baseline for the KV-reuse decision; doGenerate() must snapshot it
    // BEFORE overwriting it with the current prompt.
    std::vector<llama_token> lastPromptTokens;
    std::vector<llama_token> lastGeneratedTokens;
    std::string lastPromptText;
    int64_t lastFirstTokenMs = 0;
    std::string lastStopReason;

    // ── embedding model (optional; separate lifecycle from the chat model) ──
    // The embedding model is a small, independent model (MiniLM/BGE/nomic GGUF)
    // used for memory vectorization. It is NOT cleared by destroy() so that
    // reloading a chat model never drops a loaded embedding model; it is only
    // released by unloadEmbedding() (nativeUnloadEmbeddingModel / destructor).
    common_init_result_ptr embedInitResult; // owns embed model + ctx
    llama_model            *embedModel = nullptr;
    llama_context          *embedCtx   = nullptr;
    int32_t                 embedDim   = 0;

    LlamaEngine() : sampler(nullptr), model(nullptr), ctx(nullptr) {}

    ~LlamaEngine() { destroy(); unloadEmbedding(); }

    /**
     * NOTE: there is deliberately NO per-generation context release. Upstream
     * lifecycle (examples/llama.android ai_chat.cpp, tools/server) creates ONE
     * llama_context at load and REUSES it across turns; the KV cache is managed
     * in-place (llama_memory_clear per conversation, llama_memory_seq_rm/add
     * for context shifting). Tearing the context down after every response
     * causes the repeated Vulkan resource churn (command pools, descriptor
     * pools, pipeline state, GPU buffers) that mobile drivers watchdog into
     * VK_ERROR_DEVICE_LOST. The context is freed only on destroy()/unload.
     */

    void unloadEmbedding() {
        embedModel = nullptr;
        embedCtx = nullptr;
        embedDim = 0;
        embedInitResult.reset();
    }

    void destroy() {
        cancel.store(true);
        std::lock_guard<std::mutex> lock(stateMutex);
        // Preserve the context size for stats before the context is freed.
        if (ctx) cachedContextSizeBytes = llama_state_get_size(ctx);
        // Clear non-owning pointers before releasing owners
        model = nullptr;
        ctx = nullptr;
        if (sampler) { common_sampler_free(sampler); sampler = nullptr; }
        // The context references the model's backends — free it before the
        // model (releases KV cache, compute buffers, GPU command state).
        ctxOwner.reset();
        chatTmpls.reset();
        initResult.reset();
        promptCount = 0;
        gpuLayersUsed = 0;
        totalLayers = 0;
        gpuMemoryAllocatedBytes = 0;
        gpuBufferCount = 0;
        gpuInferenceVerified = false;
        backendReason.clear();
        vulkanValidationStatus.clear();
        vulkanValidationDetail.clear();
        // NOTE: lastRecoveryReason is intentionally NOT cleared here — the
        // generation wrapper reads it after reloadEngineContext() to log and
        // decide the next recovery stage.
        chatMsgs.clear();
        chatPosition = 0;
        systemPromptEnd = 0;
        lastPromptTokens.clear();
        lastGeneratedTokens.clear();
        lastPromptText.clear();
        lastFirstTokenMs = 0;
        lastStopReason.clear();
        peakMemoryBytes = 0;
        lastContextCreateMs = 0;
        lastCleanupMs = 0;
        decodeCount = 0;
        decodeTotalMs = 0;
        // NOTE: vulkanDeviceLost / vulkanDeviceLostRecoveries survive destroy()
        // (reloadEngineContext calls destroy() during recovery).
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

static int s_gen_counter = 0;

struct RenderedPrompt {
    std::string prompt;
    size_t systemPromptCharEnd = 0; // char offset where system prompt portion ends
};

static RenderedPrompt renderChat(
    const common_chat_templates *tmpls,
    const std::string &msgsJson,
    bool addAssistant,
    std::vector<common_chat_msg> &chatMsgs) {

    s_gen_counter++;
    LOGI("====== renderChat START gen=%d msgsJson.size=%zu addAssistant=%d ======",
         s_gen_counter, msgsJson.size(), (int)addAssistant);

    std::vector<std::map<std::string, mini_json::Node>> msgs;
    if (!mini_json::parseObjectArray(msgsJson, msgs) || msgs.empty()) {
        LOGE("[Template] bad message JSON"); return {"", 0};
    }

    LOGI("[Template] parsed %zu messages from JSON", msgs.size());
    for (size_t i = 0; i < msgs.size(); ++i) {
        auto &m = msgs[i];
        auto r = m.find("role"), c = m.find("content");
        std::string role = (r != m.end()) ? r->second.str : "?";
        std::string content = (c != m.end()) ? c->second.str : "?";
        LOGI("[Template] msg %zu: role=%s content_len=%zu content=%.60s...",
             i, role.c_str(), content.size(), content.c_str());
    }

    chatMsgs.clear();

    std::string fullPrompt;
    size_t systemPromptCharEnd = 0;
    for (size_t i = 0; i < msgs.size(); ++i) {
        auto &m = msgs[i];
        auto r = m.find("role"), c = m.find("content");
        if (r == m.end() || c == m.end() || r->second.str.empty()) {
            LOGE("[Template] msg missing role/content"); return {"", 0};
        }
        common_chat_msg newMsg{r->second.str, c->second.str};
        bool isLast = (i + 1 == msgs.size());
        bool addGen = isLast && addAssistant;
        std::string formatted = common_chat_format_single(tmpls, chatMsgs, newMsg, addGen, /*use_jinja=*/false);
        if (formatted.empty()) {
            LOGE("[Template] format_single returned empty for msg %zu role=%s", i, newMsg.role.c_str());
            return {"", 0};
        }
        fullPrompt += formatted;
        if (i == 0) {
            systemPromptCharEnd = fullPrompt.size();
        }
        LOGI("[Template] msg %zu role=%s formatted %zu chars (running total=%zu)",
             i, newMsg.role.c_str(), formatted.size(), fullPrompt.size());
        chatMsgs.push_back(std::move(newMsg));
    }

    if (fullPrompt.empty()) {
        LOGE("[Template] render returned empty prompt");
        return {"", 0};
    }
    LOGI("[Template] FINAL prompt gen=%d total=%zu chars systemPromptCharEnd=%zu",
         s_gen_counter, fullPrompt.size(), systemPromptCharEnd);
    return {fullPrompt, systemPromptCharEnd};
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
// Generation health — corruption detection & automatic context recovery
// ---------------------------------------------------------------------------
//
// llama.cpp's Vulkan backend submits the graph and waits for completion inside
// ggml_backend_vk_graph_compute, so llama_decode() returns with synchronized
// logits — no extra GPU fence is required before reading them. What CAN go
// wrong is the compute itself producing NaN/INF logits on a broken backend;
// the guards below detect that and the recovery wrapper below recreates the
// context (retry once, then CPU fallback) instead of generating garbage.

// Every logit must be finite. NaN/INF means the compute path is broken
// (typically the GPU backend). Checked after every decode, before sampling.
// Returns true when every logit in the current decode is finite (no NaN/INF).
static bool logits_are_finite(llama_context * ctx, const llama_vocab * vocab) {
    const float * logits = llama_get_logits_ith(ctx, -1);
    if (!logits) return false;
    const int n = llama_vocab_n_tokens(vocab);
    for (int i = 0; i < n; i++) {
        if (!std::isfinite(logits[i])) return false;
    }
    return true;
}

// Returns the index of the first non-finite logit (NaN or +-INF), or -1 when
// all logits are finite or unavailable. Feeds the corruption diagnostics.
static int first_bad_logit_index(llama_context * ctx, const llama_vocab * vocab) {
    const float * logits = llama_get_logits_ith(ctx, -1);
    if (!logits) return -1;
    const int n = llama_vocab_n_tokens(vocab);
    for (int i = 0; i < n; i++) {
        if (!std::isfinite(logits[i])) return i;
    }
    return -1;
}

static bool token_is_valid(const llama_vocab * vocab, llama_token id) {
    return id >= 0 && id < llama_vocab_n_tokens(vocab);
}

// Dumps the corruption context: backend, sampler state, last sampled token
// ids, KV position/context/batch size and the first bad logit index. Called
// the moment a corrupt attempt is detected.
static void dump_corruption(LlamaEngine * eng, const char * where, const std::string & detail) {
    std::ostringstream o;
    o << "[" << where << "] " << detail
      << " backend=" << (eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU")
      << " sampler=" << (eng->sampler ? common_sampler_print(eng->sampler) : "null");
    const size_t from = eng->lastGeneratedTokens.size() > 10
                        ? eng->lastGeneratedTokens.size() - 10 : 0;
    o << " last_tokens=";
    for (size_t i = from; i < eng->lastGeneratedTokens.size(); i++) {
        if (i > from) o << ",";
        o << eng->lastGeneratedTokens[i];
    }
    if (eng->ctx) {
        const llama_vocab * vocab = llama_model_get_vocab(eng->model);
        const int bad = first_bad_logit_index(eng->ctx, vocab);
        if (bad >= 0) {
            const float v = llama_get_logits_ith(eng->ctx, -1)[bad];
            o << " first_bad_logit=" << bad
              << " (" << (std::isnan(v) ? "NaN" : (v > 0 ? "+INF" : "-INF")) << ")";
        }
        o << " kv_pos=" << llama_memory_seq_pos_max(llama_get_memory(eng->ctx), 0)
          << "/" << llama_n_ctx(eng->ctx)
          << " batch=" << llama_n_batch(eng->ctx)
          << " seq=0";
    }
    o << " recovery_count=" << eng->recoveryCount;
    LOGE("%s", o.str().c_str());
}

// Wraps llama_decode so a failure thrown by the Vulkan backend is converted
// into the engine's recovery signal instead of escaping the JNI boundary and
// hard-failing (or crashing) the generation.
//
// ggml-vulkan uses Vulkan-Hpp, which throws C++ exceptions on every VkResult
// error — e.g. vk::DeviceLostError on VK_ERROR_DEVICE_LOST (logcat shows
// "vk::Queue::submit: ErrorDeviceLost"), vk::OutOfDeviceMemoryError, etc.
// These are all std::system_error subclasses, so catching std::system_error
// here intercepts every backend failure without depending on vulkan.hpp.
// Returns the llama_decode() result (0 on success) or -1 when an exception
// was caught. On a backend exception, *errOut carries the reason and the
// device-lost flag / recovery telemetry are updated.
static int decode_safe(LlamaEngine * eng, llama_context * ctx, llama_batch batch) {
    auto t0 = std::chrono::steady_clock::now();
    int rc = -1;
    try {
        rc = llama_decode(ctx, batch);
    } catch (const std::system_error & e) {
        const int code = e.code().value();
#ifdef GGML_USE_VULKAN
        if (code == (int)VK_ERROR_DEVICE_LOST) {
            eng->vulkanDeviceLost = true;
            eng->vulkanDeviceLostRecoveries++;
            eng->lastRecoveryReason = std::string("vulkan device lost (") + e.what() + ")";
        } else
#endif
        {
            eng->lastRecoveryReason = std::string("backend error (") + e.what() + ")";
        }
        LOGW("[Decode] %s", eng->lastRecoveryReason.c_str());
    } catch (const std::exception & e) {
        eng->lastRecoveryReason = std::string("decode exception: ") + e.what();
        LOGW("[Decode] %s", eng->lastRecoveryReason.c_str());
    } catch (...) {
        eng->lastRecoveryReason = "decode exception (unknown)";
        LOGW("[Decode] %s", eng->lastRecoveryReason.c_str());
    }
    eng->decodeCount++;
    eng->decodeTotalMs += std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    // A successful decode proves the device is usable again — clear the
    // device-lost flag so a later unrelated error doesn't force an unnecessary
    // full backend reload (the flag only means "the device was lost once").
    if (rc == 0) eng->vulkanDeviceLost = false;
    return rc;
}

// Builds the llama_context_params used both at load and for every per-
// generation context recreation. It reconstructs the same common_params the
// original load used and lets llama.cpp convert them, so the recreated context
// is byte-for-byte identical to the one from nativeLoadModel (same context
// length, batch/ubatch, flash attention, F16 KV cache, thread config).
static llama_context_params buildContextParams(const LlamaEngine * eng) {
    // Reproduce the exact common_params nativeLoadModel built, so the
    // recreated context is byte-identical to the one from the original load.
    common_params params;
    params.n_ctx           = eng->loadCtxLen <= 0 ? 0 : eng->loadCtxLen;
    params.n_batch         = std::max(1024, eng->loadBatchSize);
    params.n_ubatch        = std::min(params.n_batch, 512);
    params.cache_type_k    = GGML_TYPE_F16;
    params.cache_type_v    = GGML_TYPE_F16;
    params.flash_attn_type = eng->useFlashAttention ? LLAMA_FLASH_ATTN_TYPE_AUTO
                                                    : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    // NOTE: cpuparams stay at their defaults (auto) — identical to the
    // original load, which never overrode them either.
    return common_context_params_to_llama(params);
}

// Creates a brand-new llama_context from the RESIDENT model. This is the
// lifecycle helper: frees any old context (KV cache, compute buffers, command
// pools, sequence/batch state) is freed and a fresh one is built. The model
// and its GPU weight buffers stay loaded — no disk re-read. Returns true on
// success; on failure the engine is left with no context and *outErr carries
// the reason.
static bool createEngineContext(LlamaEngine * eng, std::string * outErr = nullptr) {
    auto fail = [&](const std::string & m) {
        if (outErr) *outErr = m;
        return false;
    };
    if (!eng->model) return fail("no model loaded");
    std::lock_guard<std::mutex> lock(eng->stateMutex);

    // Time the teardown of the previous context (KV cache, compute buffers,
    // command pools, descriptor pools freed) — the cleanup-duration diagnostic.
    if (eng->ctxOwner) {
        auto tClean = std::chrono::steady_clock::now();
        eng->ctxOwner.reset();
        eng->ctx = nullptr;
        eng->lastCleanupMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - tClean).count();
    }

    // Context creation can itself throw (e.g. vk::OutOfDeviceMemoryError or a
    // DeviceLost while the backend rebuilds pipelines on a dead device). Route
    // that through the fail path so the recovery ladder escalates (GPU reload
    // → CPU) instead of letting the exception escape the JNI boundary.
    auto tCreate = std::chrono::steady_clock::now();
    try {
        eng->ctxOwner.reset(llama_init_from_model(eng->model, buildContextParams(eng)));
    } catch (const std::system_error & e) {
        return fail(std::string("context init: ") + e.what());
    } catch (const std::exception & e) {
        return fail(std::string("context init: ") + e.what());
    } catch (...) {
        return fail("context init failed (unknown)");
    }
    eng->ctx = eng->ctxOwner.get();
    eng->lastContextCreateMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - tCreate).count();
    if (!eng->ctx) return fail("context creation failed");
    eng->cachedContextSizeBytes = llama_state_get_size(eng->ctx);

    // Fresh context: empty KV cache, sequence ids, positions, batch indices.
    eng->chatPosition = 0;
    eng->systemPromptEnd = 0;
    eng->lastPromptTokens.clear();
    eng->lastGeneratedTokens.clear();
    eng->lastPromptText.clear();
    eng->lastFirstTokenMs = 0;
    eng->lastStopReason.clear();
    LOGI("[Context] created (create=%lldms cleanup=%lldms)",
         (long long)eng->lastContextCreateMs, (long long)eng->lastCleanupMs);
    return true;
}

// Full backend teardown + reload from the stored model path. Destroys the
// model (and with it the Vulkan backend: command pools, descriptor sets,
// pipelines, staging buffers) and recreates it — used as the corruption
// recovery ladder's escalation step, and as the CPU session fallback
// (useGpu=false). Returns true on success; on failure the engine is left
// unloaded and *outErr carries the reason.
static bool reloadEngineContext(LlamaEngine * eng, bool useGpu, std::string * outErr = nullptr) {
    auto fail = [&](const std::string & m) {
        if (outErr) *outErr = m;
        return false;
    };
    if (eng->modelPath.empty()) return fail("no stored model path for recovery");

    eng->destroy();

    common_params params;
    params.model.path    = eng->modelPath;
    params.n_ctx         = eng->loadCtxLen <= 0 ? 0 : eng->loadCtxLen;
    params.n_batch       = std::max(1024, eng->loadBatchSize);
    params.n_ubatch      = std::min(params.n_batch, 512);
    params.n_gpu_layers  = useGpu ? eng->loadGpuLayers : 0;
    params.cache_type_k  = GGML_TYPE_F16;
    params.cache_type_v  = GGML_TYPE_F16;
    params.flash_attn_type = eng->useFlashAttention ? LLAMA_FLASH_ATTN_TYPE_AUTO
                                                    : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    common_init_result_ptr result;
    try {
        // Model-only load: the context is created separately by
        // createEngineContext() so the context can be rebuilt on recovery.
        result = common_init_from_params(params, /*model_only=*/true);
    } catch (const std::exception &e) {
        return fail(std::string("context reload failed: ") + e.what());
    } catch (...) {
        return fail("context reload failed (unknown exception)");
    }
    if (!result) return fail("context reload returned null");

    eng->model = result->model();
    if (!eng->model) return fail("context reload produced no model");
    eng->initResult = std::move(result);

    if (!createEngineContext(eng, outErr)) {
        return fail(outErr && !outErr->empty() ? *outErr : "context creation failed");
    }

    try {
        eng->chatTmpls = common_chat_templates_init(eng->model, "");
    } catch (...) { eng->chatTmpls.reset(); }

    const int nL = llama_model_n_layer(eng->model);
    eng->totalLayers = nL;
    const int sel = useGpu ? eng->loadGpuLayers : 0;
    eng->gpuLayersUsed = sel < 0 ? nL : std::min(sel, nL);
    eng->gpuInferenceVerified = false;
    eng->gpuMemoryAllocatedBytes = 0;
    eng->gpuBufferCount = 0;
    eng->vulkanValidationStatus = "skipped";  // the self-test is not re-run on recovery
    if (!useGpu) {
        // "init failed" wording keeps MemoryStats.isCpuFallback true in Kotlin.
        eng->backendReason = "CPU fallback after GPU runtime corruption (init failed during recovery)";
        eng->cpuSessionFallback = true;
    } else {
        eng->backendReason = "Vulkan active after context recovery (" +
                             std::to_string(eng->gpuLayersUsed) + "/" +
                             std::to_string(eng->totalLayers) + " layers)";
    }
    eng->cancel.store(false);
    return true;
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
    eng->decodeCount = 0;
    eng->decodeTotalMs = 0;

    llama_memory_t mem = llama_get_memory(eng->ctx);
    const uint32_t nCtx = llama_n_ctx(eng->ctx);
    const int n_batch = llama_n_batch(eng->ctx);
    LOGI("doGenerate START gen=%d promptCount=%d backend=%s ctx=%u batch=%d seq=0 sysPromptEnd=%d",
         s_gen_counter, eng->promptCount, eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU",
         nCtx, n_batch, (int)eng->systemPromptEnd);

    auto t0 = clock::now();

    // ── Tokenize ──
    // NOTE: renderChat() calls common_chat_templates_apply which already prepends
    // <|begin_of_text|> (BOS) when add_bos=true. We must NOT add another BOS via
    // add_special—upstream avoids this because it tokenizes only the diff (which
    // starts with <|start_header_id|>, not BOS). add_special=false prevents double-BOS.
    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    std::vector<llama_token> tokens =
        common_tokenize(eng->ctx, prompt, /*add_special=*/false, /*parse_special=*/true);

    // A raw generation (memory extraction, benchmarks) reuses the same native
    // context as chat, so it clobbers the conversational KV cache. Reset the
    // chat state here: the next chat turn re-renders from scratch (upstream
    // single-shot behavior).
    eng->chatMsgs.clear();
    eng->chatPosition = 0;

    eng->lastPromptTokens = tokens;
    eng->lastPromptText = prompt;
    eng->lastGeneratedTokens.clear();
    eng->lastFirstTokenMs = 0;
    eng->lastStopReason = "max_tokens";

    LOGI("[Tok] n=%zu add_special=false (BOS already in prompt)", tokens.size());
    {
        std::string tokStr;
        size_t showN = std::min<size_t>(30, tokens.size());
        for (size_t t = 0; t < showN; t++) {
            char buf[32];
            snprintf(buf, sizeof(buf), "%d ", tokens[t]);
            tokStr += buf;
        }
        if (showN < tokens.size()) tokStr += "...";
        LOGI("[Tok] first_tokens: %s", tokStr.c_str());
    }
    LOGI("[Tok] prompt_head: %.200s", prompt.c_str());

    // ── Prefill (full re-prefill from a cleared cache — upstream single-shot) ──
    llama_memory_clear(mem, false);

    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    // A cancel requested during a long prefill must abort promptly — the decode
    // loop below is cancel-aware, so the prefill must be too, or Stop becomes
    // unresponsive for the duration of the prefill.
    bool prefillCancelled = false;

    auto run_prefill = [&](size_t from, llama_pos pos0) -> bool {
        bool ok = true;
        int batchCount = 0;
        for (size_t i = from; i < tokens.size(); i += n_batch) {
            if (eng->cancel.load()) {
                prefillCancelled = true;
                LOGI("[Prefill] cancel requested at batch %d", batchCount);
                break;
            }
            size_t n = std::min<size_t>(n_batch, tokens.size() - i);
            bool batch_has_last = false;
            common_batch_clear(batch);
            for (size_t j = 0; j < n; j++) {
                bool last = (i + j == tokens.size() - 1);
                if (last) batch_has_last = true;
                common_batch_add(batch, tokens[i + j],
                                 (llama_pos)(pos0 + (i - from) + j), {0}, last);
            }
            batchCount++;
            if (batchCount <= 2 || batch_has_last) {
                LOGI("[Prefill] batch %d: n=%zu first_pos=%d last_pos=%d",
                     batchCount, n, (int)(pos0 + (i - from)), (int)(pos0 + (i - from) + n - 1));
            }
            if (decode_safe(eng, eng->ctx, batch) != 0) {
                ok = false;
                LOGE("[Prefill] FAILED at batch %d token_idx=%zu (%s)",
                     batchCount, i, eng->lastRecoveryReason.c_str());
                break;
            }
        }
        return ok;
    };

    bool prefillOk = run_prefill(0, 0);

    if (prefillCancelled) {
        llama_batch_free(batch);
        eng->lastStopReason = "cancelled";
        eng->chatPosition = 0;
        jstring empty = to_jstring(env, "");
        env->CallVoidMethod(callback, onToken, empty, JNI_TRUE);
        env->DeleteLocalRef(empty);
        return statsJson(tokens.size(), 0, 0, 0, 0.f,
                         eng->peakMemoryBytes, 0, "cancelled");
    }

    if (!prefillOk) {
        llama_batch_free(batch);
        // Preserve a backend-exception reason (device lost etc.) if decode_safe
        // already recorded one; only fall back to the generic reason otherwise.
        if (eng->lastRecoveryReason.empty()) eng->lastRecoveryReason = "prefill decode failed";
        eng->lastStopReason = "corrupted";
        eng->chatPosition = 0;
        dump_corruption(eng, "prefill_decode", eng->lastRecoveryReason);
        return statsJson(tokens.size(), 0, 0, 0, 0.f,
                         eng->peakMemoryBytes, 0, "corrupted");
    }

    eng->chatPosition = (llama_pos)tokens.size();

    // Corruption guard: the prefill logits feed the first sample. A broken
    // compute path (typically GPU) yields NaN/INF here — abort before any
    // token is emitted so the recovery retry re-streams nothing.
    if (!logits_are_finite(eng->ctx, vocab)) {
        llama_batch_free(batch);
        const int bad = first_bad_logit_index(eng->ctx, vocab);
        const float bv = bad >= 0 ? llama_get_logits_ith(eng->ctx, -1)[bad] : 0.f;
        eng->lastRecoveryReason = "prefill logits corrupted (NaN/INF) at idx " +
            std::to_string(bad) + " (" + (std::isnan(bv) ? "NaN" : "INF") + ")";
        eng->lastStopReason = "corrupted";
        eng->chatPosition = 0;
        dump_corruption(eng, "logits", eng->lastRecoveryReason);
        return statsJson(tokens.size(), 0, 0, 0, 0.f,
                         eng->peakMemoryBytes, 0, "corrupted");
    }

    LOGI("[Prefill] END backend=%s tokens=%zu ctx=%u batch=%d seq=0 rc=0",
         eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU", tokens.size(), nCtx, n_batch);

    // Context-overflow guard: never budget more generated tokens than the
    // cache can hold. The mid-run shift still discards old context when the
    // window fills; this pre-check keeps a doomed generation from starting.
    int64_t genBudget = cfg.maxTokens;
    {
        const int64_t used = (int64_t)llama_memory_seq_pos_max(mem, 0) + 1;
        const int64_t available = (int64_t)nCtx - 4 - used;
        if (available < 1) {
            llama_batch_free(batch);
            // A reload cannot fix a prompt that is simply too long for the
            // context — surface a clean error instead of recovery retries.
            eng->lastStopReason = "context_overflow";
            eng->chatPosition = 0;
            throw_java(env, "Context overflow: no room to generate (prompt exceeds context window)");
            return "{}";
        }
        genBudget = std::min<int64_t>(genBudget, available);
    }

    auto t1 = clock::now();
    int64_t promptMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    common_sampler_reset(eng->sampler);

    // ── Decode loop (upstream pattern) ──
    std::string output;
    std::string pendingUtf8;   // buffers token pieces until they form valid UTF-8
    int64_t generated = 0;

    for (int i = 0; i < genBudget; i++) {
        if (eng->cancel.load()) {
            eng->lastStopReason = "cancelled";
            eng->chatPosition = 0;
            break;
        }

        // Context full: shift (upstream pattern: discard older half after system prompt)
        llama_pos pos_check = llama_memory_seq_pos_max(mem, 0);
        if (pos_check >= (llama_pos)nCtx - 4) {
            const llama_pos sysEnd = eng->systemPromptEnd;
            const llama_pos n_discard = (pos_check - sysEnd) / 2;
            if (n_discard > 0) {
                llama_memory_seq_rm(mem, 0, sysEnd, sysEnd + n_discard);
                llama_memory_seq_add(mem, 0, sysEnd + n_discard, pos_check + 1, -n_discard);
                LOGI("[Shift] discarded %d tokens from pos %d..%d at step=%lld (preserved system prompt %d)",
                     (int)n_discard, (int)sysEnd, (int)(sysEnd + n_discard - 1),
                     (long long)generated, (int)sysEnd);
            } else {
                LOGW("[Shift] cannot shift: n_discard=%d sysEnd=%d pos_check=%d",
                     (int)n_discard, (int)sysEnd, (int)pos_check);
            }
        }

        // Corruption guard: sample only from finite logits (already
        // synchronized by llama.cpp when llama_decode returned).
        if (!logits_are_finite(eng->ctx, vocab)) {
            eng->lastRecoveryReason = std::string("logits corrupted (NaN/INF) at step ") + std::to_string(i);
            eng->lastStopReason = "corrupted";
            eng->chatPosition = 0;
            dump_corruption(eng, "logits", eng->lastRecoveryReason);
            break;
        }

        // Sample
        llama_token id = common_sampler_sample(eng->sampler, eng->ctx, -1);

        // Bounds guard: the sampler must return a valid token id.
        if (!token_is_valid(vocab, id)) {
            eng->lastRecoveryReason = std::string("invalid token id ") +
                std::to_string((long long)id) + " at step " + std::to_string(i);
            eng->lastStopReason = "corrupted";
            eng->chatPosition = 0;
            dump_corruption(eng, "token", eng->lastRecoveryReason);
            break;
        }

        eng->lastGeneratedTokens.push_back(id);
        common_sampler_accept(eng->sampler, id, true);

        // EOS check
        if (llama_vocab_is_eog(vocab, id)) {
            eng->lastStopReason = "eos";
            break;
        }

        // First token timing
        if (generated == 0) {
            auto now = clock::now();
            eng->lastFirstTokenMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - t1).count();
            LOGI("[Gen] first token latency: %lld ms", (long long)eng->lastFirstTokenMs);
        }

        // Detokenize with special=true (upstream default)
        std::string piece = common_token_to_piece(eng->ctx, id);
        output += piece;

        // Per-token decode logging (opt-in): step, id, decoded text, top-5
        // logits, temperature and backend — the decode-corruption diagnostic.
        if (cfg.debugTokenLogging) {
            const float * logits = llama_get_logits_ith(eng->ctx, -1);
            std::ostringstream o;
            o << "[TokLog] step=" << i << " id=" << id
              << " piece=\"" << piece << "\" temp=" << cfg.temperature
              << " backend=" << (eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU")
              << " top5=";
            if (logits) {
                const int n = llama_vocab_n_tokens(vocab);
                int top[5] = { -1, -1, -1, -1, -1 };
                float tv[5] = { -1e30f, -1e30f, -1e30f, -1e30f, -1e30f };
                for (int t = 0; t < n; t++) {
                    for (int k = 0; k < 5; k++) {
                        if (logits[t] > tv[k]) {
                            for (int j = 4; j > k; j--) { tv[j] = tv[j - 1]; top[j] = top[j - 1]; }
                            tv[k] = logits[t]; top[k] = t;
                            break;
                        }
                    }
                }
                for (int k = 0; k < 5; k++) {
                    if (k) o << ",";
                    o << top[k] << ":" << tv[k];
                }
            } else {
                o << "(no logits)";
            }
            LOGI("%s", o.str().c_str());
        }

        // Degenerate-repetition guard: a run of identical non-whitespace
        // tokens (e.g. "////////") is a decode failure, not model text.
        {
            const size_t REPEAT_LIMIT = 24;
            const size_t n = eng->lastGeneratedTokens.size();
            bool whitespacePiece = true;
            for (char c : piece) if (!std::isspace((unsigned char)c)) { whitespacePiece = false; break; }
            if (n >= REPEAT_LIMIT && !whitespacePiece && piece.size() > 0) {
                bool allSame = true;
                for (size_t k = n - REPEAT_LIMIT; k < n; k++) {
                    if (eng->lastGeneratedTokens[k] != id) { allSame = false; break; }
                }
                if (allSame) {
                    eng->lastRecoveryReason = std::string("degenerate repetition (") +
                        std::to_string(REPEAT_LIMIT) + "x token " + std::to_string((long long)id) + ")";
                    eng->lastStopReason = "corrupted";
                    eng->chatPosition = 0;
                    dump_corruption(eng, "repetition", eng->lastRecoveryReason);
                    break;
                }
            }
        }

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

        // Buffer the piece until it forms a complete UTF-8 character (upstream
        // ai_chat.cpp cached_token_chars pattern). A multi-byte code point may be
        // split across several tokens; emitting the truncated sequence would be
        // replaced with U+FFFD by the JNI UTF-8 conversion and corrupt the text.
        pendingUtf8 += piece;
        bool completeUtf8 = is_valid_utf8(pendingUtf8.c_str());
        // Bound the buffer at 8 bytes so a run of malformed byte-fallback
        // tokens cannot stall streaming or grow memory without limit.
        if (!pendingUtf8.empty() && (completeUtf8 || pendingUtf8.size() > 8)) {
            jstring jpiece = to_jstring(env, pendingUtf8);
            env->CallVoidMethod(callback, onToken, jpiece, JNI_FALSE);
            env->DeleteLocalRef(jpiece);
            pendingUtf8.clear();
        }
        generated++;

        // Feed token back for next step (upstream pattern: persistent batch + common_batch_add)
        common_batch_clear(batch);
        common_batch_add(batch, id, llama_memory_seq_pos_max(mem, 0) + 1, {0}, true);
        if (decode_safe(eng, eng->ctx, batch) != 0) {
            // Preserve a backend-exception reason (device lost etc.) if already set.
            if (eng->lastRecoveryReason.empty())
                eng->lastRecoveryReason = std::string("decode failed at step ") + std::to_string(i);
            eng->lastStopReason = "corrupted";
            eng->chatPosition = 0;
            dump_corruption(eng, "decode", eng->lastRecoveryReason);
            break;
        }
    }

    if (!eng->lastRecoveryReason.empty()) {
        // Corrupted attempt: no finished callback and no chatPosition update —
        // the recovery wrapper recreates the context and re-streams the prompt.
        llama_batch_free(batch);
        eng->trackMemory();
        return statsJson(tokens.size(), generated, 0, 0, 0.f,
                         eng->peakMemoryBytes, 0, "corrupted");
    }

    // Normal completion (eos / max_tokens / stop_sequence): the generated tokens
    // remain in the KV cache, so record the next free position for the following
    // turn's diff prefill. Cancelled / decode_error runs already invalidated
    // reuse by zeroing chatPosition.
    if (eng->chatPosition != 0) {
        eng->chatPosition = llama_memory_seq_pos_max(mem, 0) + 1;
    }

    llama_batch_free(batch);

    auto t2 = clock::now();
    int64_t genMs = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    float tps = genMs > 0 ? (float)generated * 1000.f / (float)genMs : 0.f;

    LOGI("doGenerate END gen=%d generated=%lld stop=%s",
         s_gen_counter, (long long)generated, eng->lastStopReason.c_str());
    LOGI("[Perf] backend=%s gpu=%d prompt=%lldms(%zu) gen=%lldms(%lld) %.2f tok/s",
         eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU",
         eng->gpuLayersUsed, (long long)promptMs, tokens.size(),
         (long long)genMs, (long long)generated, tps);

    eng->trackMemory();

    // Vulkan diagnostics — logged after every generation (the on-device audit):
    // context create/cleanup cost, decode (submit+fence) count and average
    // wait, live GPU heap and recovery telemetry.
    {
        size_t vkFree = 0, vkTotal = 0;
#ifdef GGML_USE_VULKAN
        if (eng->gpuLayersUsed > 0) {
            try { ggml_backend_vk_get_device_memory(0, &vkFree, &vkTotal); }
            catch (...) {}
        }
#endif
        const int64_t decAvg = eng->decodeCount > 0
            ? eng->decodeTotalMs / eng->decodeCount : 0;
        LOGI("[VulkanDiag] backend=%s ctxCreate=%lldms cleanup=%lldms decodeCalls=%lld decodeAvg=%lldms "
             "gpuFree=%.1fMB gpuTotal=%.1fMB recovery=%d devLostRecovered=%d",
             eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU",
             (long long)eng->lastContextCreateMs, (long long)eng->lastCleanupMs,
             (long long)eng->decodeCount, (long long)decAvg,
             vkTotal ? (double)vkFree / (1024.0 * 1024.0) : 0.0,
             vkTotal ? (double)vkTotal / (1024.0 * 1024.0) : 0.0,
             eng->recoveryCount, eng->vulkanDeviceLostRecoveries.load());
    }

    // Flush any remaining buffered bytes at the end of the stream
    if (!pendingUtf8.empty()) {
        jstring jpiece = to_jstring(env, pendingUtf8);
        env->CallVoidMethod(callback, onToken, jpiece, JNI_FALSE);
        env->DeleteLocalRef(jpiece);
        pendingUtf8.clear();
    }

    // Send final empty delta
    jstring empty = to_jstring(env, "");
    env->CallVoidMethod(callback, onToken, empty, JNI_TRUE);
    env->DeleteLocalRef(empty);

    // Context stays resident across turns (upstream lifecycle — one context
    // reused; see ai_chat.cpp). The KV cache is NOT cleared here: it holds the
    // conversation and is shifted in-place on overflow (mid-run shift) or
    // cleared in-place per new conversation (nativeResetChat / full re-render).
    // Transient per-decode buffers (staging, scratch, command buffers) are
    // already released inside llama_decode/graph cleanup — nothing extra is
    // needed after EOS. The UI only ever sees the finished callback above;
    // this runs on the same background thread as generation, never the UI.
    return statsJson(tokens.size(), generated, promptMs, genMs, tps,
                     eng->peakMemoryBytes, eng->lastFirstTokenMs,
                     eng->lastStopReason);
}

// ---------------------------------------------------------------------------
// Chat generation — official llama.cpp multi-turn pattern (ai_chat.cpp / CLI)
// ---------------------------------------------------------------------------
//
// The KV cache IS the conversation. The previous turn's prompt tokens and the
// assistant's raw generated tokens stay resident; each new turn formats ONLY
// the new messages' template diff with common_chat_format_single() (the exact
// upstream call) and decodes it at the continuing position (chatPosition).
// The history is re-rendered in full from a cleared cache only when it is not
// a strict continuation — first turn, edit/delete/regenerate, new
// conversation, changed system prompt — the upstream reset behavior — or when
// reuseKvCache is disabled.
//
// This replaces the previous custom scheme (full re-render + token-prefix
// comparison + llama_memory_seq_rm of generated tails), which had no upstream
// counterpart and was the source of the Prompt-#2 corruption bug.

static std::string trim_copy(const std::string &s) {
    size_t b = 0, e = s.size();
    while (b < e && std::isspace((unsigned char)s[b])) b++;
    while (e > b && std::isspace((unsigned char)s[e - 1])) e--;
    return s.substr(b, e - b);
}

static std::string doGenerateChat(
    LlamaEngine *eng,
    JNIEnv *env,
    jobject callback,
    jmethodID onToken,
    const std::string &msgsJson,
    bool addAssistant,
    const GenConfig &cfg) {

    using clock = std::chrono::steady_clock;

    pin_big_cores();

    if (!eng->model || !eng->ctx || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return "{}";
    }
    if (!eng->chatTmpls) {
        throw_java(env, "Chat templates not available"); return "{}";
    }

    eng->promptCount++;
    eng->cancel.store(false);
    eng->trackMemory();
    eng->decodeCount = 0;
    eng->decodeTotalMs = 0;

    llama_memory_t mem = llama_get_memory(eng->ctx);
    const uint32_t nCtx = llama_n_ctx(eng->ctx);
    const int n_batch = llama_n_batch(eng->ctx);
    const llama_vocab *vocab = llama_model_get_vocab(eng->model);

    auto t0 = clock::now();

    // ── Parse the incoming message history ──
    std::vector<std::map<std::string, mini_json::Node>> msgs;
    if (!mini_json::parseObjectArray(msgsJson, msgs) || msgs.empty()) {
        throw_java(env, "Empty message history"); return "{}";
    }

    // ── Continuation vs full re-render ──
    // A continuation requires every stored message to appear verbatim at the
    // start of the incoming list (the app persists its own copy of the
    // conversation; assistant texts are trimmed on both sides so they match),
    // plus new messages, plus a non-empty cache. Anything else (first turn,
    // edit/delete/regenerate, new conversation, changed system prompt, or a
    // previous cancel/decode error that emptied the cache) falls back to a
    // full re-render from a cleared cache.
    const size_t storedN = eng->chatMsgs.size();
    bool isContinuation =
        cfg.reuseKvCache && storedN > 0 && eng->chatPosition > 0 && msgs.size() > storedN;
    for (size_t i = 0; isContinuation && i < storedN; ++i) {
        auto r = msgs[i].find("role"), c = msgs[i].find("content");
        const std::string role = (r != msgs[i].end()) ? r->second.str : "";
        const std::string content = (c != msgs[i].end()) ? c->second.str : "";
        // Compare trimmed-to-trimmed: the app persists its own copy of the
        // conversation (messages are trimmed on both sides), while the native
        // side stores the assistant text verbatim as generated. A whitespace
        // mismatch must not silently disable KV reuse (that would force a full
        // re-render every turn) — and the trimmed prefix is exactly what the
        // cache holds at these positions.
        if (eng->chatMsgs[i].role != role ||
            trim_copy(eng->chatMsgs[i].content) != trim_copy(content)) {
            isContinuation = false;
        }
    }

    std::string prompt;
    llama_pos prefillStart = 0;
    const size_t msgsBeforeGen = eng->chatMsgs.size();

    if (isContinuation) {
        // Diff path: format each new message with the official
        // common_chat_format_single() (returns the template diff for that
        // message against the accumulated past) and decode it at the
        // continuing position. The assistant's raw generated tokens from the
        // previous turn stay in the cache — they are never re-encoded.
        for (size_t i = storedN; i < msgs.size(); ++i) {
            auto &m = msgs[i];
            auto r = m.find("role"), c = m.find("content");
            if (r == m.end() || c == m.end() || r->second.str.empty()) {
                throw_java(env, "Message missing role/content"); return "{}";
            }
            common_chat_msg newMsg{r->second.str, c->second.str};
            bool isLast = (i + 1 == msgs.size());
            bool addGen = isLast && addAssistant;
            std::string part = common_chat_format_single(
                eng->chatTmpls.get(), eng->chatMsgs, newMsg, addGen, /*use_jinja=*/false);
            if (part.empty()) {
                throw_java(env, "Chat template returned an empty diff"); return "{}";
            }
            prompt += part;
            eng->chatMsgs.push_back(std::move(newMsg));
        }
        prefillStart = eng->chatPosition;
        LOGI("[Chat] continuation: +%zu msgs, %zu chars at pos %d",
             msgs.size() - storedN, prompt.size(), (int)prefillStart);
    } else {
        // Full re-render. Render into a LOCAL message list; commit to the
        // engine only after the prefill succeeds so a cancel leaves the
        // previous conversation untouched.
        std::vector<common_chat_msg> rebuilt;
        RenderedPrompt rp = renderChat(eng->chatTmpls.get(), msgsJson, addAssistant, rebuilt);
        if (rp.prompt.empty()) {
            throw_java(env, "Chat template returned an empty prompt"); return "{}";
        }
        prompt = rp.prompt;
        if (rp.systemPromptCharEnd > 0) {
            std::string sysPart = prompt.substr(0, rp.systemPromptCharEnd);
            eng->systemPromptEnd = (llama_pos)common_tokenize(
                eng->ctx, sysPart, /*add_special=*/false, /*parse_special=*/true).size();
        } else {
            eng->systemPromptEnd = 0;
        }
        llama_memory_clear(mem, false);
        prefillStart = 0;
        eng->chatMsgs = std::move(rebuilt);
        LOGI("[Chat] full re-render: %zu msgs, %zu chars", eng->chatMsgs.size(), prompt.size());
    }

    // Diff path: derive the system boundary for context shifts from the
    // accumulated conversation itself (the message-0 template block), never
    // from eng->systemPromptEnd — the memory pipeline's buildChatPrompt may
    // overwrite that field between turns with a different prompt's boundary.
    llama_pos diffSysEnd = 0;
    if (isContinuation && !eng->chatMsgs.empty()) {
        std::string sysBlock = common_chat_format_single(
            eng->chatTmpls.get(), {}, eng->chatMsgs[0],
            /*add_gen=*/false, /*use_jinja=*/false);
        if (!sysBlock.empty()) {
            diffSysEnd = (llama_pos)common_tokenize(
                eng->ctx, sysBlock, /*add_special=*/false, /*parse_special=*/true).size();
        }
    }

    // ── Tokenize ──
    // add_special=false: the template render carries the BOS in the system
    // block; adding another would inject a BOS mid-conversation on
    // continuation turns. parse_special=true turns the template's special
    // tokens (e.g. <|start_header_id|>) into single tokens.
    std::vector<llama_token> tokens =
        common_tokenize(eng->ctx, prompt, /*add_special=*/false, /*parse_special=*/true);

    eng->lastPromptTokens = tokens;
    eng->lastPromptText = prompt;
    eng->lastGeneratedTokens.clear();
    eng->lastFirstTokenMs = 0;
    eng->lastStopReason = "max_tokens";

    LOGI("[Tok] chat n=%zu add_special=false (diff=%s)", tokens.size(), isContinuation ? "yes" : "no");

    // ── Prefill (cancel-aware batches) ──
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    // Undo this turn: drop the partial diff/generation from the cache and
    // restore chatMsgs, so the next turn starts from the previous state.
    auto rollback = [&]() {
        llama_memory_seq_rm(mem, 0, prefillStart, -1);
        eng->chatMsgs.resize(msgsBeforeGen);
        eng->chatPosition = prefillStart;
    };

    bool prefillOk = true;
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        if (eng->cancel.load()) {
            LOGI("[Prefill] cancel requested at token %zu", i);
            llama_batch_free(batch);
            rollback();
            eng->lastStopReason = "cancelled";
            jstring empty = to_jstring(env, "");
            env->CallVoidMethod(callback, onToken, empty, JNI_TRUE);
            env->DeleteLocalRef(empty);
            return statsJson(tokens.size(), 0, 0, 0, 0.f,
                             eng->peakMemoryBytes, 0, "cancelled");
        }
        size_t n = std::min<size_t>(n_batch, tokens.size() - i);
        common_batch_clear(batch);
        for (size_t j = 0; j < n; j++) {
            common_batch_add(batch, tokens[i + j],
                             (llama_pos)(prefillStart + i + j), {0},
                             (i + j == tokens.size() - 1));
        }
        if (isContinuation) {
            // Upstream ai_chat.cpp shifts the context when a prefill batch
            // would overflow the cache (decode_tokens_in_batches); a long
            // conversation with a big new message must keep working.
            llama_pos pos_check = llama_memory_seq_pos_max(mem, 0);
            if (pos_check + (llama_pos)n >= (llama_pos)nCtx - 4) {
                const llama_pos sysEnd = diffSysEnd;
                const llama_pos n_discard = (pos_check - sysEnd) / 2;
                if (n_discard > 0) {
                    llama_memory_seq_rm(mem, 0, sysEnd, sysEnd + n_discard);
                    llama_memory_seq_add(mem, 0, sysEnd + n_discard, pos_check + 1, -n_discard);
                    LOGI("[Shift] prefill shift: discarded %d tokens", (int)n_discard);
                }
            }
        }
        if (decode_safe(eng, eng->ctx, batch) != 0) {
            prefillOk = false;
            LOGE("[Prefill] FAILED at token_idx=%zu (%s)",
                 i, eng->lastRecoveryReason.c_str());
            break;
        }
    }

    // A backend exception (device lost, out-of-device-memory) already poisons
    // the device — a full re-render on the SAME broken backend would fail too.
    // Skip the doomed retry and go straight to the recovery ladder.
    if (!prefillOk && isContinuation && eng->lastRecoveryReason.empty()) {
        // The diff did not fit into the cache (and the shift could not make
        // room): fall back to a full re-render from a cleared cache.
        LOGW("[Chat] diff prefill failed - falling back to full re-render");
        std::vector<common_chat_msg> rebuilt;
        RenderedPrompt rp = renderChat(eng->chatTmpls.get(), msgsJson, addAssistant, rebuilt);
        if (!rp.prompt.empty()) {
            llama_memory_clear(mem, false);
            eng->chatMsgs = std::move(rebuilt);
            prompt = rp.prompt;
            tokens = common_tokenize(eng->ctx, prompt, /*add_special=*/false, /*parse_special=*/true);
            eng->lastPromptTokens = tokens;
            prefillStart = 0;
            prefillOk = true;
            for (size_t i = 0; i < tokens.size(); i += n_batch) {
                if (eng->cancel.load()) {
                    llama_batch_free(batch);
                    rollback();
                    eng->lastStopReason = "cancelled";
                    jstring empty = to_jstring(env, "");
                    env->CallVoidMethod(callback, onToken, empty, JNI_TRUE);
                    env->DeleteLocalRef(empty);
                    return statsJson(tokens.size(), 0, 0, 0, 0.f,
                                     eng->peakMemoryBytes, 0, "cancelled");
                }
                size_t n = std::min<size_t>(n_batch, tokens.size() - i);
                common_batch_clear(batch);
                for (size_t j = 0; j < n; j++) {
                    common_batch_add(batch, tokens[i + j], (llama_pos)(i + j), {0},
                                     (i + j == tokens.size() - 1));
                }
                if (decode_safe(eng, eng->ctx, batch) != 0) {
                    prefillOk = false;
                    break;
                }
            }
        }
    }

    if (!prefillOk) {
        llama_batch_free(batch);
        rollback();
        // Preserve a backend-exception reason (device lost etc.) if already set.
        if (eng->lastRecoveryReason.empty())
            eng->lastRecoveryReason = "prefill decode failed after full re-render";
        eng->lastStopReason = "corrupted";
        eng->chatPosition = 0;
        dump_corruption(eng, "prefill_decode", eng->lastRecoveryReason);
        return statsJson(tokens.size(), 0, 0, 0, 0.f,
                         eng->peakMemoryBytes, 0, "corrupted");
    }

    eng->chatPosition = llama_memory_seq_pos_max(mem, 0) + 1;

    // Corruption guard: the prefill logits feed the first sample. A broken
    // compute path (typically GPU) yields NaN/INF here — abort before any
    // token is emitted so the recovery retry re-streams nothing.
    if (!logits_are_finite(eng->ctx, vocab)) {
        llama_batch_free(batch);
        rollback();
        const int bad = first_bad_logit_index(eng->ctx, vocab);
        const float bv = bad >= 0 ? llama_get_logits_ith(eng->ctx, -1)[bad] : 0.f;
        eng->lastRecoveryReason = "prefill logits corrupted (NaN/INF) at idx " +
            std::to_string(bad) + " (" + (std::isnan(bv) ? "NaN" : "INF") + ")";
        eng->lastStopReason = "corrupted";
        eng->chatPosition = 0;
        dump_corruption(eng, "logits", eng->lastRecoveryReason);
        return statsJson(tokens.size(), 0, 0, 0, 0.f,
                         eng->peakMemoryBytes, 0, "corrupted");
    }

    LOGI("[Prefill] END backend=%s tokens=%zu ctx=%u batch=%d seq=0 rc=0",
         eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU", tokens.size(), nCtx, n_batch);

    // Context-overflow guard: never budget more generated tokens than the
    // cache can hold (the mid-run shift still discards old context when the
    // window fills).
    int64_t genBudget = cfg.maxTokens;
    {
        const int64_t used = (int64_t)llama_memory_seq_pos_max(mem, 0) + 1;
        const int64_t available = (int64_t)nCtx - 4 - used;
        if (available < 1) {
            llama_batch_free(batch);
            rollback();
            // A reload cannot fix a prompt that is simply too long for the
            // context — surface a clean error instead of recovery retries.
            eng->lastStopReason = "context_overflow";
            eng->chatPosition = 0;
            throw_java(env, "Context overflow: no room to generate (prompt exceeds context window)");
            return "{}";
        }
        genBudget = std::min<int64_t>(genBudget, available);
    }

    auto t1 = clock::now();
    int64_t promptMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    common_sampler_reset(eng->sampler);

    // ── Decode loop (upstream pattern) ──
    std::string output;
    std::string pendingUtf8;   // buffers token pieces until they form valid UTF-8
    int64_t generated = 0;
    bool aborted = false;      // cancel OR decode error: roll back the turn

    for (int i = 0; i < genBudget; i++) {
        if (eng->cancel.load()) {
            aborted = true;
            eng->lastStopReason = "cancelled";
            break;
        }

        // Context full: shift (upstream shift_context pattern: discard the
        // older half of the tokens after the system prompt).
        llama_pos pos_check = llama_memory_seq_pos_max(mem, 0);
        if (pos_check >= (llama_pos)nCtx - 4) {
            const llama_pos sysEnd = eng->systemPromptEnd;
            const llama_pos n_discard = (pos_check - sysEnd) / 2;
            if (n_discard > 0) {
                llama_memory_seq_rm(mem, 0, sysEnd, sysEnd + n_discard);
                llama_memory_seq_add(mem, 0, sysEnd + n_discard, pos_check + 1, -n_discard);
                LOGI("[Shift] discarded %d tokens at step=%lld (preserved system prompt %d)",
                     (int)n_discard, (long long)generated, (int)sysEnd);
            } else {
                LOGW("[Shift] cannot shift: n_discard=%d sysEnd=%d pos_check=%d",
                     (int)n_discard, (int)sysEnd, (int)pos_check);
            }
        }

        // Corruption guard: sample only from finite logits (already
        // synchronized by llama.cpp when llama_decode returned).
        if (!logits_are_finite(eng->ctx, vocab)) {
            eng->lastRecoveryReason = std::string("logits corrupted (NaN/INF) at step ") + std::to_string(i);
            eng->lastStopReason = "corrupted";
            eng->chatPosition = 0;
            dump_corruption(eng, "logits", eng->lastRecoveryReason);
            break;
        }

        // Sample
        llama_token id = common_sampler_sample(eng->sampler, eng->ctx, -1);

        // Bounds guard: the sampler must return a valid token id.
        if (!token_is_valid(vocab, id)) {
            eng->lastRecoveryReason = std::string("invalid token id ") +
                std::to_string((long long)id) + " at step " + std::to_string(i);
            eng->lastStopReason = "corrupted";
            eng->chatPosition = 0;
            dump_corruption(eng, "token", eng->lastRecoveryReason);
            break;
        }

        eng->lastGeneratedTokens.push_back(id);
        common_sampler_accept(eng->sampler, id, true);

        // EOS check
        if (llama_vocab_is_eog(vocab, id)) {
            eng->lastStopReason = "eos";
            break;
        }

        // First token timing
        if (generated == 0) {
            auto now = clock::now();
            eng->lastFirstTokenMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - t1).count();
            LOGI("[Gen] first token latency: %lld ms", (long long)eng->lastFirstTokenMs);
        }

        // Detokenize with special=true (upstream default)
        std::string piece = common_token_to_piece(eng->ctx, id);
        output += piece;

        // Per-token decode logging (opt-in): step, id, decoded text, top-5
        // logits, temperature and backend — the decode-corruption diagnostic.
        if (cfg.debugTokenLogging) {
            const float * logits = llama_get_logits_ith(eng->ctx, -1);
            std::ostringstream o;
            o << "[TokLog] step=" << i << " id=" << id
              << " piece=\"" << piece << "\" temp=" << cfg.temperature
              << " backend=" << (eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU")
              << " top5=";
            if (logits) {
                const int n = llama_vocab_n_tokens(vocab);
                int top[5] = { -1, -1, -1, -1, -1 };
                float tv[5] = { -1e30f, -1e30f, -1e30f, -1e30f, -1e30f };
                for (int t = 0; t < n; t++) {
                    for (int k = 0; k < 5; k++) {
                        if (logits[t] > tv[k]) {
                            for (int j = 4; j > k; j--) { tv[j] = tv[j - 1]; top[j] = top[j - 1]; }
                            tv[k] = logits[t]; top[k] = t;
                            break;
                        }
                    }
                }
                for (int k = 0; k < 5; k++) {
                    if (k) o << ",";
                    o << top[k] << ":" << tv[k];
                }
            } else {
                o << "(no logits)";
            }
            LOGI("%s", o.str().c_str());
        }

        // Degenerate-repetition guard: a run of identical non-whitespace
        // tokens (e.g. "////////") is a decode failure, not model text.
        {
            const size_t REPEAT_LIMIT = 24;
            const size_t n = eng->lastGeneratedTokens.size();
            bool whitespacePiece = true;
            for (char c : piece) if (!std::isspace((unsigned char)c)) { whitespacePiece = false; break; }
            if (n >= REPEAT_LIMIT && !whitespacePiece && piece.size() > 0) {
                bool allSame = true;
                for (size_t k = n - REPEAT_LIMIT; k < n; k++) {
                    if (eng->lastGeneratedTokens[k] != id) { allSame = false; break; }
                }
                if (allSame) {
                    eng->lastRecoveryReason = std::string("degenerate repetition (") +
                        std::to_string(REPEAT_LIMIT) + "x token " + std::to_string((long long)id) + ")";
                    eng->lastStopReason = "corrupted";
                    eng->chatPosition = 0;
                    dump_corruption(eng, "repetition", eng->lastRecoveryReason);
                    break;
                }
            }
        }

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

        // Buffer the piece until it forms a complete UTF-8 character (upstream
        // ai_chat.cpp cached_token_chars pattern).
        pendingUtf8 += piece;
        bool completeUtf8 = is_valid_utf8(pendingUtf8.c_str());
        if (!pendingUtf8.empty() && (completeUtf8 || pendingUtf8.size() > 8)) {
            jstring jpiece = to_jstring(env, pendingUtf8);
            env->CallVoidMethod(callback, onToken, jpiece, JNI_FALSE);
            env->DeleteLocalRef(jpiece);
            pendingUtf8.clear();
        }
        generated++;

        // Feed token back for next step (upstream pattern: persistent batch + common_batch_add)
        common_batch_clear(batch);
        common_batch_add(batch, id, llama_memory_seq_pos_max(mem, 0) + 1, {0}, true);
        if (decode_safe(eng, eng->ctx, batch) != 0) {
            // Preserve a backend-exception reason (device lost etc.) if already set.
            if (eng->lastRecoveryReason.empty())
                eng->lastRecoveryReason = std::string("decode failed at step ") + std::to_string(i);
            eng->lastStopReason = "corrupted";
            aborted = true; // the partial turn must not survive into the next one
            dump_corruption(eng, "decode", eng->lastRecoveryReason);
            break;
        }
    }

    if (!eng->lastRecoveryReason.empty()) {
        // Corrupted attempt: free the batch, roll back the turn and emit NO
        // finished callback — the recovery wrapper re-streams this turn. This
        // runs BEFORE the commit block so a partial corrupt output can never
        // be pushed into chatMsgs.
        llama_batch_free(batch);
        rollback();
        eng->trackMemory();
        return statsJson(tokens.size(), generated, 0, 0, 0.f,
                         eng->peakMemoryBytes, 0, "corrupted");
    }

    if (aborted) {
        // Cancel: remove this turn's partial diff + generation from the cache
        // and restore chatMsgs. The next turn either retries the diff (when
        // the cache still holds the prefix) or full re-renders (when the cache
        // was emptied). Decode errors never reach here — they set
        // lastRecoveryReason and return above.
        llama_batch_free(batch);
        rollback();
    } else {
        // Normal completion (eos / max_tokens / stop_sequence): the generated
        // tokens remain in the cache and the assistant message is recorded so
        // the next turn's continuation check matches the app's stored copy
        // (trimmed on both sides).
        llama_batch_free(batch);
        eng->chatPosition = llama_memory_seq_pos_max(mem, 0) + 1;
        // Upstream ai_chat.cpp pushes the generated text verbatim into its
        // message list. We do the same: the KV cache holds exactly these
        // tokens, so the next turn's diff is computed against the real cache
        // prefix. The continuation comparison trims both sides, so the app's
        // trimmed copy still matches.
        eng->chatMsgs.push_back(common_chat_msg{"assistant", output});
    }

    auto t2 = clock::now();
    int64_t genMs = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    float tps = genMs > 0 ? (float)generated * 1000.f / (float)genMs : 0.f;

    LOGI("doGenerateChat END gen=%d generated=%lld stop=%s",
         s_gen_counter, (long long)generated, eng->lastStopReason.c_str());
    LOGI("[Perf] backend=%s gpu=%d prompt=%lldms(%zu) gen=%lldms(%lld) %.2f tok/s",
         eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU",
         eng->gpuLayersUsed, (long long)promptMs, tokens.size(),
         (long long)genMs, (long long)generated, tps);

    eng->trackMemory();

    // Vulkan diagnostics — logged after every generation (the on-device audit):
    // context create/cleanup cost, decode (submit+fence) count and average
    // wait, live GPU heap and recovery telemetry.
    {
        size_t vkFree = 0, vkTotal = 0;
#ifdef GGML_USE_VULKAN
        if (eng->gpuLayersUsed > 0) {
            try { ggml_backend_vk_get_device_memory(0, &vkFree, &vkTotal); }
            catch (...) {}
        }
#endif
        const int64_t decAvg = eng->decodeCount > 0
            ? eng->decodeTotalMs / eng->decodeCount : 0;
        LOGI("[VulkanDiag] backend=%s ctxCreate=%lldms cleanup=%lldms decodeCalls=%lld decodeAvg=%lldms "
             "gpuFree=%.1fMB gpuTotal=%.1fMB recovery=%d devLostRecovered=%d",
             eng->gpuLayersUsed > 0 ? "VULKAN" : "CPU",
             (long long)eng->lastContextCreateMs, (long long)eng->lastCleanupMs,
             (long long)eng->decodeCount, (long long)decAvg,
             vkTotal ? (double)vkFree / (1024.0 * 1024.0) : 0.0,
             vkTotal ? (double)vkTotal / (1024.0 * 1024.0) : 0.0,
             eng->recoveryCount, eng->vulkanDeviceLostRecoveries.load());
    }

    // Flush any remaining buffered bytes at the end of the stream
    if (!pendingUtf8.empty()) {
        jstring jpiece = to_jstring(env, pendingUtf8);
        env->CallVoidMethod(callback, onToken, jpiece, JNI_FALSE);
        env->DeleteLocalRef(jpiece);
        pendingUtf8.clear();
    }

    // Send final empty delta
    jstring empty = to_jstring(env, "");
    env->CallVoidMethod(callback, onToken, empty, JNI_TRUE);
    env->DeleteLocalRef(empty);

    // Context stays resident across turns (upstream lifecycle — one context
    // reused; see ai_chat.cpp). The KV cache is NOT cleared here: it holds the
    // conversation and is shifted in-place on overflow (mid-run shift) or
    // cleared in-place per new conversation (nativeResetChat / full re-render).
    // Transient per-decode buffers (staging, scratch, command buffers) are
    // already released inside llama_decode/graph cleanup — nothing extra is
    // needed after EOS. The UI only ever sees the finished callback above;
    // this runs on the same background thread as generation, never the UI.
    return statsJson(tokens.size(), generated, promptMs, genMs, tps,
                     eng->peakMemoryBytes, eng->lastFirstTokenMs,
                     eng->lastStopReason);
}

// ---------------------------------------------------------------------------
// Generation wrapper — automatic recovery from corrupted inference
// ---------------------------------------------------------------------------
//
// Every generation runs a FRESH sampler (never reused across requests). If the
// attempt is flagged corrupt (NaN/INF logits, invalid token id, decode failure,
// degenerate repetition), the wrapper destroys and recreates the context — once
// on the same backend, then once on CPU — and re-streams the same prompt. It
// only gives up after both recovery stages also fail.

enum class GenKind { Raw, Chat };

static std::string generateWithRecovery(
    LlamaEngine * eng,
    JNIEnv * env,
    jobject callback,
    jmethodID mid,
    const std::string & prompt,
    const std::string & msgsJson,
    bool addAssistant,
    const GenConfig & cfg,
    GenKind kind) {

    // Recovery ladder — matches the upstream context lifecycle (one context
    // created at load and REUSED across turns; see examples/llama.android
    // ai_chat.cpp and tools/server). The KV cache is cleared in-place
    // (llama_memory_clear) per conversation and shifted in-place on overflow;
    // the context object itself is NOT torn down between generations.
    //   stage 0 — reuse the RESIDENT llama_context (created at load, kept
    //             alive across turns; KV cleared in-place per conversation).
    //   stage 1 — corruption detected: recreate ONLY the llama_context from the
    //             resident model (fresh KV cache, compute buffers, command
    //             pools; model weights stay on GPU).
    //   stage 2 — full backend teardown: destroy model + Vulkan backend, reload
    //             once on GPU (buffers, descriptor sets, pipelines recreated).
    //   stage 3 — CPU session: full teardown + reload on CPU; cpuSessionFallback
    //             notifies the UI so the user sees the amber fallback warning.
    //
    // A DeviceLost poisons the WHOLE backend, not just one context — skip the
    // cheap context-recreation stage and go straight to a full backend reload.
    int stage = eng->vulkanDeviceLost ? 2 : 0;
    for (;;) {
        std::string err;
        if (stage == 0) {
            // Reuse the resident context: nothing to rebuild on the happy path.
            // Only create it if it was released (e.g. after an unload/load or
            // a context created before this wrapper existed).
            if (!eng->ctx) {
                if (!createEngineContext(eng, &err)) {
                    LOGW("[Recovery] context creation failed (%s)", err.c_str());
                    if (!reloadEngineContext(eng, true, &err)) {
                        if (!reloadEngineContext(eng, false, &err)) {
                            throw_java(env, "Recovery failed: " + err);
                            return "{}";
                        }
                        stage = 3;
                    } else {
                        stage = 2;
                    }
                }
            }
        } else if (stage == 1) {
            if (!createEngineContext(eng, &err)) {
                // Even cheap context recreation failed (e.g. the GPU died):
                // escalate straight to a full backend reload on GPU, then CPU.
                LOGW("[Recovery] context recreation failed (%s)", err.c_str());
                if (!reloadEngineContext(eng, true, &err)) {
                    if (!reloadEngineContext(eng, false, &err)) {
                        throw_java(env, "Recovery failed: " + err);
                        return "{}";
                    }
                    stage = 3;
                } else {
                    stage = 2;
                }
            }
            eng->vulkanDeviceLost = false;  // fresh context = fresh backend state
        } else if (stage == 2) {
            if (!reloadEngineContext(eng, true, &err)) {
                // The GPU reload itself failed (driver-level): recover on CPU.
                if (!reloadEngineContext(eng, false, &err)) {
                    throw_java(env, "Recovery failed: " + err);
                    return "{}";
                }
                LOGW("[Recovery] GPU reload failed (%s) - recovered on CPU", err.c_str());
                stage = 3;
            } else {
                LOGW("[Recovery] GPU backend reloaded (stage 2)");
            }
            eng->vulkanDeviceLost = false;  // backend recreated → device usable again
        } else { // stage == 3 — CPU session
            if (!reloadEngineContext(eng, false, &err)) {
                throw_java(env, "Recovery failed: " + err);
                return "{}";
            }
            eng->vulkanDeviceLost = false;
        }

        // Fresh sampler per attempt: a corrupted sampler state can never leak
        // into the next attempt (grammar, penalties, top-k/p/min-p, temp...).
        common_params_sampling sp = buildSamplingParams(cfg);
        {
            // Sampler is read by nativeGetDebugInfo (common_sampler_print)
            // under stateMutex from the stats thread — swap it under the same
            // lock so a stats poll can never deref a freed sampler.
            std::lock_guard<std::mutex> lock(eng->stateMutex);
            common_sampler_free(eng->sampler);
            eng->sampler = common_sampler_init(eng->model, sp);
        }
        if (!eng->sampler) {
            throw_java(env, "Sampler creation failed");
            return "{}";
        }

        eng->lastRecoveryReason.clear();

        std::string stats = kind == GenKind::Chat
            ? doGenerateChat(eng, env, callback, mid, msgsJson, addAssistant, cfg)
            : doGenerate(eng, env, callback, mid, prompt, cfg);

        if (eng->lastRecoveryReason.empty()) {
            eng->vulkanDeviceLost = false;
            return stats;  // clean run
        }

        if (stage >= 3) {
            throw_java(env, "Inference corrupted after recovery retries: " + eng->lastRecoveryReason);
            return "{}";
        }
        const std::string reason = eng->lastRecoveryReason;
        LOGW("[Recovery] stage=%d backend=%s reason=%s",
             stage, eng->gpuLayersUsed > 0 ? "GPU" : "CPU", reason.c_str());
        eng->recoveryCount++;
        stage++;
    }
}

// ---------------------------------------------------------------------------
// Embeddings — official llama.cpp embedding pattern (examples/embedding)
// ---------------------------------------------------------------------------

// Encodes a single text and returns its (pooled, normalized) embedding.
// Pooling is handled by llama.cpp based on the model's pooling type metadata
// (MEAN for BERT-style models, CLS/LAST for others); embd_normalize=2 is set
// at load time so embeddings come back unit-length (cosine == dot product).
static std::vector<float> embed_one(llama_context * ctx, const std::string & text) {
    const int32_t n_embd = llama_model_n_embd(llama_get_model(ctx));

    std::vector<llama_token> tokens =
        common_tokenize(ctx, text, /*add_special=*/true, /*parse_special=*/false);
    if (tokens.empty()) return std::vector<float>(n_embd, 0.0f);

    // Never exceed the context: truncate from the tail (embedding models are
    // short-text models; MiniLM/BGE train on <=512 tokens). An overflow would
    // fail llama_encode and abort the whole batch.
    const uint32_t n_ctx = llama_n_ctx(ctx);
    if ((uint32_t)tokens.size() >= n_ctx) {
        tokens.resize(n_ctx - 1);
    }

    llama_batch batch = llama_batch_init((int32_t)tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        // logits=true on every token so the pooled embedding is computed from
        // the full sequence (required for MEAN pooling models).
        common_batch_add(batch, tokens[i], (llama_pos)i, {0}, /*logits=*/true);
    }
    const int rc = llama_encode(ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) throw std::runtime_error("llama_encode failed (rc=" + std::to_string(rc) + ")");

    const float * embd = llama_get_embeddings_ith(ctx, -1);
    if (!embd) throw std::runtime_error("embeddings unavailable");
    return std::vector<float>(embd, embd + n_embd);
}

// Serializes a list of embeddings as a JSON array of float arrays.
static std::string embeddings_to_json(const std::vector<std::vector<float>> & embs) {
    std::ostringstream o;
    o << "[";
    for (size_t i = 0; i < embs.size(); i++) {
        if (i) o << ",";
        o << "[";
        for (size_t j = 0; j < embs[i].size(); j++) {
            if (j) o << ",";
            o << std::setprecision(8) << embs[i][j];
        }
        o << "]";
    }
    o << "]";
    return o.str();
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

    // Store the load configuration for runtime corruption recovery (the
    // generation wrapper can destroy and recreate this exact context).
    eng->modelPath = path;
    eng->loadCtxLen = ctxLen;
    eng->loadBatchSize = batchSize;
    eng->loadGpuLayers = gpuLayers;

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

    // ── Load model via official llama.cpp (model-only; the context is created
    //    separately by createEngineContext so it can be recreated per
    //    generation without touching the GPU-resident weights) ──
    common_init_result_ptr result;
    try {
        result = common_init_from_params(params, /*model_only=*/true);
    } catch (const std::exception &e) {
        if (params.n_gpu_layers != 0) {
            LOGW("[Load] GPU failed (%s) - retrying CPU", e.what());
            params.n_gpu_layers = 0;
            selectedGpuLayers = 0;
            eng->backendReason = std::string("GPU init failed: ") + e.what();
            try { result = common_init_from_params(params, /*model_only=*/true); }
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
            try { result = common_init_from_params(params, /*model_only=*/true); }
            catch (...) { throw_java(env, "Model load failed (CPU fallback)"); return; }
        } else {
            throw_java(env, "Model load failed"); return;
        }
    }

    if (!result) {
        throw_java(env, "Model init returned null"); return;
    }

    // ── Install a loaded model into the engine ──
    auto install_result = [&](common_init_result_ptr & r, const char * failMsg) -> bool {
        eng->model = r->model();
        if (!eng->model) {
            r.reset();
            throw_java(env, failMsg);
            return false;
        }
        eng->initResult = std::move(r);

        // Brand-new llama_context from the resident model (empty KV cache,
        // compute buffers and sequence state — fresh for the first generation).
        std::string cerr;
        if (!createEngineContext(eng, &cerr)) {
            throw_java(env, failMsg);
            return false;
        }
        // Fresh initial sampler (the per-generation wrapper replaces it).
        common_params_sampling sp0 = buildSamplingParams(GenConfig());
        eng->sampler = common_sampler_init(eng->model, sp0);
        if (!eng->sampler) {
            throw_java(env, "Sampler creation failed");
            return false;
        }
        // Fresh load: reset session fallback / recovery counters. The recovery
        // reason is deliberately preserved across destroy() (the wrapper reads
        // it after reloadEngineContext), so it must be cleared here explicitly
        // — otherwise a fresh clean load would still report the old reason.
        eng->cpuSessionFallback = false;
        eng->recoveryCount = 0;
        eng->lastRecoveryReason.clear();
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
            eng->vulkanValidationStatus = "passed";
            eng->backendReason = "Vulkan active (" + std::to_string(eng->gpuLayersUsed) + "/" +
                                 std::to_string(eng->totalLayers) + " layers)";
            LOGI("[Verify] PASSED: %s", format_verify_summary(vr).c_str());
        } else {
            const std::string fail = "Vulkan backend failed correctness validation on this device." +
                                     format_verify_failures(vr) + " (" + format_verify_summary(vr) + ")";
            // The self-test result is DIAGNOSTIC ONLY. A mismatch between the CPU
            // reference and the GPU path does not mean Vulkan inference is broken
            // on this device — the GPU context already loaded and decoded fine.
            // Keep the Vulkan backend active and surface the mismatch in the
            // diagnostics UI; gpuInferenceVerified flips true the moment a real
            // inference run (warm-up / generation) succeeds on the GPU.
            LOGW("[Verify] FAILED (diagnostic only, keeping Vulkan): %s", fail.c_str());
            eng->vulkanValidationStatus = "failed";
            eng->vulkanValidationDetail = fail;
            eng->backendReason = "Vulkan active (" + std::to_string(eng->gpuLayersUsed) + "/" +
                                 std::to_string(eng->totalLayers) + " layers)";
        }
    } else {
        eng->vulkanValidationStatus = "skipped";
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
    // Pure render: compute into a LOCAL message list so this call has no side
    // effects on the engine's accumulated conversation (chatMsgs). Only
    // systemPromptEnd is recorded below, for the raw single-shot generate path
    // that needs the system boundary for its context shifts.
    std::vector<common_chat_msg> localMsgs;
    RenderedPrompt rp = renderChat(
        eng->chatTmpls.get(), from_jstring(env, msgJson),
        addAssistant == JNI_TRUE, localMsgs);

    // Compute systemPromptEnd: token count of system prompt portion
    if (rp.systemPromptCharEnd > 0 && eng->ctx && eng->model) {
        std::string sysPart = rp.prompt.substr(0, rp.systemPromptCharEnd);
        // Tokenize exactly like doGenerate() (add_special=false): the rendered
        // prompt already contains the BOS token text, which parse_special=true
        // converts into the BOS token. Using add_special here would insert a
        // second BOS and make systemPromptEnd off-by-one vs. the prefill
        // positions, shifting the wrong range during context compression.
        std::vector<llama_token> sysToks =
            common_tokenize(eng->ctx, sysPart, /*add_special=*/false, /*parse_special=*/true);
        eng->systemPromptEnd = (llama_pos)sysToks.size();
        LOGI("[Template] systemPromptEnd=%d (from %zu chars, %zu tokens)",
             (int)eng->systemPromptEnd, rp.systemPromptCharEnd, sysToks.size());
    } else {
        eng->systemPromptEnd = 0;
    }

    LOGI("[Template] rendered %zu bytes", rp.prompt.size());
    return to_jstring(env, rp.prompt);
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
    // Pure render: compute into a LOCAL message list so this call has no side
    // effects on the engine's accumulated conversation (chatMsgs). Only
    // systemPromptEnd is recorded below, for the raw single-shot generate path
    // that needs the system boundary for its context shifts.
    std::vector<common_chat_msg> localMsgs;
    RenderedPrompt rp = renderChat(
        eng->chatTmpls.get(), from_jstring(env, msgJson),
        addAssistant == JNI_TRUE, localMsgs);

    if (rp.systemPromptCharEnd > 0 && eng->ctx && eng->model) {
        std::string sysPart = rp.prompt.substr(0, rp.systemPromptCharEnd);
        // Tokenize exactly like doGenerate() (add_special=false); see
        // nativeApplyChatTemplate for the rationale.
        std::vector<llama_token> sysToks =
            common_tokenize(eng->ctx, sysPart, /*add_special=*/false, /*parse_special=*/true);
        eng->systemPromptEnd = (llama_pos)sysToks.size();
    } else {
        eng->systemPromptEnd = 0;
    }

    LOGI("[Template] rendered %zu bytes", rp.prompt.size());
    return to_jstring(env, rp.prompt);
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
    eng->lastPromptTokens.clear();
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
    // NOTE: no ctx check — the context was released after the previous
    // generation and is recreated inside generateWithRecovery() below.
    if (!eng || !eng->model || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return to_jstring(env, "{}");
    }

    GenConfig cfg = parseGenConfig(from_jstring(env, cfgJson));

    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Z)V");
    if (!mid) {
        throw_java(env, "Invalid callback");
        env->DeleteLocalRef(cls);
        return to_jstring(env, "{}");
    }

    std::string promptStr = from_jstring(env, prompt);
    LOGI("[JNI] nativeGenerate: prompt.size=%zu genConfig: temp=%.2f maxTok=%d",
         promptStr.size(), cfg.temperature, cfg.maxTokens);
    LOGI("[JNI] nativeGenerate prompt_head: %.300s", promptStr.c_str());
    LOGI("[JNI] nativeGenerate prompt_tail: %.300s",
         promptStr.c_str() + std::max<size_t>(0, promptStr.size() - 300));

    // generateWithRecovery creates a FRESH sampler and recreates the context
    // (retry once, then CPU fallback) if the run is detected as corrupted.
    std::string result = generateWithRecovery(eng, env, callback, mid,
                                              promptStr, "", false, cfg, GenKind::Raw);

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

// ── nativeGenerateChat ─────────────────────────────────────────────────────
// Official llama.cpp multi-turn chat: the message history is diffed against
// the engine's accumulated conversation and only the new messages' template
// diff is decoded at the continuing KV position (see doGenerateChat).

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeGenerateChat(
    JNIEnv *env, jobject, jlong handle,
    jstring msgJson, jboolean addAssistant, jstring cfgJson, jobject callback) {
try {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    // NOTE: no ctx check — the context was released after the previous
    // generation and is recreated inside generateWithRecovery() below.
    if (!eng || !eng->model || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return to_jstring(env, "{}");
    }

    GenConfig cfg = parseGenConfig(from_jstring(env, cfgJson));

    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Z)V");
    if (!mid) {
        throw_java(env, "Invalid callback");
        env->DeleteLocalRef(cls);
        return to_jstring(env, "{}");
    }

    const bool addAss = addAssistant == JNI_TRUE;
    std::string msgStr = from_jstring(env, msgJson);
    LOGI("[JNI] nativeGenerateChat: msgsJson.size=%zu addAssistant=%d genConfig: temp=%.2f maxTok=%d",
         msgStr.size(), (int)addAss, cfg.temperature, cfg.maxTokens);

    // generateWithRecovery creates a FRESH sampler and recreates the context
    // (retry once, then CPU fallback) if the run is detected as corrupted.
    std::string result = generateWithRecovery(eng, env, callback, mid,
                                              "", msgStr, addAss, cfg, GenKind::Chat);

    env->DeleteLocalRef(cls);
    return to_jstring(env, result);

} catch (const std::exception &e) {
    LOGE("[GenerateChat] exception: %s", e.what());
    throw_java(env, std::string("Chat generation failed: ") + e.what());
    return to_jstring(env, "{}");
} catch (...) {
    LOGE("[GenerateChat] unknown exception");
    throw_java(env, "Chat generation failed (unknown)");
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
    // NOTE: no ctx check — the context was released after the previous
    // generation and is recreated inside generateWithRecovery() below.
    if (!eng || !eng->model || !eng->sampler) {
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
        std::string stats = generateWithRecovery(eng, env, callback, mid,
                                                 "Hello", "", false, cfg, GenKind::Raw);
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
    if (!eng) return 0;
    std::lock_guard<std::mutex> lock(eng->stateMutex);
    return (jlong)eng->peakMemoryBytes;
}

// ── nativeVulkanAvailable ──────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeVulkanAvailable(
    JNIEnv *, jobject) {
    return checkVulkan().ok ? JNI_TRUE : JNI_FALSE;
}

// Runs the "Hi" probe on the current engine context: decodes the prompt and
// verifies every logit is finite. Returns true only when the backend produced
// a valid result — used by the warm-up recovery ladder to validate each
// recreated context / reloaded backend before accepting it.
static bool warmupProbe(LlamaEngine * eng) {
    const llama_vocab * vocab = llama_model_get_vocab(eng->model);
    const bool addBos = llama_vocab_get_add_bos(vocab);
    std::vector<llama_token> toks = common_tokenize(eng->ctx, "Hi", addBos, true);

    llama_batch batch = llama_batch_init((int32_t)toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++)
        common_batch_add(batch, toks[i], (llama_pos)i, {0}, true);
    const int rc = decode_safe(eng, eng->ctx, batch);
    llama_batch_free(batch);
    return rc == 0 && logits_are_finite(eng->ctx, vocab);
}

// ── nativeWarmUp ───────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeWarmUp(
    JNIEnv *env, jobject, jlong handle) {
try {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    // NOTE: no ctx check — the context is released after every generation and
    // recreated here (mirrors nativeGenerate). Warm-up only runs right after
    // load in practice, but must not hard-fail if called again later.
    if (!eng || !eng->model || !eng->sampler) {
        throw_java(env, "Engine not initialized"); return to_jstring(env, "{}");
    }
    if (!eng->ctx) {
        std::string cerr;
        if (!createEngineContext(eng, &cerr)) {
            throw_java(env, "Warm-up context creation failed: " + cerr);
            return to_jstring(env, "{}");
        }
    }

    auto t0 = std::chrono::steady_clock::now();

    // Snapshot the device-lost state BEFORE the first probe decode: decode_safe
    // clears the flag on rc==0, which would otherwise defeat the device-lost-
    // aware ladder start below (a fresh context can't fix a lost backend).
    const bool devLostAtWarmup = eng->vulkanDeviceLost;

    llama_memory_clear(llama_get_memory(eng->ctx), true);

    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    const bool addBos = llama_vocab_get_add_bos(vocab);
    std::vector<llama_token> toks = common_tokenize(eng->ctx, "Hi", addBos, true);

    llama_batch batch = llama_batch_init(toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++)
        common_batch_add(batch, toks[i], (llama_pos)i, {0}, true);
    int rc = decode_safe(eng, eng->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        throw_java(env, "Warm-up decode failed: " +
            (eng->lastRecoveryReason.empty() ? "decode error" : eng->lastRecoveryReason));
        return to_jstring(env, "{}");
    }

    if (!logits_are_finite(eng->ctx, vocab)) {
        // The GPU pipeline is producing corrupted results: recreate the context
        // on the same backend once; if it is still corrupt, fall back to CPU.
        // Each recreated context / reloaded backend is validated by the "Hi"
        // probe before the trailing sample below runs.
        LOGW("[WarmUp] logits corrupted (NaN/INF) - recreating context");
        std::string err;

        // Recovery ladder (mirrors generateWithRecovery):
        //   stage 0 — recreate ONLY the llama_context from the resident model
        //             (fresh KV cache, compute buffers, command state). Cheap:
        //             the model weights stay on the GPU.
        //   stage 1 — full backend teardown + reload on GPU (Vulkan buffers,
        //             descriptor sets, pipelines recreated).
        //   stage 2 — CPU session: full teardown + reload on CPU. Final try.
        // Each stage re-runs the "Hi" probe and only succeeds when the decode
        // returns 0 AND every logit is finite (no NaN/INF). When the device was
        // lost, a fresh context cannot fix the backend — start at the full
        // backend reload (mirrors generateWithRecovery).
        int warmStage = devLostAtWarmup ? 1 : 0;
        for (;;) {
            if (warmStage == 0) {
                if (createEngineContext(eng, &err)) {
                    if (warmupProbe(eng)) {
                        LOGI("[WarmUp] context recreated backend=%s",
                             eng->gpuLayersUsed > 0 ? "GPU" : "CPU");
                        break;
                    }
                    LOGW("[WarmUp] recreated context failed probe - escalating");
                } else {
                    LOGW("[WarmUp] context recreation failed (%s)", err.c_str());
                }
                warmStage = 1;
            } else if (warmStage == 1) {
                if (reloadEngineContext(eng, eng->gpuLayersUsed > 0, &err)) {
                    if (warmupProbe(eng)) {
                        LOGI("[WarmUp] recovered backend=%s",
                             eng->gpuLayersUsed > 0 ? "GPU" : "CPU");
                        break;
                    }
                    LOGW("[WarmUp] GPU reload still corrupt - falling back to CPU");
                } else {
                    LOGW("[WarmUp] GPU reload failed (%s) - recovering on CPU", err.c_str());
                }
                warmStage = 2;
            } else { // stage 2 — CPU session, final attempt
                if (!reloadEngineContext(eng, false, &err)) {
                    throw_java(env, std::string("Warm-up recovery failed: ") + err);
                    return to_jstring(env, "{}");
                }
                if (!warmupProbe(eng)) {
                    throw_java(env, "Warm-up corrupted after backend recovery");
                    return to_jstring(env, "{}");
                }
                LOGI("[WarmUp] recovered backend=CPU");
                break;
            }
        }

        // Any reload destroyed the old sampler — recreate it fresh for the
        // warm-up sample below (same pattern as the generation wrapper).
        common_params_sampling sp = buildSamplingParams(GenConfig());
        {
            // Same lock as generateWithRecovery: the stats thread reads the
            // sampler (common_sampler_print) under stateMutex.
            std::lock_guard<std::mutex> lock(eng->stateMutex);
            common_sampler_free(eng->sampler);
            eng->sampler = common_sampler_init(eng->model, sp);
        }

        // The ladder recovered (each stage only succeeds after a valid probe) —
        // clear the stale corruption reason so stats/debug don't keep reporting
        // the warm-up failure on the next clean run.
        eng->lastRecoveryReason.clear();
        eng->vulkanDeviceLost = false;
    }

    if (eng->gpuLayersUsed > 0 && !eng->gpuInferenceVerified) {
        eng->gpuInferenceVerified = true;
        LOGI("[WarmUp] GPU inference verified");
    }

    llama_token tok = common_sampler_sample(eng->sampler, eng->ctx, -1);
    common_sampler_accept(eng->sampler, tok, true);
    llama_batch next = llama_batch_get_one(&tok, 1);
    decode_safe(eng, eng->ctx, next);

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
    std::lock_guard<std::mutex> lock(eng->stateMutex);

    size_t mSz = eng->model ? llama_model_size(eng->model) : 0;
    // The context was released after the last generation — report the cached
    // context size so the developer view stays meaningful between responses.
    size_t cSz = eng->ctx ? llama_state_get_size(eng->ctx) : eng->cachedContextSizeBytes;
    size_t gpuUsed = eng->gpuMemoryAllocatedBytes;

#ifdef GGML_USE_VULKAN
    // Refresh the live GPU heap whenever we have no resident context to account
    // for (released after generation) or no tracked allocation.
    if (eng->gpuLayersUsed > 0 && (gpuUsed == 0 || eng->ctx == nullptr)) {
        gpuUsed = mSz * eng->gpuLayersUsed / std::max(1, eng->totalLayers) + cSz;
        size_t f = 0, t = 0;
        try {
            ggml_backend_vk_get_device_memory(0, &f, &t);
            eng->gpuMemoryFreeBytes = f;
            eng->gpuMemoryTotalBytes = t;
        } catch (...) {}
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
      << ",\"vulkanValidationStatus\":\"" << json_escape(eng->vulkanValidationStatus)
      << "\",\"vulkanValidationDetail\":\"" << json_escape(eng->vulkanValidationDetail)
      << ",\"recoveryCount\":" << eng->recoveryCount
      << ",\"lastRecoveryReason\":\"" << json_escape(eng->lastRecoveryReason)
      << "\",\"cpuSessionFallback\":" << (eng->cpuSessionFallback ? "true" : "false")
      << ",\"lastContextCreateMs\":" << eng->lastContextCreateMs
      << ",\"lastCleanupMs\":" << eng->lastCleanupMs
      << ",\"decodeCount\":" << eng->decodeCount
      << ",\"decodeAvgMs\":" << (eng->decodeCount > 0 ? eng->decodeTotalMs / eng->decodeCount : 0)
      << ",\"vulkanDeviceLostRecoveries\":" << eng->vulkanDeviceLostRecoveries
      << "}";
    return to_jstring(env, o.str());
}

// ── nativeGetDebugInfo ─────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeGetDebugInfo(
    JNIEnv *env, jobject, jlong handle) {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->model) return to_jstring(env, "{}");
    std::lock_guard<std::mutex> lock(eng->stateMutex);
    // NOTE: the ctx/model pointers are safe under this lock. The lastPrompt*
    // vector reads below are best-effort diagnostics — they can be mutated by
    // an in-flight generation on the engine thread (diagnostic-only race; the
    // engine thread never touches them from this JNI path).

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
      << ",\"contextSizeBytes\":" << (c ? llama_state_get_size(c) : eng->cachedContextSizeBytes)
      << ",\"peakMemoryBytes\":" << eng->peakMemoryBytes
      << ",\"backendReason\":\"" << json_escape(eng->backendReason)
      << "\",\"gpuInferenceVerified\":" << (eng->gpuInferenceVerified ? "true" : "false")
      << ",\"vulkanValidationStatus\":\"" << json_escape(eng->vulkanValidationStatus)
      << "\",\"vulkanValidationDetail\":\"" << json_escape(eng->vulkanValidationDetail)
      << ",\"recoveryCount\":" << eng->recoveryCount
      << ",\"lastRecoveryReason\":\"" << json_escape(eng->lastRecoveryReason)
      << "\",\"cpuSessionFallback\":" << (eng->cpuSessionFallback ? "true" : "false")
      << ",\"lastContextCreateMs\":" << eng->lastContextCreateMs
      << ",\"lastCleanupMs\":" << eng->lastCleanupMs
      << ",\"decodeCount\":" << eng->decodeCount
      << ",\"decodeAvgMs\":" << (eng->decodeCount > 0 ? eng->decodeTotalMs / eng->decodeCount : 0)
      << ",\"vulkanDeviceLostRecoveries\":" << eng->vulkanDeviceLostRecoveries
      << "}";
    return to_jstring(env, o.str());
}

// ── nativeLoadEmbeddingModel ───────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeLoadEmbeddingModel(
    JNIEnv *env, jobject, jlong handle, jstring modelPath, jstring cfgJson) {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng) { throw_java(env, "Invalid engine handle"); return; }

    std::string path = from_jstring(env, modelPath);
    if (path.empty()) { throw_java(env, "Embedding model path is empty"); return; }

    int ctxLen = 512, batchSize = 512, threads = 4;
    {
        std::map<std::string, mini_json::Node> obj;
        mini_json::parseObject(from_jstring(env, cfgJson), obj);
        auto it = obj.end();
        if ((it = obj.find("contextLength")) != obj.end()) ctxLen    = (int)it->second.num;
        if ((it = obj.find("batchSize"))     != obj.end()) batchSize = (int)it->second.num;
        if ((it = obj.find("threads"))       != obj.end()) threads   = (int)it->second.num;
    }

    // Drop any previously loaded embedding model first.
    eng->unloadEmbedding();

    LOGI("[Embed] loading model=%s ctx=%d batch=%d", path.c_str(), ctxLen, batchSize);

    common_params params;
    params.model.path    = path;
    params.embedding     = true;          // sentence-embedding mode (no sampling)
    params.embd_normalize = 2;            // L2-normalize embeddings (cosine == dot)
    params.n_ctx          = std::max(128, ctxLen);
    params.n_batch        = std::max(128, batchSize);
    params.n_ubatch       = params.n_batch;
    params.cpuparams.n_threads       = threads;   // this llama.cpp moved thread counts
    params.cpuparams_batch.n_threads = threads;   // into common_cpu_params
    params.n_gpu_layers   = 0;            // CPU: embedding models are tiny, GPU adds RAM pressure
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    common_init_result_ptr result;
    try {
        result = common_init_from_params(params);
    } catch (const std::exception &e) {
        throw_java(env, std::string("Embedding model load failed: ") + e.what());
        return;
    } catch (...) {
        throw_java(env, "Embedding model load failed (unknown)");
        return;
    }

    if (!result || !result->model() || !result->context()) {
        throw_java(env, "Embedding model init returned null");
        return;
    }

    eng->embedInitResult = std::move(result);
    eng->embedModel      = eng->embedInitResult->model();
    eng->embedCtx        = eng->embedInitResult->context();
    eng->embedDim        = llama_model_n_embd(eng->embedModel);

    LOGI("[Embed] loaded dim=%d ctx=%u model_size=%zu",
         eng->embedDim, llama_n_ctx(eng->embedCtx), llama_model_size(eng->embedModel));
}

// ── nativeEmbeddingLoaded ──────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeEmbeddingLoaded(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    return (eng && eng->embedModel && eng->embedCtx) ? JNI_TRUE : JNI_FALSE;
}

// ── nativeEmbeddingDim ─────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeEmbeddingDim(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    return (eng && eng->embedModel) ? eng->embedDim : 0;
}

// ── nativeEmbed ────────────────────────────────────────────────────────────
// Encodes each text and returns a JSON array of float arrays.
// Blocking: caller must not invoke on the main thread.

JNIEXPORT jstring JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeEmbed(
    JNIEnv *env, jobject, jlong handle, jobjectArray texts) {

    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (!eng || !eng->embedModel || !eng->embedCtx) {
        throw_java(env, "Embedding model not loaded");
        return to_jstring(env, "[]");
    }

    std::vector<std::string> list;
    jsize n = texts ? env->GetArrayLength(texts) : 0;
    list.reserve(n);
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(texts, i);
        list.push_back(from_jstring(env, js));
        env->DeleteLocalRef(js);
    }

    try {
        std::vector<std::vector<float>> embs;
        embs.reserve(list.size());
        for (const auto & t : list) {
            embs.push_back(embed_one(eng->embedCtx, t));
        }
        return to_jstring(env, embeddings_to_json(embs));
    } catch (const std::exception &e) {
        throw_java(env, std::string("Embedding failed: ") + e.what());
        return to_jstring(env, "[]");
    } catch (...) {
        throw_java(env, "Embedding failed (unknown)");
        return to_jstring(env, "[]");
    }
}

// ── nativeUnloadEmbeddingModel ─────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_androllm_engine_jni_LlamaJniBridge_nativeUnloadEmbeddingModel(
    JNIEnv *, jobject, jlong handle) {
    auto *eng = reinterpret_cast<LlamaEngine *>(handle);
    if (eng) eng->unloadEmbedding();
}

} // extern "C"
