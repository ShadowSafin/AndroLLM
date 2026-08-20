package io.androllm.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.androllm.app.navigation.AppNavHost
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.models.ThemeMode
import io.androllm.core.ui.theme.AndroLLMTheme
import javax.inject.Inject

/**
 * Main activity hosting the Compose navigation graph.
 *
 * Shows the AndroidX system splash (brand color + logo) while the process
 * starts, then hands over to the cinematic composable splash inside the graph.
 * The persisted [ThemeMode] and profile-setup accent color are applied here so
 * every destination — including the entry flow — renders with the user's choice.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesDataStore: PreferencesDataStore

    /** Route requested by a voice command (e.g. "open settings"). */
    private var pendingRoute: String? = null
    private var pendingRouteSetter: ((String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = intent.getStringExtra(io.androllm.core.navigation.Routes.EXTRA_NAV_ROUTE)
        setContent {
            val routeState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(pendingRoute)
            }
            pendingRouteSetter = { routeState.value = it }
            val themeMode by preferencesDataStore.theme.collectAsState(initial = ThemeMode.SYSTEM)
            val accentHex by preferencesDataStore.accentColor.collectAsState(initial = null)
            val dynamicColor by preferencesDataStore.dynamicColor.collectAsState(initial = true)
            val uiDensity by preferencesDataStore.uiDensity.collectAsState(initial = io.androllm.core.models.UiDensity.DEFAULT)
            val fontSize by preferencesDataStore.fontSize.collectAsState(initial = io.androllm.core.models.ChatFontSize.MEDIUM)
            val accentColor = accentHex?.takeIf { it.isNotBlank() }?.let { hex ->
                runCatching { Color(hex.toLong(16)) }.getOrNull()
            }
            AndroLLMTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                accentColor = accentColor
            ) {
                io.androllm.core.ui.theme.ProvideUiScale(
                    density = uiDensity,
                    fontSize = fontSize
                ) {
                    AppNavHost(
                        preferencesDataStore = preferencesDataStore,
                        pendingRoute = routeState.value,
                        onPendingRouteConsumed = { pendingRouteSetter?.invoke(null) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Voice commands arriving while the app is already open (SINGLE_TOP).
        pendingRoute = intent.getStringExtra(io.androllm.core.navigation.Routes.EXTRA_NAV_ROUTE)
        pendingRouteSetter?.invoke(pendingRoute)
    }
}
