package io.androllm.core.cloud.pipeline

import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.usage.CloudErrorKind
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** A tool call accumulated from streaming deltas (or a fallback parse). */
data class ObservedToolCall(
    val index: Int,
    val id: String?,
    val name: String,
    val argumentsJson: String
)

/** Token usage observed on a turn (provider-reported when available). */
data class ObservedUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long
)

/**
 * The outcome of one observed cloud request: accumulated text, tool calls,
 * usage, latency (total + first token), finish reason and a normalized
 * error category. The usage meter turns this directly into a record.
 */
data class CloudTurnResult(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ObservedToolCall> = emptyList(),
    val usage: ObservedUsage? = null,
    val latencyMs: Long = 0,
    val firstTokenMs: Long? = null,
    val finishReason: String? = null,
    val success: Boolean = true,
    val errorKind: CloudErrorKind = CloudErrorKind.NONE,
    val errorMessage: String = "",
    /** True when at least one event reached the observer. */
    val receivedAnyEvent: Boolean = false
)

/**
 * Observes a cloud request's event stream and folds it into a
 * [CloudTurnResult]: accumulates content/reasoning deltas, merges streaming
 * tool-call fragments by index, captures usage and finish reason, and
 * measures total + first-token latency.
 *
 * Every event is also forwarded to [onEvent] (used by the gateway to tee the
 * stream to the caller) BEFORE it is folded, so the observer never delays
 * delivery. Failures are normalized into the result instead of throwing —
 * except [CancellationException], which is always rethrown so structured
 * concurrency keeps working.
 */
class CloudResultObserver(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Collects [events], forwarding each to [onEvent], and returns the folded
     * turn result. Never throws provider errors — they land in the result's
     * [CloudErrorKind]. Rethrows cancellation.
     */
    suspend fun observe(
        events: Flow<CloudStreamEvent>,
        onEvent: suspend (CloudStreamEvent) -> Unit = {}
    ): CloudTurnResult {
        val startedAt = clock()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolFragments = LinkedHashMap<Int, ToolFragment>()
        var usage: ObservedUsage? = null
        var finishReason: String? = null
        var firstTokenAt: Long? = null
        var receivedAny = false

        try {
            events.collect { event ->
                onEvent(event)
                receivedAny = true
                when (event) {
                    is CloudStreamEvent.Delta -> {
                        if (firstTokenAt == null) firstTokenAt = clock()
                        text.append(event.text)
                    }
                    is CloudStreamEvent.Reasoning -> {
                        if (firstTokenAt == null) firstTokenAt = clock()
                        reasoning.append(event.text)
                    }
                    is CloudStreamEvent.ToolCallDelta -> {
                        val fragment = toolFragments.getOrPut(event.index) { ToolFragment(event.index) }
                        event.id?.let { fragment.id = it }
                        event.name?.let { if (it.isNotBlank()) fragment.name = it }
                        fragment.arguments.append(event.arguments)
                    }
                    is CloudStreamEvent.Usage -> usage = ObservedUsage(
                        promptTokens = event.promptTokens,
                        completionTokens = event.completionTokens,
                        totalTokens = event.totalTokens
                    )
                    is CloudStreamEvent.Finish -> finishReason = event.reason
                    CloudStreamEvent.Done -> Unit
                }
            }
            val endedAt = clock()
            return CloudTurnResult(
                text = text.toString(),
                reasoning = reasoning.toString(),
                toolCalls = toolFragments.values.mapNotNull { it.toCall() },
                usage = usage,
                latencyMs = endedAt - startedAt,
                firstTokenMs = firstTokenAt?.let { it - startedAt },
                finishReason = finishReason,
                success = true,
                receivedAnyEvent = receivedAny
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val endedAt = clock()
            val kind = classify(e)
            CloudPipelineLogger.failure(
                "request failed after ${endedAt - startedAt}ms: ${kind.name} (${e.message?.take(120)})",
                e
            )
            return CloudTurnResult(
                text = text.toString(),
                reasoning = reasoning.toString(),
                toolCalls = toolFragments.values.mapNotNull { it.toCall() },
                usage = usage,
                latencyMs = endedAt - startedAt,
                firstTokenMs = firstTokenAt?.let { it - startedAt },
                finishReason = finishReason,
                success = false,
                errorKind = kind,
                errorMessage = e.message.orEmpty().take(300),
                receivedAnyEvent = receivedAny
            )
        }
    }

    /** Maps an exception to the normalized dashboard error category. */
    fun classify(error: Throwable): CloudErrorKind = when (error) {
        is SocketTimeoutException -> CloudErrorKind.TIMEOUT
        is CloudException -> when (error.statusCode) {
            408 -> CloudErrorKind.TIMEOUT
            429 -> CloudErrorKind.RATE_LIMIT
            in 500..599 -> CloudErrorKind.HTTP_ERROR
            null -> CloudErrorKind.TRANSPORT
            else -> CloudErrorKind.HTTP_ERROR
        }
        is IOException -> CloudErrorKind.TRANSPORT
        is kotlinx.serialization.SerializationException -> CloudErrorKind.MALFORMED
        else -> CloudErrorKind.TRANSPORT
    }

    /** True when a failed turn may be retried against a fallback provider. */
    fun isFallbackEligible(result: CloudTurnResult): Boolean =
        !result.success &&
            !result.receivedAnyEvent &&
            result.errorKind in setOf(
                CloudErrorKind.TIMEOUT,
                CloudErrorKind.RATE_LIMIT,
                CloudErrorKind.HTTP_ERROR,
                CloudErrorKind.TRANSPORT
            )

    private class ToolFragment(val index: Int) {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()

        fun toCall(): ObservedToolCall? {
            val toolName = name?.trim()
            if (toolName.isNullOrBlank()) return null
            val args = arguments.toString().trim()
            // Malformed/partial arguments must never reach execution — the
            // caller gets an empty-object call which validation then rejects
            // with a clear message instead of a crash.
            val safeArgs = if (args.startsWith("{") && args.endsWith("}") && isBalanced(args)) args else "{}"
            return ObservedToolCall(index = index, id = id, name = toolName, argumentsJson = safeArgs)
        }

        private fun isBalanced(text: String): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (c in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth < 0) return false
                    }
                }
            }
            return depth == 0 && !inString
        }
    }
}
