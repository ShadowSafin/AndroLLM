package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end compatibility contract for EVERY family: render "Hello" through
 * the family's official template (prompt shape), then push a GENERATION
 * through that family's [OutputDecoder] and verify that (a) the reply text
 * survives byte-for-byte and (b) no special token or marker can leak into
 * user-facing output. This is the round-trip the engine performs on every
 * answer.
 */
class ModelFamilyCompatibilityTest {

    private val renderer = ChatTemplateRenderer()

    @Test
    fun `every family survives the prompt-to-clean round trip`() {
        for (config in ModelFamilyRegistry.all) {
            val family = config.family
            // 1. The prompt shape must carry the user text (template contract).
            val prompt = renderer.render(
                config.chatTemplate,
                listOf(ChatTemplateRenderer.RenderMessage("user", "Hello")),
                bosToken = config.specialTokens.bos,
                eosToken = config.specialTokens.eos,
                addGenerationPrompt = true
            )
            assertTrue("${family.displayName}: prompt lost the user text", prompt.contains("Hello"))

            // 2. Worst-case generation: the reply followed by EVERY special
            // token the family knows, plus thinking-channel markers. The stop
            // sequences come last so the first cut lands at the very end.
            val tokens = config.specialTokens.all + listOfNotNull(
                config.thinkingChannel?.start,
                config.thinkingChannel?.end
            )
            val generation = "Hi there!" + tokens.joinToString("")
            val clean = OutputDecoder(config).clean(generation)

            assertEquals("${family.displayName}: reply text altered", "Hi there!", clean)
        }
    }

    @Test
    fun `qwen output with tool-call garbage is fully clean`() {
        val config = ModelFamilyRegistry.configFor(ModelFamily.QWEN2P5)
        val raw = "The battery is at 87%. <|im_start|>assistant\n" +
            "<|tool_call|>{\"name\":\"get_battery\",\"arguments\":{}}<|im_end|>\n" +
            "<|im_start|>assistant\nThe battery is at 87%.<|im_end|>"
        val clean = OutputDecoder(config).clean(raw)
        assertTrue(clean.contains("87%"))
        for (token in config.forbiddenInOutput) {
            assertFalse(clean.contains(token))
        }
    }
}