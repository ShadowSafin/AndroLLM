package io.androllm.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.androllm.engine.api.EngineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Application class for AndroLLM.
 *
 * Registers ComponentCallbacks2 to keep the loaded model resident in memory
 * during background operation, only releasing under critical memory pressure.
 */
@HiltAndroidApp
class AndroLLMApplication : Application() {

    @Inject
    lateinit var engineRepository: EngineRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                        Timber.w("[Engine] Memory pressure: RUNNING_LOW — keeping model loaded")
                    }
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                        Timber.w("[Engine] Memory pressure: RUNNING_CRITICAL — unloading model")
                        appScope.launch { engineRepository.unloadModel() }
                    }
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                        Timber.w("[Engine] Memory pressure: COMPLETE — unloading model")
                        appScope.launch { engineRepository.unloadModel() }
                    }
                    else -> {
                        Timber.d("[Engine] Memory trim level: $level — keeping model loaded")
                    }
                }
            }

            @Deprecated("Deprecated in API level 1")
            override fun onLowMemory() {
                Timber.w("[Engine] onLowMemory — unloading model")
                appScope.launch { engineRepository.unloadModel() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) { /* no-op */ }
        })
    }
}
