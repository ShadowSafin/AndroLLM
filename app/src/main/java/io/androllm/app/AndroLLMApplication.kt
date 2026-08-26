package io.androllm.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import dagger.hilt.android.HiltAndroidApp
import io.androllm.core.cloud.ProviderHealthMonitor
import io.androllm.core.memory.background.MemoryBackgroundScheduler
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
 * Also wires WorkManager to Hilt (so memory background jobs get their
 * dependencies) and schedules the opportunistic memory maintenance loop.
 */
@HiltAndroidApp
class AndroLLMApplication : Application(), WorkConfiguration.Provider {

    @Inject
    lateinit var engineRepository: EngineRepository

    @Inject
    lateinit var providerHealthMonitor: ProviderHealthMonitor

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var memoryBackgroundScheduler: MemoryBackgroundScheduler

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().setWorkerFactory(workerFactory).build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        io.androllm.engine.diagnostics.StartupProfiler.markAppStart()

        // NPU (Qualcomm QNN) pre-init: the Hexagon DSP loads libQnnHtpV81Skel.so
        // via FastRPC from ADSP_LIBRARY_PATH, and libQnnHtp.so reads that env
        // var ONCE when it is dlopened. Seeding here (before any LiteRT lib
        // load) is mandatory — setting it later in engine init is too late and
        // the NPU init silently fails. The device's own skel dirs are included
        // so the DSP can fall back to the vendor copies; every entry is
        // best-effort and harmless when absent.
        seedNpuLibraryPaths()

        // Background health probing — LAZY: start only after first chat/cloud use to avoid network on cold start
        // providerHealthMonitor.start() deferred to first CloudGateway access (see ProviderHealthMonitor lazy init)

        // Memory housekeeping — LAZY: WorkManager init is deferred; schedule only after first memory write or on next foreground
        // memoryBackgroundScheduler.schedule() deferred to MemoryRepository first write
        // Keep NPU env seeding on critical path (must be before first dlopen) — already done above

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

    /**
     * Sets the DSP/vendor library search paths the Qualcomm QNN backend needs
     * to reach the Hexagon skeleton. Must run before the first dlopen of any
     * LiteRT/QNN library, hence in [onCreate].
     */
    private fun seedNpuLibraryPaths() {
        runCatching {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val paths = listOf(
                nativeLibDir,
                // This device (SM8845 / OnePlus) keeps its V81 skel here.
                "/odm/lib64/aiframe/cdsp/unsigned",
                "/odm/lib64/aiframe/cdsp/signed",
                "/odm/lib64/aiframe",
                "/odm/lib64",
                "/vendor/dsp/cdsp",
                "/vendor/lib64",
                "/vendor/lib64/snap",
                "/system/lib64",
                "/system/vendor/lib64"
            ).filter { it.isNotBlank() }
            val joined = paths.joinToString(":")
            android.system.Os.setenv("ADSP_LIBRARY_PATH", joined, true)
            android.system.Os.setenv("LD_LIBRARY_PATH", joined, true)
            Timber.d("[NPU] ADSP_LIBRARY_PATH=$joined")
        }.onFailure { e ->
            Timber.w("[NPU] Could not seed ADSP_LIBRARY_PATH: ${e.message}")
        }
    }
}
