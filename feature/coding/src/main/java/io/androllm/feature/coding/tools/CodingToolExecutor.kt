package io.androllm.feature.coding.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Executes coding tool calls requested by the cloud model. Wraps each call with:
 *  - unknown-tool rejection,
 *  - argument parsing (tolerant of malformed JSON),
 *  - a confirmation gate for tools flagged [CodingToolSpec.requiresConfirmation],
 *  - exception isolation (a tool can never crash the agent loop).
 *
 * The result's [CodingToolResult.summary] is fed back to the model verbatim, so
 * raw command output and file contents reach it unmodified.
 */
class CodingToolExecutor(
    private val registry: CodingToolRegistry,
    private val confirmationGate: ConfirmationGateAdapter = ApproveAll
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Adapter so the tool layer does not depend on the environment package's gate type. */
    fun interface ConfirmationGateAdapter {
        suspend fun confirm(toolName: String, summary: String): Boolean
    }

    object ApproveAll : ConfirmationGateAdapter {
        override suspend fun confirm(toolName: String, summary: String): Boolean = true
    }

    object DeclineAll : ConfirmationGateAdapter {
        override suspend fun confirm(toolName: String, summary: String): Boolean = false
    }

    /**
     * Runs the tool [name] with [argumentsJson] in the given [context]. Never
     * throws; every outcome is a [CodingToolResult].
     */
    suspend fun execute(
        name: String,
        argumentsJson: String,
        context: CodingToolContext
    ): CodingToolResult {
        val tool = registry.find(name)
            ?: return CodingToolResult.Failure(
                "Unknown tool '$name'. Available: ${registry.names().sorted().joinToString()}.",
                retryable = false
            )

        val args: JsonObject = parseArgs(argumentsJson) ?: return CodingToolResult.Failure(
            "Tool '$name' received malformed arguments JSON: $argumentsJson",
            retryable = false
        )

        // Confirmation gate for destructive / externally-visible tools.
        if (tool.spec.requiresConfirmation) {
            val approved = confirmationGate.confirm(name, describeCall(name, args))
            if (!approved) {
                return CodingToolResult.Failure(
                    "User declined to run '$name'. Ask what to do instead or stop.",
                    retryable = false
                )
            }
        }

        context.recordTool(name)
        return try {
            tool.execute(args, context)
        } catch (t: Throwable) {
            CodingToolResult.Failure("Tool '$name' crashed: ${t.message}", retryable = true)
        }
    }

    private fun parseArgs(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
    }

    private fun describeCall(name: String, args: JsonObject): String {
        val target = args["path"]?.toString()?.trim('"')
            ?: args["command"]?.toString()?.trim('"')
            ?: ""
        return "$name${if (target.isNotBlank()) " → $target" else ""}"
    }
}
