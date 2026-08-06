package io.androllm.core.navigation

import android.net.Uri
import io.androllm.core.common.AppConstants

/**
 * Central registry of all navigation routes in the app.
 */
object Routes {
    const val SPLASH = AppConstants.Navigation.SPLASH_ROUTE
    const val ONBOARDING = AppConstants.Navigation.ONBOARDING_ROUTE
    const val AUTH = AppConstants.Navigation.AUTH_ROUTE
    const val PROFILE_SETUP = AppConstants.Navigation.PROFILE_SETUP_ROUTE
    const val HOME = AppConstants.Navigation.HOME_ROUTE
    const val CHAT = AppConstants.Navigation.CHAT_ROUTE
    const val CHAT_DETAIL = AppConstants.Navigation.CHAT_DETAIL_ROUTE
    const val CHAT_WITH_PROMPT = AppConstants.Navigation.CHAT_WITH_PROMPT_ROUTE
    const val MODELS = AppConstants.Navigation.MODELS_ROUTE
    const val SETTINGS = AppConstants.Navigation.SETTINGS_ROUTE
    const val MODEL_DETAIL = AppConstants.Navigation.MODEL_DETAIL_ROUTE
    const val PROFILE = AppConstants.Navigation.PROFILE_ROUTE
    const val PROMPTS = AppConstants.Navigation.PROMPTS_ROUTE
    const val DEVELOPER = AppConstants.Navigation.DEVELOPER_ROUTE
    const val CLOUD_PROVIDERS = AppConstants.Navigation.CLOUD_PROVIDERS_ROUTE
    const val CLOUD_MODELS = AppConstants.Navigation.CLOUD_MODELS_ROUTE

    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_MODEL_ID = "modelId"
    const val ARG_PROMPT = "prompt"
    const val ARG_PROVIDER_ID = "providerId"

    /**
     * Builds the route for a specific conversation.
     */
    fun chatDetail(conversationId: String): String = "chat/$conversationId"

    /**
     * Builds the route for a specific model.
     */
    fun modelDetail(modelId: String): String = "models/$modelId"

    /**
     * Builds the route that opens the chat with a pre-filled prompt
     * (used by the Prompt Library).
     */
    fun chatWithPrompt(prompt: String): String = "chat/prompt/${Uri.encode(prompt)}"

    /**
     * Builds the route for a provider's cloud model list.
     */
    fun cloudModels(providerId: String): String = "cloud/models/$providerId"
}
