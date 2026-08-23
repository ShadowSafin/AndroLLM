package io.androllm.feature.prompts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/**
 * Studio categories as required by the spec (11).
 */
enum class StudioCategory(val label: String, val icon: ImageVector) {
    CODE("Code", Icons.Filled.Code),
    WRITING("Writing", Icons.Filled.EditNote),
    SUMMARIZE("Summarize", Icons.Filled.Summarize),
    EXPLAIN("Explain", Icons.Filled.MenuBook),
    REWRITE("Rewrite", Icons.Filled.Description),
    RESEARCH("Research", Icons.Filled.Search),
    BRAINSTORM("Brainstorm", Icons.Filled.Lightbulb),
    EMAIL("Email", Icons.Filled.Email),
    SOCIAL_POST("Social post", Icons.Filled.Share),
    PROMPT_ENGINEERING("Prompt engineering", Icons.Filled.AutoAwesome),
    CUSTOM("Custom", Icons.Filled.Build)
}

/**
 * Type of a dynamic field.
 */
enum class PromptFieldType {
    TEXT,           // single line
    TEXT_AREA,      // multi line
    CODE,           // code area with mono font
    SELECT,         // dropdown
    FILE            // file upload
}

/**
 * A single dynamic question/field for a template.
 */
data class PromptField(
    val id: String, // variable name like "code", "language", "goal"
    val label: String,
    val placeholder: String = "",
    val type: PromptFieldType = PromptFieldType.TEXT_AREA,
    val required: Boolean = true,
    val options: List<String> = emptyList(), // for SELECT
    val isAdvanced: Boolean = false, // shown only when showAdvanced = true
    val helperText: String = ""
)

/**
 * Supported prompt variables (requirement 4).
 */
object PromptVariables {
    const val TASK = "{{task}}"
    const val CONTEXT = "{{context}}"
    const val CODE = "{{code}}"
    const val LANGUAGE = "{{language}}"
    const val TONE = "{{tone}}"
    const val AUDIENCE = "{{audience}}"
    const val OUTPUT_FORMAT = "{{output_format}}"
    const val LENGTH = "{{length}}"
    val all = listOf(TASK, CONTEXT, CODE, LANGUAGE, TONE, AUDIENCE, OUTPUT_FORMAT, LENGTH)
}

/**
 * Internal prompt structure for a template (requirement 1 - smart templates).
 */
data class PromptStructure(
    val role: String = "",
    val task: String = "",
    val contextBlock: String = "",
    val constraints: List<String> = emptyList(),
    val outputFormat: String = "",
    val safetyRules: List<String> = emptyList()
)

/**
 * Studio template with full wizard metadata.
 */
data class StudioTemplate(
    val id: String,
    val title: String,
    val description: String,
    val exampleUseCase: String,
    val category: StudioCategory,
    val icon: ImageVector = category.icon,
    val qualityScore: Int = 85, // 0..100
    val usefulnessTag: String = "High quality",
    val fields: List<PromptField>,
    val promptTemplate: String, // with {{variables}}
    val structure: PromptStructure = PromptStructure(),
    val exampleFilledPrompt: String = "", // example of final prompt
    val supportedModels: List<String> = listOf("local", "cloud", "code", "small")
)

/**
 * Wizard step.
 */
enum class WizardStep(val label: String) {
    TEMPLATE("Template"),
    FORM("Details"),
    PREVIEW("Preview"),
    SEND("Send")
}

/**
 * Preview mode toggle (requirement 9).
 */
enum class PreviewMode(val label: String) {
    FORM("Form"),
    PREVIEW("Preview"),
    RAW("Raw")
}

/**
 * Quality helper actions (requirement 7).
 */
enum class QualityHelper(val label: String, val transform: (String) -> String) {
    SHORTER("Make it shorter", { p -> "$p\n\n[Instruction: Keep the answer concise and under 150 words.]" }),
    DETAILED("Make it more detailed", { p -> "$p\n\n[Instruction: Provide extensive detail, examples, and nuance.]" }),
    TECHNICAL("Make it more technical", { p -> "$p\n\n[Instruction: Use precise technical terminology and assume expert audience.]" }),
    BEGINNER("Beginner-friendly", { p -> "$p\n\n[Instruction: Explain in simple terms suitable for beginners, with analogies.]" }),
    CODE_EXAMPLES("Add code examples", { p -> "$p\n\n[Instruction: Include clear, runnable code examples with comments.]" }),
    BULLET_POINTS("Add bullet points", { p -> "$p\n\n[Instruction: Structure the answer with bullet points for clarity.]" }),
    STRICT("Make it strict", { p -> "$p\n\n[Instruction: Be strict, do not hallucinate, only state verified facts.]" }),
    CONCISE("Make it concise", { p -> "$p\n\n[Instruction: Be as concise as possible while retaining key information.]" })
}

/**
 * History entry.
 */
@Serializable
data class PromptSession(
    val id: String,
    val templateId: String,
    val templateTitle: String,
    val finalPrompt: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)

/**
 * Studio settings (requirement for Prompt Studio settings).
 */
data class PromptStudioSettings(
    val defaultTemplateId: String? = null,
    val autoPreviewOn: Boolean = true,
    val showAdvancedFields: Boolean = false,
    val savePromptHistory: Boolean = true,
    val enableRefinementSuggestions: Boolean = true
)

/**
 * Validation result for a field.
 */
data class FieldValidation(
    val fieldId: String,
    val message: String
)
