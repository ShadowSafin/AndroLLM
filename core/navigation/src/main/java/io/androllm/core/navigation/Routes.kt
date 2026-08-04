package io.androllm.core.navigation

import io.androllm.core.common.AppConstants

/**
 * Central registry of all navigation routes in the app.
 */
object Routes {
    const val SPLASH = AppConstants.Navigation.SPLASH_ROUTE
    const val AUTH = AppConstants.Navigation.AUTH_ROUTE
    const val HOME = AppConstants.Navigation.HOME_ROUTE
    const val CHAT = AppConstants.Navigation.CHAT_ROUTE
    const val CHAT_DETAIL = AppConstants.Navigation.CHAT_DETAIL_ROUTE
    const val MODELS = AppConstants.Navigation.MODELS_ROUTE
    const val SETTINGS = AppConstants.Navigation.SETTINGS_ROUTE
    const val MODEL_DETAIL = AppConstants.Navigation.MODEL_DETAIL_ROUTE

    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_MODEL_ID = "modelId"

    /**
     * Builds the route for a specific conversation.
     */
    fun chatDetail(conversationId: String): String = "chat/$conversationId"

    /**
     * Builds the route for a specific model.
     */
    fun modelDetail(modelId: String): String = "models/$modelId"
}
