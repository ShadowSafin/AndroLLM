package io.androllm.feature.coding.tools.impl

import io.androllm.feature.coding.tools.CodingTool
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolResult
import io.androllm.feature.coding.tools.CodingToolSpec
import io.androllm.feature.coding.tools.Schemas
import io.androllm.feature.coding.tools.int
import io.androllm.feature.coding.tools.str
import kotlinx.serialization.json.JsonObject

/**
 * Lists the background services (dev servers, watchers) currently running for
 * the workspace, with their status, port and access URLs. Pass an `id` to also
 * get the tail of that service's captured output.
 */
class ListBackgroundServicesTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "list_background_services",
        description = "List background services (dev servers, watchers) started with run_command background=true: " +
            "id, command, status, port and access URLs. Pass an id to also fetch the tail of its output.",
        parameters = Schemas.obj(
            mapOf(
                "id" to Schemas.string("Optional service id — when set, the tail of that service's output is included."),
                "tail_chars" to Schemas.integer("How many characters of output to include with 'id' (default 4000).")
            ),
            required = emptyList()
        ),
        readOnly = true
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val services = context.services
            ?: return CodingToolResult.Failure("Background services are not available in this environment.", retryable = false)

        val all = services.list()
        if (all.isEmpty()) {
            return CodingToolResult.Success("No background services are running.")
        }

        val sb = StringBuilder()
        for (svc in all) {
            sb.append("• ").append(svc.id)
                .append("  [").append(svc.statusLabel).append("]")
                .append("  ").append(svc.command).append('\n')
            if (svc.port != null) {
                sb.append("    port: ").append(svc.port).append('\n')
                sb.append("    on device: ").append(svc.urlOnDevice).append('\n')
                if (svc.urlNetwork != null) sb.append("    on network: ").append(svc.urlNetwork).append('\n')
            }
        }

        val id = arguments.str("id")
        if (!id.isNullOrBlank()) {
            val tailChars = arguments.int("tail_chars", 4000)
            val tail = services.logTail(id, tailChars)
            if (tail != null) {
                sb.append("\nOutput of ").append(id).append(":\n").append(tail)
            } else {
                sb.append("\nNo service with id '").append(id).append("' — see the list above.")
            }
        }
        return CodingToolResult.Success(sb.toString())
    }
}

/** Stops a background service by id. */
class StopBackgroundServiceTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "stop_background_service",
        description = "Stop a background service (dev server, watcher) by its id. Use list_background_services to find ids.",
        parameters = Schemas.obj(
            mapOf("id" to Schemas.string("The service id to stop, e.g. 'svc-a1b2c3'.")),
            required = listOf("id")
        ),
        readOnly = false
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val services = context.services
            ?: return CodingToolResult.Failure("Background services are not available in this environment.", retryable = false)
        val id = arguments.str("id")
            ?: return CodingToolResult.Failure("Missing 'id'. Use list_background_services to see running services.", retryable = false)
        return CodingToolResult.Success(services.stop(id))
    }
}
