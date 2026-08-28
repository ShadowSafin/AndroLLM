package io.androllm.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.auth.FirebaseAuth
import io.androllm.app.auth.FirebaseAuthScreen
import io.androllm.app.profile.ProfileSetupScreen
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.feature.setup.PermissionSetupScreen
import io.androllm.feature.setup.PermissionsAccessScreen
import io.androllm.core.navigation.Routes
import io.androllm.feature.chat.ChatScreen
import io.androllm.feature.developer.DeveloperScreen
import io.androllm.feature.developer.ToolDebugScreen
import io.androllm.feature.home.HomeScreen
import io.androllm.feature.models.ModelsScreen
import io.androllm.feature.onboarding.OnboardingScreen
import io.androllm.feature.profile.ProfileScreen
import io.androllm.feature.prompts.PromptLibraryScreen
import io.androllm.feature.settings.SettingsScreen
import io.androllm.feature.splash.SplashScreen
import kotlinx.coroutines.flow.first

/**
 * Root navigation host wiring all destinations.
 *
 * Entry flow:
 *   Splash → (already authenticated, setup done) Home
 *         → (already authenticated, setup pending) Setup
 *         → (onboarding not completed) Onboarding → Auth
 *         → (otherwise) Auth
 *   Auth   → (first sign-in) Profile Setup → Setup → Home
 *         → (returning user) Setup → Home
 */
@Composable
fun AppNavHost(
    preferencesDataStore: PreferencesDataStore,
    navController: NavHostController = rememberNavController(),
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    // Null until resolved — routing waits for this rather than assuming a value.
    var onboardingCompleted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        onboardingCompleted = preferencesDataStore.onboardingCompleted.first()
    }

    // Clears the whole back stack so the entry flow never lingers behind Home.
    // popUpTo the root graph (inclusive) is the standard "fresh start" idiom.
    fun navigateClearing(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Firebase is optional — an unconfigured/misconfigured Firebase must never
    // take down navigation. `FirebaseAuth.getInstance()` throws IllegalStateException
    // when the app is not linked to a Firebase project.
    fun isSignedInToFirebase(): Boolean =
        runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)

    // Voice-command deep links (e.g. "Hey Andro, open settings") navigate
    // straight to the requested screen once the graph is up. Consumed once so
    // recomposition never re-navigates.
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            onPendingRouteConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    if (isSignedInToFirebase()) {
                        // Signed in: open the setup screen once if the
                        // permission/access flow was never completed.
                        scope.launch {
                            val setupDone = preferencesDataStore.setupCompleted.first()
                            navigateClearing(pendingRoute ?: if (setupDone) Routes.HOME else Routes.SETUP)
                        }
                    } else {
                        // Wait for the onboarding flag (never route on a guess).
                        scope.launch {
                            val done = onboardingCompleted
                                ?: preferencesDataStore.onboardingCompleted.first()
                            navigateClearing(pendingRoute ?: if (done) Routes.AUTH else Routes.ONBOARDING)
                        }
                    }
                }
            )
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navigateClearing(pendingRoute ?: if (isSignedInToFirebase()) Routes.HOME else Routes.AUTH)
                }
            )
        }

        composable(Routes.AUTH) {
            FirebaseAuthScreen(
                onAuthSuccess = { isNewUser ->
                    // Everyone lands on the setup screen after sign-in; it
                    // skips itself (LaunchedEffect) when already completed.
                    navigateClearing(pendingRoute ?: if (isNewUser) Routes.PROFILE_SETUP else Routes.SETUP)
                }
            )
        }

        composable(Routes.PROFILE_SETUP) {
            ProfileSetupScreen(
                onDone = { navigateClearing(pendingRoute ?: Routes.SETUP) }
            )
        }

        composable(Routes.SETUP) {
            PermissionSetupScreen(
                onFinished = { navigateClearing(pendingRoute ?: Routes.HOME) }
            )
        }

        // Settings → Permissions & Access (manage every gate later)
        composable(Routes.PERMISSIONS) {
            PermissionsAccessScreen(
                onBack = { navController.popBackStack() }
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

        composable(
            route = Routes.CHAT_WITH_PROMPT,
            arguments = listOf(navArgument(Routes.ARG_PROMPT) { type = NavType.StringType })
        ) { backStackEntry ->
            val prompt = backStackEntry.arguments?.getString(Routes.ARG_PROMPT).orEmpty()
            ChatScreen(
                navController = navController,
                initialPrompt = prompt,
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
            navController.navigateUp()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(navController = navController)
        }

        composable(Routes.PROFILE) {
            ProfileScreen(navController = navController)
        }

        composable(Routes.PROMPTS) {
            PromptLibraryScreen(navController = navController)
        }

        composable(Routes.DEVELOPER) {
            DeveloperScreen(navController = navController)
        }

        composable(Routes.TOOL_DEBUG) {
            ToolDebugScreen(navController = navController)
        }

        composable(Routes.CLOUD_PROVIDERS) {
            io.androllm.feature.cloud.CloudProvidersScreen(navController = navController)
        }

        composable(Routes.CLOUD_USAGE) {
            io.androllm.feature.cloud.CloudUsageDashboardScreen(navController = navController)
        }

        composable(
            route = Routes.CLOUD_MODELS,
            arguments = listOf(navArgument(Routes.ARG_PROVIDER_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            io.androllm.feature.cloud.CloudModelsScreen(navController = navController)
        }

        // ── AI Agent Coding Chat (dedicated cloud-only coding mode) ──────────
        composable(Routes.CODING) {
            io.androllm.feature.coding.ui.CodingWorkspaceSelectorScreen(
                onBack = { navController.navigateUp() },
                onWorkspaceSelected = {
                    navController.navigate(Routes.CODING_CHAT) { launchSingleTop = true }
                }
            )
        }

        composable(Routes.CODING_CHAT) {
            io.androllm.feature.coding.ui.CodingChatScreen(
                onBack = { navController.navigateUp() },
                onChangeWorkspace = {
                    navController.navigate(Routes.CODING) { launchSingleTop = true }
                }
            )
        }

    }
}
