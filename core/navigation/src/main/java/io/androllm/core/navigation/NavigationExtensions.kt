package io.androllm.core.navigation

import androidx.navigation.NavController
import androidx.navigation.NavOptions

/**
 * Navigation extension functions for consistent transitions.
 */
fun NavController.navigateToHome() {
    navigate(Routes.HOME) {
        popUpTo(Routes.HOME) { inclusive = false }
        launchSingleTop = true
    }
}

fun NavController.navigateToChat(conversationId: String? = null) {
    val route = if (conversationId.isNullOrBlank()) Routes.CHAT else Routes.chatDetail(conversationId)
    navigate(route) {
        launchSingleTop = true
    }
}

fun NavController.navigateToModels() {
    navigate(Routes.MODELS) {
        launchSingleTop = true
    }
}

fun NavController.navigateToSettings() {
    navigate(Routes.SETTINGS) {
        launchSingleTop = true
    }
}

fun NavController.navigateToSplash() {
    navigate(Routes.SPLASH)
}

/**
 * Navigates home, clearing the back stack so back exits the app.
 */
fun NavController.navigateToHomeClearStack() {
    navigate(Routes.HOME) {
        popUpTo(Routes.SPLASH) { inclusive = true }
        launchSingleTop = true
    }
}
