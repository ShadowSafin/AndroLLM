package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [ModelFamilyRegistry] + [ModelCompatibilityResolver] —
 * the detection order (container model type → embedded template → catalog
 * family → stop tokens → name → generic fallback) and the contract that no
 * model ever fails the load for lack of a family match.
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
    fun `unknown model falls back to generic instead of failing`() {
        val r = ModelFamilyRegistry.resolve(null, "mystery-model.bin")
        assertEquals(ModelFamily.GENERIC, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.GENERIC_FALLBACK, r.source)
    }

    @Test
    fun `unknown container type auto-resolves to generic instead of failing`() {
        // A container identifier the runtime supports but the registry does
        // not know yet must never fail the load — it degrades to generic
        // container-template mode.
        val r = ModelFamilyRegistry.resolve(container(modelTypeName = "some_new_type"), "gemma.bin")
        assertEquals(ModelFamily.GENERIC, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.GENERIC_FALLBACK, r.source)
    }

    @Test
    fun `fast_vlm container maps to generic engine mode`() {
        val r = ModelFamilyRegistry.resolve(container(modelTypeName = "fast_vlm"), "FastVLM.bin")
        assertEquals(ModelFamily.GENERIC, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.CONTAINER_MODEL_TYPE, r.source)
    }

    @Test
    fun `minicpm5 container maps to generic engine mode`() {
        val r = ModelFamilyRegistry.resolve(container(modelTypeName = "minicpm5"), "MiniCPM5.bin")
        assertEquals(ModelFamily.GENERIC, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.CONTAINER_MODEL_TYPE, r.source)
    }

    @Test
    fun `lfm2 container maps to llama3`() {
        val r = ModelFamilyRegistry.resolve(container(modelTypeName = "lfm2"), "LFM2.5.bin")
        assertEquals(ModelFamily.LLAMA3, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.CONTAINER_MODEL_TYPE, r.source)
    }

    @Test
    fun `generic_model container falls through to template detection`() {
        val r = ModelFamilyRegistry.resolve(
            container(modelTypeName = "generic_model", template = ChatTemplates.qwen),
            "m.bin"
        )
        assertEquals(ModelFamily.QWEN2P5, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.EMBEDDED_TEMPLATE, r.source)
    }

    @Test
    fun `catalog family resolves a generic container`() {
        // The catalog says TinySwallow (registry: → QWEN2P5); the container
        // carries no family signal — the catalog family fills the gap.
        val r = ModelFamilyRegistry.resolve(
            container(modelTypeName = "generic_model"),
            "TinySwallow.bin",
            catalogFamily = "TinySwallow"
        )
        assertEquals(ModelFamily.QWEN2P5, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.CATALOG_FAMILY, r.source)
    }

    @Test
    fun `catalog family without engine mapping is skipped`() {
        // Qwen spans several engine families — its spec has no engine key, so
        // the catalog family alone cannot resolve it; the generic fallback
        // applies when no other evidence exists.
        val r = ModelFamilyRegistry.resolve(
            container(modelTypeName = "generic_model"),
            "m.bin",
            catalogFamily = "Qwen"
        )
        assertEquals(ModelFamily.GENERIC, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.GENERIC_FALLBACK, r.source)
    }

    @Test
    fun `catalog family never overrides container metadata`() {
        // Container says qwen3, catalog says TinySwallow — the container wins.
        val r = ModelFamilyRegistry.resolve(
            container(modelTypeName = "qwen3"),
            "TinySwallow.bin",
            catalogFamily = "TinySwallow"
        )
        assertEquals(ModelFamily.QWEN3, r.family)
        assertEquals(ModelCompatibilityResolver.DetectionSource.CONTAINER_MODEL_TYPE, r.source)
    }

    @Test
    fun `every container identifier maps without rejection`() {
        // All 10 LlmModelType identifiers must resolve to SOMETHING — none
        // may fail the load.
        val identifiers = listOf(
            "generic_model", "qwen3", "qwen2p5", "gemma3", "gemma3n",
            "gemma4", "function_gemma", "fast_vlm", "lfm2", "minicpm5"
        )
        for (identifier in identifiers) {
            val r = ModelFamilyRegistry.resolve(container(modelTypeName = identifier), "m.bin")
            assertTrue("identifier '$identifier' fell to generic despite a mapping",
                r.source != ModelCompatibilityResolver.DetectionSource.GENERIC_FALLBACK ||
                    identifier == "generic_model")
        }
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