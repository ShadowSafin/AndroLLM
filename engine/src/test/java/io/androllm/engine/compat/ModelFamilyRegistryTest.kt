package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [ModelFamilyRegistry] + [ModelCompatibilityResolver] —
 * the detection order (container model type → embedded template → stop tokens
 * → name) and the failure contract for unknown models.
 */
class ModelFamilyRegistryTest {

    private fun container(
        modelTypeName: String? = null,
        template: String? = null,
        stopTokens: List<String> = emptyList()
    ) = ContainerMetadata(
        modelTypeName = modelTypeName,
        jinjaPromptTemplate = template,
        stopTokens = stopTokens
    )

    @Test
    fun `container model type wins`() {
        val r = ModelFamilyRegistry.resolve(container(modelTypeName = "qwen3"), "anything.bin")
        assertEquals(ModelFamily.QWEN3, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.CONTAINER_MODEL_TYPE, r.source)
    }

    @Test
    fun `gemma model type maps to gemma`() {
        val r = ModelFamilyRegistry.resolve(container(modelTypeName = "gemma3"), "m.bin")
        assertEquals(ModelFamily.GEMMA, r.family)
    }

    @Test
    fun `embedded template identifies qwen3 by thinking marker`() {
        val r = ModelFamilyRegistry.resolve(
            container(template = ChatTemplates.qwen3, modelTypeName = null),
            "m.bin"
        )
        assertEquals(ModelFamily.QWEN3, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.EMBEDDED_TEMPLATE, r.source)
    }

    @Test
    fun `embedded qwen template identifies qwen2p5`() {
        val r = ModelFamilyRegistry.resolve(container(template = ChatTemplates.qwen), "m.bin")
        assertEquals(ModelFamily.QWEN2P5, r.family)
    }

    @Test
    fun `stop tokens identify qwen`() {
        val r = ModelFamilyRegistry.resolve(
            container(stopTokens = listOf("<|im_end|>")),
            "m.bin"
        )
        assertEquals(ModelFamily.QWEN2P5, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.CONTAINER_STOP_TOKENS, r.source)
    }

    @Test
    fun `name fallback works when no metadata exists`() {
        val r = ModelFamilyRegistry.resolve(null, "deepseek-v3-7b.litertlm")
        assertEquals(ModelFamily.DEEPSEEK, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.NAME_FALLBACK, r.source)
    }

    @Test
    fun `name is never used when metadata exists`() {
        // Container says qwen3, name says gemma — the container must win.
        val r = ModelFamilyRegistry.resolve(container(modelTypeName = "qwen3"), "gemma.bin")
        assertEquals(ModelFamily.QWEN3, r.family)
    }

    @Test
    fun `unknown model fails with actionable message`() {
        val e = assertThrows(ModelCompatibilityException::class.java) {
            ModelFamilyRegistry.resolve(null, "mystery-model.bin")
        }
        assertTrue(e.message!!.contains("supported families"))
        assertTrue(e.message!!.contains("Gemma"))
    }

    @Test
    fun `known family with conflicting metadata fails`() {
        val e = assertThrows(ModelCompatibilityException::class.java) {
            ModelFamilyRegistry.resolve(container(modelTypeName = "some_new_type"), "gemma.bin")
        }
        assertTrue(e.message!!.contains("does not match any supported family"))
    }

    @Test
    fun `every registered family has a config with a renderable template`() {
        val renderer = ChatTemplateRenderer()
        for (config in ModelFamilyRegistry.all) {
            val out = renderer.render(
                config.chatTemplate,
                listOf(ChatTemplateRenderer.RenderMessage("user", "Hello")),
                bosToken = config.specialTokens.bos,
                eosToken = config.specialTokens.eos,
                addGenerationPrompt = true
            )
            assertTrue("${config.family.displayName} prompt lost the user text", out.contains("Hello"))
        }
    }

    @Test
    fun `stop sequences are derived from end markers`() {
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.GEMMA).stopSequences.contains("<end_of_turn>"))
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.QWEN2P5).stopSequences.contains("<|im_end|>"))
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.DEEPSEEK).stopSequences.contains("<|EOT|>"))
    }

    @Test
    fun `end-of-text markers that double as eos are stop sequences`() {
        // Qwen pads/unks with <|endoftext|> — generation must stop on it too.
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.QWEN2P5).stopSequences.contains("<|endoftext|>"))
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.QWEN3).stopSequences.contains("<|endoftext|>"))
        // Llama 3 pads/unks with <|end_of_text|>.
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.LLAMA3).stopSequences.contains("<|end_of_text|>"))
        // DeepSeek pads/unks with <|end?of?sentence|>.
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.DEEPSEEK).stopSequences.contains("<|end?of?sentence|>"))
        // Llama-style families stop on </s>.
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.MISTRAL).stopSequences.contains("</s>"))
        assertTrue(ModelFamilyRegistry.configFor(ModelFamily.TINYLLAMA).stopSequences.contains("</s>"))
        // Gemma's pad token is <pad>, not an end marker — it must NOT stop.
        assertFalse(ModelFamilyRegistry.configFor(ModelFamily.GEMMA).stopSequences.contains("<pad>"))
        assertFalse(ModelFamilyRegistry.configFor(ModelFamily.GEMMA).stopSequences.contains("<unk>"))
    }

    @Test
    fun `qwen3 config declares the thinking channel`() {
        val config = ModelFamilyRegistry.configFor(ModelFamily.QWEN3)
        assertEquals("<think>", config.thinkingChannel?.start)
        assertEquals("</think>", config.thinkingChannel?.end)
    }

    @Test
    fun `every family has non-empty stop sequences and non-blank tokens`() {
        for (config in ModelFamilyRegistry.all) {
            assertTrue(
                "${config.family.displayName} has no stop sequences",
                config.stopSequences.isNotEmpty()
            )
            assertTrue(
                "${config.family.displayName} has an empty special token",
                config.specialTokens.all.none { it.isBlank() }
            )
        }
    }
}