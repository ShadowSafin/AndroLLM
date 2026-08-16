package io.androllm.core.attachments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilitiesTest {

    @Test
    fun `vision models are detected`() {
        assertTrue(ProviderCapabilities.supportsVision("openai/gpt-4o"))
        assertTrue(ProviderCapabilities.supportsVision("anthropic/claude-3-5-sonnet"))
        assertTrue(ProviderCapabilities.supportsVision("google/gemini-2.0-flash"))
        assertTrue(ProviderCapabilities.supportsVision("meta-llama/llama-3.2-90b-vision"))
        assertTrue(ProviderCapabilities.supportsVision("qwen/qwen2.5-vl-72b"))
    }

    @Test
    fun `text-only and unknown models are not vision`() {
        assertFalse(ProviderCapabilities.supportsVision("openai/gpt-3.5-turbo"))
        assertFalse(ProviderCapabilities.supportsVision("anthropic/claude-2.1"))
        assertFalse(ProviderCapabilities.supportsVision("some/unknown-model"))
        assertFalse(ProviderCapabilities.supportsVision(null))
        assertFalse(ProviderCapabilities.supportsVision(""))
    }

    @Test
    fun `file upload models are detected`() {
        assertTrue(ProviderCapabilities.supportsFileUploads("openai/gpt-4o"))
        assertTrue(ProviderCapabilities.supportsFileUploads("google/gemini-2.5-pro"))
        assertFalse(ProviderCapabilities.supportsFileUploads("openai/gpt-3.5-turbo"))
    }

    @Test
    fun `attachments are cloud-only`() {
        // Every cloud model id supports the attachment pipeline.
        assertTrue(ProviderCapabilities.supportsAttachments("openai/gpt-4o"))
        assertTrue(ProviderCapabilities.supportsAttachments("anthropic/claude-3-5-sonnet"))
        assertTrue(ProviderCapabilities.supportsAttachments("openrouter/deepseek/deepseek-chat"))
        assertTrue(ProviderCapabilities.supportsAttachments("groq/llama-3.3-70b-versatile"))
        // Local runtimes pass NO cloud model id → attachments unsupported,
        // without any provider-name check.
        assertFalse(ProviderCapabilities.supportsAttachments(null))
        assertFalse(ProviderCapabilities.supportsAttachments(""))
    }

    @Test
    fun `streaming and tool calling are cloud capabilities`() {
        assertTrue(ProviderCapabilities.supportsStreaming("openai/gpt-4o"))
        assertFalse(ProviderCapabilities.supportsStreaming(null))
        assertTrue(ProviderCapabilities.supportsToolCalling("openai/gpt-4o"))
        assertFalse(ProviderCapabilities.supportsToolCalling(""))
    }
}
