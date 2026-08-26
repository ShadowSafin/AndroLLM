package io.androllm.core.cloud.pipeline

import timber.log.Timber

/**
 * Structured debug logging for the cloud pipeline.
 *
 * Every interesting pipeline decision (cache hits/misses, planning steps,
 * provider selection, request start/finish, tool execution, retries,
 * invalidations) gets one line here. Logs carry metadata only — never
 * message content, never API keys — so they are safe for development
 * without leaking user data. Normal users never see these; they go through
 * Timber's debug/info levels which release builds strip.
 */
object CloudPipelineLogger {

    const val TAG = "CloudPipeline"

    fun plan(message: String) = Timber.tag(TAG).d("PLAN  %s", message)

    fun cache(message: String) = Timber.tag(TAG).d("CACHE %s", message)

    fun provider(message: String) = Timber.tag(TAG).i("PROV  %s", message)

    fun request(message: String) = Timber.tag(TAG).i("REQ   %s", message)

    fun tools(message: String) = Timber.tag(TAG).i("TOOL  %s", message)

    fun usage(message: String) = Timber.tag(TAG).d("USAGE %s", message)

    fun retry(message: String) = Timber.tag(TAG).w("RETRY %s", message)

    fun failure(message: String, error: Throwable? = null) =
        if (error != null) Timber.tag(TAG).w(error, "FAIL  %s", message)
        else Timber.tag(TAG).w("FAIL  %s", message)
}
