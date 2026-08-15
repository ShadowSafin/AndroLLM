package io.androllm.core.cloud.runtime

import io.androllm.core.cloud.ProviderManager
import io.androllm.core.runtime.Runtime
import io.androllm.core.runtime.RuntimeCategory
import io.androllm.core.runtime.RuntimeStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the cloud inference runtime into the central
 * [io.androllm.core.runtime.RuntimeRegistry].
 *
 * Cloud providers in AndroLLM are user-defined LiteLLM-compatible endpoints,
 * so Gemini / OpenAI / OpenRouter / Ollama (and any other provider) appear
 * here as configured providers — exactly as the user set them up in
 * Cloud Providers. This adapter only mirrors [ProviderManager]; it never
 * connects or disconnects anything.
 */
@Singleton
class CloudRuntime @Inject constructor(
    private val manager: ProviderManager
) : Runtime {

    override val id = "cloud"
    override val displayName = "Cloud Providers"
    override val category = RuntimeCategory.CLOUD
    override val description = "LiteLLM gateway for Gemini, OpenAI, OpenRouter, Ollama and any OpenAI-compatible endpoint."

    override suspend fun status(): RuntimeStatus = runCatching {
        val settings = manager.current()
        val providers = settings.providers.filter { it.enabled }
        if (settings.enabled && providers.isNotEmpty()) {
            val names = providers.joinToString(", ") { it.name }
            RuntimeStatus(true, "${providers.size} provider(s) configured — $names")
        } else {
            RuntimeStatus(
                available = false,
                summary = "Cloud chat is off",
                detail = "Enable Cloud chat and add a provider (Gemini, OpenAI, OpenRouter, Ollama…) in Cloud Providers."
            )
        }
    }.getOrElse { e ->
        RuntimeStatus(false, "Status check failed", e.message ?: e.javaClass.simpleName)
    }
}
