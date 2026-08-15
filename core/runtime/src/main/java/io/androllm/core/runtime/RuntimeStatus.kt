package io.androllm.core.runtime

/**
 * Availability snapshot of one runtime. Always built defensively: a runtime
 * that throws while reporting is caught by the [RuntimeRegistry] and turned
 * into a failed [RuntimeStatus] — never propagated to the other runtimes.
 */
data class RuntimeStatus(
    val available: Boolean,
    val summary: String,
    val detail: String? = null
)
