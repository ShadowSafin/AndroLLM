package io.androllm.feature.coding.tools

import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolFunction
import io.androllm.feature.coding.tools.impl.EditFileTool
import io.androllm.feature.coding.tools.impl.FileTreeTool
import io.androllm.feature.coding.tools.impl.GitStatusTool
import io.androllm.feature.coding.tools.impl.GrepTool
import io.androllm.feature.coding.tools.impl.ListBackgroundServicesTool
import io.androllm.feature.coding.tools.impl.ListDirTool
import io.androllm.feature.coding.tools.impl.ReadFileTool
import io.androllm.feature.coding.tools.impl.ReplaceTextTool
import io.androllm.feature.coding.tools.impl.RunCommandTool
import io.androllm.feature.coding.tools.impl.StopBackgroundServiceTool
import io.androllm.feature.coding.tools.impl.UpdatePlanTool
import io.androllm.feature.coding.tools.impl.WorkspaceSummaryTool
import io.androllm.feature.coding.tools.impl.WriteFileTool
import kotlinx.serialization.json.JsonElement

/**
 * The workspace-scoped set of coding tools. This is deliberately separate from
 * the global device-tool registry (`core/tools`) so the coding agent stays a
 * self-contained feature; the cloud model is advertised exactly these functions.
 */
class CodingToolRegistry(
    tools: List<CodingTool> = defaultTools()
) {
    private val byName = tools.associateBy { it.spec.name }

    fun tools(): List<CodingTool> = byName.values.toList()

    fun find(name: String): CodingTool? = byName[name]

    fun names(): Set<String> = byName.keys

    /**
     * Converts the coding tools into the OpenAI/LiteLLM `tools` array consumed by
     * [io.androllm.core.cloud.CloudGateway]. Only name/description/parameters are
     * exposed — the model never sees implementations.
     */
    fun toCloudTools(): List<CloudTool> = byName.values.map { tool ->
        CloudTool(
            type = "function",
            function = CloudToolFunction(
                name = tool.spec.name,
                description = tool.spec.description,
                parameters = tool.spec.parameters.toMap()
            )
        )
    }

    companion object {
        fun defaultTools(): List<CodingTool> = listOf(
            ReadFileTool(),
            WriteFileTool(),
            EditFileTool(),
            ReplaceTextTool(),
            GrepTool(),
            ListDirTool(),
            FileTreeTool(),
            RunCommandTool(),
            GitStatusTool(),
            WorkspaceSummaryTool(),
            UpdatePlanTool(),
            ListBackgroundServicesTool(),
            StopBackgroundServiceTool()
        )
    }
}
