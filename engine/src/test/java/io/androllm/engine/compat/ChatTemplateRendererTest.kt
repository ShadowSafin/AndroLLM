package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [ChatTemplateRenderer] against the OFFICIAL templates in
 * [ChatTemplates]. The expected strings below are the exact prompts the
 * reference models see ??? a renderer change that alters one of them changes the
 * prompt the model actually reads, which is why they are pinned byte-for-byte.
 */
class ChatTemplateRendererTest {

    private val renderer = ChatTemplateRenderer()

    private fun user(content: String) = ChatTemplateRenderer.RenderMessage("user", content)

    private fun model(content: String) = ChatTemplateRenderer.RenderMessage("assistant", content)

    private fun system(content: String) = ChatTemplateRenderer.RenderMessage("system", content)

    // ---- Qwen2.5 / SmolLM2 ---------------------------------------------------

    @Test
    fun `qwen single user turn matches official output`() {
        val out = renderer.render(
            ChatTemplates.qwen,
            listOf(user("Hello")),
            addGenerationPrompt = true
        )
        assertEquals(
            "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
                "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n",
            out
        )
    }

    @Test
    fun `qwen multi-turn keeps role blocks in order`() {
        val out = renderer.render(
            ChatTemplates.qwen,
            listOf(user("Hi"), model("Hello!"), user("Again")),
            addGenerationPrompt = true
        )
        assertEquals(
            "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
                "<|im_start|>user\nHi<|im_end|>\n" +
                "<|im_start|>assistant\nHello!<|im_end|>\n" +
                "<|im_start|>user\nAgain<|im_end|>\n<|im_start|>assistant\n",
            out
        )
    }

    @Test
    fun `smol renders identically to qwen`() {
        assertEquals(
            renderer.render(ChatTemplates.qwen, listOf(user("Hello")), addGenerationPrompt = true),
            renderer.render(ChatTemplates.smol, listOf(user("Hello")), addGenerationPrompt = true)
        )
    }

    // ---- Qwen3 ---------------------------------------------------------------

    @Test
    fun `qwen3 with generation prompt renders the assistant prefix`() {
        val out = renderer.render(
            ChatTemplates.qwen3,
            listOf(user("Hello")),
            addGenerationPrompt = true
        )
        assertEquals(
            "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n",
            out
        )
    }

    @Test
    fun `qwen3 with thinking disabled seeds the closed thinking block`() {
        val out = renderer.render(
            ChatTemplates.qwen3,
            listOf(user("Hello")),
            addGenerationPrompt = true,
            extraContext = mapOf("enable_thinking" to false)
        )
        assertEquals(
            "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n" + "<think>\n\n</think>\n\n",
            out
        )
    }

    @Test
    fun `qwen3 with system message renders it first`() {
        val out = renderer.render(
            ChatTemplates.qwen3,
            listOf(system("You are a pirate."), user("Ahoy")),
            addGenerationPrompt = true
        )
        assertEquals(
            "<|im_start|>system\nYou are a pirate.<|im_end|>\n" +
                "<|im_start|>user\nAhoy<|im_end|>\n<|im_start|>assistant\n",
            out
        )
    }
    // ---- Gemma ---------------------------------------------------------------

    @Test
    fun `gemma official template raises on system role`() {
        assertThrows(ChatTemplateRenderException::class.java) {
            renderer.render(ChatTemplates.gemma, listOf(system("sys"), user("Hi")))
        }
    }

    @Test
    fun `gemma lenient template drops system messages`() {
        val out = renderer.render(
            ChatTemplates.gemmaLenient,
            listOf(system("sys"), user("Hi")),
            bosToken = "<bos>",
            addGenerationPrompt = true
        )
        assertEquals("<bos><start_of_turn>user\nHi<end_of_turn>\n<start_of_turn>model\n", out)
    }

    @Test
    fun `gemma single turn matches official output`() {
        val out = renderer.render(
            ChatTemplates.gemmaLenient,
            listOf(user("Hello")),
            bosToken = "<bos>",
            addGenerationPrompt = true
        )
        assertEquals("<bos><start_of_turn>user\nHello<end_of_turn>\n<start_of_turn>model\n", out)
    }

    // ---- Llama 3 -------------------------------------------------------------

    @Test
    fun `llama3 single turn matches official output`() {
        val out = renderer.render(
            ChatTemplates.llama3,
            listOf(user("Hello")),
            bosToken = "<|begin_of_text|>",
            addGenerationPrompt = true
        )
        assertEquals(
            "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n\n",
            out
        )
    }

    // ---- Phi -----------------------------------------------------------------

    @Test
    fun `phi single turn matches official output`() {
        val out = renderer.render(
            ChatTemplates.phi,
            listOf(user("Hello")),
            addGenerationPrompt = true
        )
        assertEquals("<|user|>\nHello<|end|>\n<|assistant|>\n", out)
    }

    // ---- Mistral -------------------------------------------------------------

    @Test
    fun `mistral single turn matches official output`() {
        val out = renderer.render(
            ChatTemplates.mistral,
            listOf(user("Hello")),
            bosToken = "<s>",
            eosToken = "</s>",
            addGenerationPrompt = true
        )
        assertEquals("<s>[INST] Hello [/INST]", out)
    }

    // ---- DeepSeek ------------------------------------------------------------

    @Test
    fun `deepseek single turn matches official output`() {
        val out = renderer.render(
            ChatTemplates.deepseek,
            listOf(user("Hello")),
            bosToken = "<|begin?of?sentence|>",
            eosToken = "<|EOT|>",
            addGenerationPrompt = true
        )
        assertEquals("<|begin?of?sentence|><|User|>Hello<|Assistant|>", out)
    }

    @Test
    fun `deepseek renders system prompt once before user turn`() {
        val out = renderer.render(
            ChatTemplates.deepseek,
            listOf(system("Be kind."), user("Hello")),
            bosToken = "<|begin?of?sentence|>",
            eosToken = "<|EOT|>",
            addGenerationPrompt = true
        )
        assertEquals("<|begin?of?sentence|>Be kind.<|User|>Hello<|Assistant|>", out)
    }

    // ---- TinyLlama -----------------------------------------------------------

    @Test
    fun `tinyllama single turn matches official output`() {
        val out = renderer.render(
            ChatTemplates.tinyLlama,
            listOf(user("Hello")),
            addGenerationPrompt = true
        )
        assertEquals("<|user|>\nHello</s><|assistant|>\n", out)
    }

    // ---- Strictness -----------------------------------------------------------

    @Test
    fun `unsupported statement throws`() {
        assertThrows(ChatTemplateRenderException::class.java) {
            renderer.render("{% macro foo() %}x{% endmacro %}", emptyList())
        }
    }

    @Test
    fun `unterminated if throws`() {
        assertThrows(ChatTemplateRenderException::class.java) {
            renderer.render("{% if true %}x", emptyList())
        }
    }

    @Test
    fun `unterminated for throws`() {
        assertThrows(ChatTemplateRenderException::class.java) {
            renderer.render("{% for m in messages %}x", emptyList())
        }
    }

    @Test
    fun `unsupported call throws`() {
        assertThrows(ChatTemplateRenderException::class.java) {
            renderer.render("{{ unknown_call() }}", emptyList())
        }
    }

    @Test
    fun `nested if inside for inside if renders`() {
        val template =
            "{% if messages %}" +
                "{% for m in messages %}" +
                "{% if m['role'] == 'user' %}U{% else %}M{% endif %}" +
                "{% endfor %}" +
                "{% endif %}"
        val out = renderer.render(template, listOf(user("a"), model("b"), user("c")))
        assertEquals("UMU", out)
    }
}