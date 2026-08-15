package io.androllm.engine.compat

/**
 * The official chat templates of each supported model family, transcribed from
 * the reference `tokenizer_config.json` on Hugging Face. These are the exact
 * templates the family's own pipeline uses — forcing them at conversation
 * creation (via `ExperimentalFlags.overwritePromptTemplate`) guarantees the
 * prompt shape matches what the model was trained on, regardless of what the
 * container embedded.
 *
 * The strings use Jinja syntax with a strict, limited feature set (if/for/set,
 * string concatenation, a few filters) which [ChatTemplateRenderer] implements.
 */
object ChatTemplates {

    private const val T = "\t"
    private const val N = "\n"

    /** Gemma 3 (google/gemma-3-*-it) — roles are `user`/`model`. */
    val gemma: String =
        "{{ bos_token }}" +
            "{% if messages[0]['role'] == 'system' %}" +
            "{{ raise_exception('System role not supported') }}" +
            "{% endif %}" +
            "{% for message in messages %}" +
            "{% if message['role'] == 'user' %}{% set role = 'user' %}" +
            "{% elif message['role'] == 'model' %}{% set role = 'model' %}" +
            "{% else %}{{ raise_exception('Only user and model roles are supported, not ' + message['role']) }}{% endif %}" +
            "{{ '<start_of_turn>' + role + '\\n' + message['content'] | trim + '<end_of_turn>\\n' }}" +
            "{% endfor %}" +
            "{% if add_generation_prompt %}{{ '<start_of_turn>model\\n' }}{% endif %}"

    /**
     * The same Gemma template with the system branch neutralized. The official
     * template *raises* on a system role, but this app supports system prompts
     * (ConversationConfig.systemInstruction), so the engine uses this variant:
     * system messages are skipped, everything else renders byte-identically.
     */
    val gemmaLenient: String =
        "{{ bos_token }}" +
            "{% for message in messages %}" +
            "{% if message['role'] == 'system' %}{% set skip = true %}{% else %}{% set skip = false %}{% endif %}" +
            "{% if not skip %}" +
            "{% if message['role'] == 'user' %}{% set role = 'user' %}" +
            "{% elif message['role'] == 'model' %}{% set role = 'model' %}{% endif %}" +
            "{{ '<start_of_turn>' + role + '\\n' + message['content'] | trim + '<end_of_turn>\\n' }}" +
            "{% endif %}" +
            "{% endfor %}" +
            "{% if add_generation_prompt %}{{ '<start_of_turn>model\\n' }}{% endif %}"

    /** Qwen2 / Qwen2.5 (Qwen/Qwen2.5-*-Instruct) — also the SmolLM2 template. */
    val qwen: String =
        "{% for message in messages %}" +
            "{% if loop.first and message['role'] != 'system' %}" +
            "{{ '<|im_start|>system\\nYou are a helpful assistant.<|im_end|>\\n' }}" +
            "{% endif %}" +
            "{{ '<|im_start|>' + message['role'] + '\\n' + message['content'] + '<|im_end|>\\n' }}" +
            "{% endfor %}" +
            "{% if add_generation_prompt %}{{ '<|im_start|>assistant\\n' }}{% endif %}"

    /**
     * Qwen3 (Qwen/Qwen3-*-B) — thinking channel enabled by default. The
     * official template's tool branch is never reached in plain chat.
     */
    val qwen3: String =
        "{% if not add_generation_prompt is defined %}{% set add_generation_prompt = false %}{% endif %}" +
            "{% if not include_system is defined %}{% set include_system = true %}{% endif %}" +
            "{% if not enable_thinking is defined %}{% set enable_thinking = true %}{% endif %}" +
            "{% if not is_tool_call is defined %}{% set is_tool_call = false %}{% endif %}" +
            "{% for message in messages %}" +
            "{% if message['role'] == 'system' and include_system %}" +
            "{{ '<|im_start|>system\\n' + message['content'] + '<|im_end|>\\n' }}" +
            "{% endif %}" +
            "{% if message['role'] == 'user' %}" +
            "{{ '<|im_start|>user\\n' + message['content'] + '<|im_end|>\\n' }}" +
            "{% endif %}" +
            "{% if message['role'] == 'assistant' %}" +
            "{% if message['content'] is none %}" +
            "{{ '<|im_start|>assistant\\n<|tool_call|>\\n' + message['tool_calls'] + '<|im_end|>\\n' }}" +
            "{% else %}" +
            "{{ '<|im_start|>assistant\\n' + message['content'] + '<|im_end|>\\n' }}" +
            "{% endif %}" +
            "{% endif %}" +
            "{% endfor %}" +
            "{% if add_generation_prompt %}" +
            "{{ '<|im_start|>assistant\\n' }}" +
            "{% if enable_thinking is defined and enable_thinking is false %}" +
            "{{ '<think>\\n\\n</think>\\n\\n' }}" +
            "{% endif %}" +
            "{% endif %}"

    /** Llama 3 / 3.1 / 3.2 / 3.3 (meta-llama/Meta-Llama-3-*-Instruct). */
    val llama3: String =
        "{{- bos_token }}" +
            "{%- for message in messages %}" +
            "{{- '<|start_header_id|>' + message['role'] + '<|end_header_id|>\\n\\n' + message['content'] + '<|eot_id|>' }}" +
            "{%- endfor %}" +
            "{%- if add_generation_prompt %}" +
            "{{- '<|start_header_id|>assistant<|end_header_id|>\\n\\n' }}" +
            "{%- endif %}"

    /** Phi-3 / Phi-4 (microsoft/Phi-3.5-mini-instruct). */
    val phi: String =
        "{%- for message in messages %}" +
            "{%- if message['role'] == 'system' %}" +
            "{{- '<|system|>\\n' + message['content'] + '<|end|>\\n' }}" +
            "{%- elif message['role'] == 'user' %}" +
            "{{- '<|user|>\\n' + message['content'] + '<|end|>\\n' }}" +
            "{%- elif message['role'] == 'assistant' %}" +
            "{{- '<|assistant|>\\n' + message['content'] + '<|end|>\\n' }}" +
            "{%- endif %}" +
            "{%- endfor %}" +
            "{%- if add_generation_prompt %}{{- '<|assistant|>\\n' }}{%- endif %}"

    /** Mistral v0.3 (mistralai/Mistral-7B-Instruct-v0.3). */
    val mistral: String =
        "{{- bos_token }}" +
            "{%- for message in messages %}" +
            "{%- if message['role'] == 'user' %}" +
            "{{- '[INST] ' + message['content'] + ' [/INST]' }}" +
            "{%- elif message['role'] == 'assistant' %}" +
            "{{- ' ' + message['content'] + ' ' + eos_token }}" +
            "{%- endif %}" +
            "{%- endfor %}"

    /**
     * DeepSeek V3 (deepseek-ai/DeepSeek-V3) — current official template with
     * readable special tokens. The namespace/tool machinery is unreachable in
     * plain chat, so [ChatTemplateRenderer] implements this exact rendering
     * for system + user/assistant turns.
     */
    val deepseek: String =
        "{% if not add_generation_prompt is defined %}{% set add_generation_prompt = false %}{% endif %}" +
            "{% set ns = namespace(is_first=false, is_tool=false, is_output_first=true, system_prompt='', is_first_sp=true) %}" +
            "{%- for message in messages %}" +
            "{%- if message['role'] == 'system' %}" +
            "{%- if ns.is_first_sp %}{% set ns.system_prompt = ns.system_prompt + message['content'] %}{% set ns.is_first_sp = false %}" +
            "{%- else %}{% set ns.system_prompt = ns.system_prompt + '\\n\\n' + message['content'] %}{%- endif %}" +
            "{%- endif %}" +
            "{%- endfor %}" +
            "{{ bos_token }}{{ ns.system_prompt }}" +
            "{%- for message in messages %}" +
            "{%- if message['role'] == 'user' %}{%- set ns.is_tool = false -%}{{ '<|User|>' + message['content'] }}{%- endif %}" +
            "{%- if message['role'] == 'assistant' and message['content'] is not none %}" +
            "{%- if ns.is_tool %}{{ '<|tool?outputs?end|>' + message['content'] + '<|end?of?sentence|>' }}{%- set ns.is_tool = false -%}" +
            "{%- else %}{{ '<|Assistant|>' + message['content'] + '<|end?of?sentence|>' }}{%- endif %}" +
            "{%- endif %}" +
            "{%- endfor -%}" +
            "{% if ns.is_tool %}{{ '<|tool?outputs?end|>' }}{% endif %}" +
            "{% if add_generation_prompt and not ns.is_tool %}{{ '<|Assistant|>' }}{% endif %}"

    /** SmolLM2 (HuggingFaceTB/SmolLM2-*-Instruct) — same as Qwen2.5. */
    val smol: String = qwen

    /** TinyLlama 1.1B Chat (TinyLlama/TinyLlama-1.1B-Chat-v1.0). */
    val tinyLlama: String =
        "{%- for message in messages %}" +
            "{%- if message['role'] == 'system' %}" +
            "{{- '<|system|>\\n' + message['content'] + '</s>' }}" +
            "{%- elif message['role'] == 'user' %}" +
            "{{- '<|user|>\\n' + message['content'] + '</s>' }}" +
            "{%- elif message['role'] == 'assistant' %}" +
            "{{- '<|assistant|>\\n' + message['content'] + '</s>' }}" +
            "{%- endif %}" +
            "{%- endfor %}" +
            "{%- if add_generation_prompt %}{{- '<|assistant|>\\n' }}{%- endif %}"

    fun officialTemplateFor(family: ModelFamily): String = when (family) {
        ModelFamily.GEMMA -> gemma
        ModelFamily.QWEN2 -> qwen
        ModelFamily.QWEN2P5 -> qwen
        ModelFamily.QWEN3 -> qwen3
        ModelFamily.PHI -> phi
        ModelFamily.LLAMA3 -> llama3
        ModelFamily.DEEPSEEK -> deepseek
        ModelFamily.MISTRAL -> mistral
        ModelFamily.SMOL -> smol
        ModelFamily.TINYLLAMA -> tinyLlama
    }
}