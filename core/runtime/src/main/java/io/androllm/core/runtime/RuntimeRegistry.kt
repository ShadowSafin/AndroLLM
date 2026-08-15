package io.androllm.core.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central registry of every app runtime — the single place that knows which
 * runtimes exist and whether they are usable.
 *
 * Discovery is automatic: each contributing module adds a
 * `@Binds @IntoSet Runtime` adapter (Hilt multibinding) and the app component
 * aggregates them here. Nothing is hard-coded; shipping a new runtime is one
 * binding away.
 *
 * Independence contract: [statuses] evaluates every runtime inside its own
 * `runCatching`, so a throwing runtime is reported as *failed* instead of
 * breaking the other entries — "image runtime crashes → chat keeps working".
 * This registry never starts, stops or configures a runtime.
 */
@Singleton
class RuntimeRegistry @Inject constructor(
    private val runtimes: Set<@JvmSuppressWildcards Runtime>
) {

    /** All registered runtimes, stable order by id. */
    val all: List<Runtime> get() = runtimes.sortedBy { it.id }

    val size: Int get() = runtimes.size

    fun byId(id: String): Runtime? = all.firstOrNull { it.id == id }

    fun byCategory(category: RuntimeCategory): List<Runtime> =
        all.filter { it.category == category }

    /**
     * Evaluates every runtime's availability, each isolated from the others.
     * A runtime whose [Runtime.status] throws is reported as failed with the
     * exception text — it never aborts the sweep.
     */
    suspend fun statuses(): List<Pair<Runtime, RuntimeStatus>> = withContext(Dispatchers.Default) {
        all.map { runtime ->
            runtime to try {
                runtime.status()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallow cancellation — a cancelled sweep must cancel.
                throw e
            } catch (e: Exception) {
                RuntimeStatus(
                    available = false,
                    summary = "Status check failed",
                    detail = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }
}
