package io.androllm.core.models.catalog

/**
 * Architectures accepted by the catalog validator.
 *
 * The base set mirrors the LLM_ARCH_NAMES map in src/llama-arch.cpp (137
 * loadable archs) — a guard from the GGUF era. The LiteRT catalog additionally
 * carries native LiteRT-LM containers (speech, vision, embedding) whose
 * architecture ids are not llama.cpp archs; they are listed below so the
 * validator keeps accepting official litert-community artifacts.
 */
object SupportedArchitectures {
    val ALL: Set<String> = setOf(
        "llama", "llama4", "deci", "falcon", "grok", "gpt2", "gptj", "gptneox",
        "mpt", "baichuan", "starcoder", "refact", "bert", "modern-bert",
        "nomic-bert", "nomic-bert-moe", "neo-bert", "jina-bert-v2", "jina-bert-v3",
        "eurobert", "bloom", "stablelm", "qwen", "qwen2", "qwen2moe", "qwen2vl",
        "qwen3", "qwen3moe", "qwen3next", "qwen3vl", "qwen3vlmoe", "qwen35",
        "qwen35moe", "phi2", "phi3", "phimoe", "plamo", "plamo2", "plamo3",
        "codeshell", "orion", "internlm2", "minicpm", "minicpm3", "gemma", "gemma2",
        "gemma3", "gemma3n", "gemma4", "gemma4-assistant", "gemma-embedding",
        "starcoder2", "mamba", "mamba2", "jamba", "falcon-h1", "xverse", "command-r",
        "cohere2", "cohere2moe", "dbrx", "olmo", "olmo2", "olmoe", "openelm",
        "arctic", "deepseek", "deepseek2", "deepseek2-ocr", "deepseek32",
        "deepseek4", "chatglm", "glm4", "glm4moe", "glm-dsa", "bitnet", "t5",
        "t5encoder", "jais", "jais2", "nemotron", "nemotron_h", "nemotron_h_moe",
        "exaone", "exaone4", "exaone-moe", "rwkv6", "rwkv6qwen2", "rwkv7", "arwkv7",
        "granite", "granitemoe", "granitehybrid", "chameleon", "wavtokenizer-dec",
        "plm", "bailingmoe", "bailingmoe2", "dots1", "arcee", "afmoe", "laguna",
        "ernie4_5", "ernie4_5-moe", "hunyuan-moe", "hunyuan-dense", "hunyuan_vl",
        "hy_v3", "smollm3", "gpt-oss", "lfm2", "lfm2moe", "dream", "smallthinker",
        "llada", "llada-moe", "seed_oss", "grovemoe", "apertus", "minimax-m2",
        "minimax-m3", "cogvlm", "rnd1", "pangu-embedded", "mistral3", "eagle3",
        "dflash", "mistral4", "paddleocr", "mimo2", "step35", "llama-embed",
        "maincoder", "kimi-linear", "talkie", "mellum", "nanbeige",
        // ---- LiteRT-native architectures (official litert-community builds) ----
        "phi4", "gemma-1.5", "translategemma", "functiongemma", "smolvlm2",
        "fastvlm", "mage-vl", "whisper", "moonshine", "parakeet", "qwen3-asr",
        "qwen3-omni", "codegemma", "qwen2.5-coder"
    )

    fun isSupported(architecture: String): Boolean =
        architecture.isNotBlank() && architecture in ALL
}
