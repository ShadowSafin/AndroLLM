package io.androllm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by preferencesDataStore.theme.collectAsState(initial = ThemeMode.SYSTEM)
            val accentHex by preferencesDataStore.accentColor.collectAsState(initial = null)
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val accentColor = accentHex?.let { hex ->
                runCatching { Color(hex.toLong(16)) }.getOrNull()
            }
            AndroLLMTheme(
                darkTheme = darkTheme,
                accentColor = accentColor
            ) {
                AppNavHost(preferencesDataStore = preferencesDataStore)
            }
        }
    }
}
