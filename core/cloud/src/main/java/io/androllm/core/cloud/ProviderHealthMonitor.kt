package io.androllm.core.cloud

import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.network.LiteLLMClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodically probes every enabled provider via `/health/liveliness` and
 * `/health/readiness` and publishes the latest per-provider [CloudHealth].
 *
 * The cadence comes from [io.androllm.core.cloud.model.ProviderSettings]
 * ([io.androllm.core.cloud.model.ProviderSettings.healthCheckIntervalMinutes]);
 * a value of 0 keeps the monitor idle.
 *
 * Exposes [status] as a [StateFlow] so UI layers (Cloud Providers screen) can
 * render live reachability/latency without polling.
 */
@Singleton
class ProviderHealthMonitor @Inject constructor(
    private val manager: ProviderManager,
    private val client: LiteLLMClient
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _status = MutableStateFlow<Map<String, CloudHealth>>(emptyMap())

    /** Latest health snapshot keyed by provider id. */
    val status: StateFlow<Map<String, CloudHealth>> = _status.asStateFlow()

    /** Starts the periodic loop. Idempotent; safe to call from Application.onCreate. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val settings = manager.current()
                val intervalMinutes = settings.providerSettings.healthCheckIntervalMinutes
                if (intervalMinutes > 0) {
                    checkAll(settings)
                    delay(intervalMinutes * 60_000L)
                } else {
                    delay(60_000L)
                }
            }
        }
    }

    /** Stops the periodic loop (clears nothing; last snapshot stays visible). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Probes all enabled providers once and publishes the results. */
    suspend fun checkAll(): Map<String, CloudHealth> = checkAll(manager.current())

    /** Probes all enabled providers in [settings] once and publishes the results. */
    suspend fun checkAll(settings: CloudSettings): Map<String, CloudHealth> {
        val results = mutableMapOf<String, CloudHealth>()
        for (provider in settings.providers) {
            if (!provider.enabled) continue
            val apiKey = runCatching { manager.getApiKey(provider) }.getOrDefault("")
            val health = runCatching { client.health(provider, apiKey) }
                .getOrElse { CloudHealth(reachable = false, alive = false, ready = false, latencyMs = 0) }
            results[provider.id] = health
        }
        _status.value = results
        return results
    }

    /** Probes a single provider once and updates its entry in [status]. */
    suspend fun check(providerId: String): CloudHealth? {
        val settings = manager.current()
        val provider = settings.providers.find { it.id == providerId } ?: return null
        val apiKey = runCatching { manager.getApiKey(provider) }.getOrDefault("")
        val health = runCatching { client.health(provider, apiKey) }
            .getOrElse { CloudHealth(reachable = false, alive = false, ready = false, latencyMs = 0) }
        _status.value = _status.value + (providerId to health)
        return health
    }
}
