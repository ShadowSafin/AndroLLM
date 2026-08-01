package io.androllm.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.androllm.core.navigation.Routes
import io.androllm.feature.chat.ChatScreen
import io.androllm.feature.home.HomeScreen
import io.androllm.feature.models.ModelsScreen
import io.androllm.feature.settings.SettingsScreen
import io.androllm.feature.splash.SplashScreen

/**
 * Root navigation host wiring all destinations.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(navController = navController)
        }

        composable(Routes.CHAT) {
            ChatScreen(
                navController = navController,
                onNavigateUp = { navController.navigateUp() }
            )
        }

        composable(
            route = Routes.CHAT_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString(Routes.ARG_CONVERSATION_ID).orEmpty()
            ChatScreen(
                navController = navController,
                conversationId = conversationId,
                onNavigateUp = { navController.navigateUp() }
            )
        }

        composable(Routes.MODELS) {
            ModelsScreen(navController = navController)
        }

        composable(
            route = Routes.MODEL_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_MODEL_ID) { type = NavType.StringType })
        ) {
            // TODO: Model detail screen in Phase 2.
            navController.navigateUp()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(navController = navController)
        }
    }
}
