package io.androllm.feature.prompts

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.common.BaseViewModel
import io.androllm.core.datastore.PreferencesDataStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ViewModel for the prompt library: search, category filters, and persistent
 * favorites (stored in DataStore). Now extended to support the guided
 * Prompt Studio wizard (4 steps, dynamic fields, preview, history, quality helpers).
 */
@HiltViewModel
class PromptLibraryViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore
) : BaseViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow(PromptCategory.ALL)

    val uiState: StateFlow<PromptLibraryUiState> = combine(
        _searchQuery,
        _selectedCategory,
        preferencesDataStore.favoritePromptIds
    ) { query, category, favorites ->
        val filtered = PromptLibrary.prompts.filter { template ->
            val matchesCategory = category == PromptCategory.ALL || template.category == category
            val q = query.trim()
            val matchesQuery = q.isEmpty() ||
                template.title.contains(q, ignoreCase = true) ||
                template.description.contains(q, ignoreCase = true) ||
                template.text.contains(q, ignoreCase = true)
            matchesCategory && matchesQuery
        }
        PromptLibraryUiState(
            query = query,
            selectedCategory = category,
            prompts = filtered,
            favorites = favorites,
            categories = PromptCategory.entries
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PromptLibraryUiState()
    )

    fun updateQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectCategory(category: PromptCategory) {
        _selectedCategory.value = category
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val current = uiState.value.favorites
            preferencesDataStore.setPromptFavorite(id, id !in current)
        }
    }

    // ── Prompt Studio Wizard ──────────────────────────────────────────────────

    private val _selectedStudioTemplate = MutableStateFlow<StudioTemplate?>(null)
    val selectedStudioTemplate: StateFlow<StudioTemplate?> = _selectedStudioTemplate

    private val _wizardStep = MutableStateFlow(WizardStep.TEMPLATE)
    val wizardStep: StateFlow<WizardStep> = _wizardStep

    private val _studioCategory = MutableStateFlow<StudioCategory?>(null)
    val studioCategory: StateFlow<StudioCategory?> = _studioCategory

    private val _formValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val formValues: StateFlow<Map<String, String>> = _formValues

    private val _previewMode = MutableStateFlow(PreviewMode.PREVIEW)
    val previewMode: StateFlow<PreviewMode> = _previewMode

    private val _rawPromptOverride = MutableStateFlow<String?>(null)
    val rawPromptOverride: StateFlow<String?> = _rawPromptOverride

    private val _validationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val validationErrors: StateFlow<Map<String, String>> = _validationErrors

    private val _studioHistory = MutableStateFlow<List<PromptSession>>(emptyList())
    val studioHistory: StateFlow<List<PromptSession>> = _studioHistory

    private val _studioSettings = MutableStateFlow(PromptStudioSettings())
    val studioSettings: StateFlow<PromptStudioSettings> = _studioSettings

    // Studio UI state combining wizard pieces
    private val _studioUiState = MutableStateFlow(StudioUiState())
    val studioUiState: StateFlow<StudioUiState> = _studioUiState

    init {
        // Observe studio settings
        viewModelScope.launch {
            preferencesDataStore.studioDefaultTemplate.collect { id ->
                _studioSettings.value = _studioSettings.value.copy(defaultTemplateId = id)
                _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.studioAutoPreview.collect { v ->
                _studioSettings.value = _studioSettings.value.copy(autoPreviewOn = v)
                _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.studioShowAdvanced.collect { v ->
                _studioSettings.value = _studioSettings.value.copy(showAdvancedFields = v)
                _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.studioSaveHistory.collect { v ->
                _studioSettings.value = _studioSettings.value.copy(savePromptHistory = v)
                _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.studioEnableRefinement.collect { v ->
                _studioSettings.value = _studioSettings.value.copy(enableRefinementSuggestions = v)
                _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.studioHistoryJson.collect { json ->
                val list = runCatching {
                    Json.decodeFromString<List<PromptSession>>(json)
                }.getOrDefault(emptyList())
                _studioHistory.value = list
                _studioUiState.value = _studioUiState.value.copy(history = list)
            }
        }
        // Observe wizard pieces to keep studioUiState in sync
        viewModelScope.launch {
            combine(
                _selectedStudioTemplate,
                _wizardStep,
                _formValues,
                _previewMode,
                _validationErrors,
                _rawPromptOverride
            ) { args ->
                val t = args[0] as StudioTemplate?
                val s = args[1] as WizardStep
                val v = args[2] as Map<String, String>
                val m = args[3] as PreviewMode
                val e = args[4] as Map<String, String>
                val r = args[5] as String?
                val preview = if (t != null) generatePreviewInternal(t, v, r) else ""
                _studioUiState.value = _studioUiState.value.copy(
                    selectedTemplate = t,
                    wizardStep = s,
                    formValues = v,
                    previewMode = m,
                    validationErrors = e,
                    previewText = preview,
                    rawPrompt = r ?: preview
                )
            }.collect {}
        }
        // Auto-select default template if set
        viewModelScope.launch {
            val defaultId = runCatching { preferencesDataStore.studioDefaultTemplate.first() }.getOrNull()
            if (defaultId != null) {
                PromptStudioLibrary.byId(defaultId)?.let { _selectedStudioTemplate.value = it }
            }
        }
    }

    // ── Studio actions ────────────────────────────────────────────────────────

    fun selectStudioCategory(category: StudioCategory?) {
        _studioCategory.value = category
    }

    fun selectStudioTemplate(template: StudioTemplate) {
        _selectedStudioTemplate.value = template
        _formValues.value = emptyMap()
        _validationErrors.value = emptyMap()
        _rawPromptOverride.value = null
        _wizardStep.value = WizardStep.FORM
    }

    fun switchTemplate(template: StudioTemplate) {
        selectStudioTemplate(template)
    }

    fun updateField(fieldId: String, value: String) {
        _formValues.value = _formValues.value.toMutableMap().apply { put(fieldId, value) }
        // Clear validation for this field
        if (_validationErrors.value.containsKey(fieldId)) {
            _validationErrors.value = _validationErrors.value - fieldId
        }
        // Auto-preview is handled via studioUiState derivation; no extra action needed
    }

    fun setPreviewMode(mode: PreviewMode) {
        _previewMode.value = mode
    }

    fun setWizardStep(step: WizardStep) {
        _wizardStep.value = step
    }

    fun nextStep() {
        val next = when (_wizardStep.value) {
            WizardStep.TEMPLATE -> WizardStep.FORM
            WizardStep.FORM -> {
                if (validateForm()) WizardStep.PREVIEW else WizardStep.FORM
            }
            WizardStep.PREVIEW -> WizardStep.SEND
            WizardStep.SEND -> WizardStep.SEND
        }
        _wizardStep.value = next
    }

    fun previousStep() {
        val prev = when (_wizardStep.value) {
            WizardStep.TEMPLATE -> WizardStep.TEMPLATE
            WizardStep.FORM -> WizardStep.TEMPLATE
            WizardStep.PREVIEW -> WizardStep.FORM
            WizardStep.SEND -> WizardStep.PREVIEW
        }
        _wizardStep.value = prev
    }

    fun resetForm() {
        _formValues.value = emptyMap()
        _validationErrors.value = emptyMap()
        _rawPromptOverride.value = null
    }

    fun resetWizard() {
        _selectedStudioTemplate.value = null
        _formValues.value = emptyMap()
        _validationErrors.value = emptyMap()
        _rawPromptOverride.value = null
        _wizardStep.value = WizardStep.TEMPLATE
        _previewMode.value = PreviewMode.PREVIEW
    }

    fun validateForm(): Boolean {
        val template = _selectedStudioTemplate.value ?: return false
        val errors = mutableMapOf<String, String>()
        for (field in template.fields) {
            if (field.required && _formValues.value[field.id].isNullOrBlank()) {
                errors[field.id] = "${field.label} is required"
            }
        }
        _validationErrors.value = errors
        return errors.isEmpty()
    }

    fun generatePreview(): String {
        val template = _selectedStudioTemplate.value ?: return ""
        return generatePreviewInternal(template, _formValues.value, _rawPromptOverride.value)
    }

    private fun generatePreviewInternal(template: StudioTemplate, values: Map<String, String>, rawOverride: String?): String {
        if (rawOverride != null) return rawOverride
        var prompt = template.promptTemplate
        // Handle conditional blocks {{#var}}...{{/var}}
        for ((key, value) in values) {
            val hasValue = value.isNotBlank()
            val conditionalRegex = Regex("""\{\{#$key}}(.*?)\{\{/$key}}""", setOf(RegexOption.DOT_MATCHES_ALL))
            prompt = conditionalRegex.replace(prompt) { match ->
                if (hasValue) {
                    // Replace inner variables
                    var inner = match.groupValues[1]
                    inner = inner.replace("{{$key}}", value)
                    // Also replace other variables inside
                    for ((k2, v2) in values) {
                        inner = inner.replace("{{$k2}}", v2)
                    }
                    inner
                } else ""
            }
        }
        // Remove any remaining empty conditional blocks (for unset optional fields)
        prompt = Regex("""\{\{#\w+}}.*?\{\{/\w+}}""", setOf(RegexOption.DOT_MATCHES_ALL)).replace(prompt, "")
        // Replace simple variables {{var}}
        for ((key, value) in values) {
            prompt = prompt.replace("{{$key}}", value)
        }
        // Clean any leftover placeholders like {{language}} -> ""
        prompt = Regex("""\{\{\w+}}""").replace(prompt, "")
        // Trim and normalize whitespace
        prompt = prompt.trim().replace(Regex("\n{3,}"), "\n\n")
        // Model-aware tuning
        prompt = tuneForModel(prompt, values["audience"] ?: values["model_target"] ?: "")
        return prompt.trim()
    }

    private fun tuneForModel(prompt: String, modelTarget: String): String {
        val lower = modelTarget.lowercase()
        val isSmall = lower.contains("small") || lower.contains("local small")
        val isLocal = lower.contains("local")
        var tuned = prompt
        if (isSmall || isLocal) {
            // Keep prompts shorter for small local models: limit to ~1200 chars, reduce verbosity
            if (tuned.length > 1200) {
                // Truncate constraints and safety to keep core task
                tuned = tuned.replace(Regex("Requirements:\n(- .*\n)+"), "Requirements:\n- Preserve functionality\n- Be concise\n")
            }
            // Remove overly verbose sections for small models
            tuned = tuned.replace("Please provide extensive detail, examples, and nuance.", "Be concise.")
        }
        return tuned
    }

    fun applyQualityHelper(helper: QualityHelper) {
        val current = generatePreview()
        val transformed = helper.transform(current)
        _rawPromptOverride.value = transformed
        // Also update preview to reflect transformed
        _previewMode.value = PreviewMode.PREVIEW
    }

    fun updateRawPrompt(text: String) {
        _rawPromptOverride.value = text
    }

    fun copyPrompt(): String = generatePreview()

    fun getFinalPrompt(): String = generatePreview()

    fun sendPrompt(): String {
        val final = getFinalPrompt()
        if (_studioSettings.value.savePromptHistory) {
            saveToHistory(final)
        }
        return final
    }

    private fun saveToHistory(finalPrompt: String) {
        viewModelScope.launch {
            val template = _selectedStudioTemplate.value ?: return@launch
            val session = PromptSession(
                id = System.currentTimeMillis().toString(),
                templateId = template.id,
                templateTitle = template.title,
                finalPrompt = finalPrompt
            )
            val current = _studioHistory.value.toMutableList()
            current.add(0, session)
            // Keep last 50
            val trimmed = current.take(50)
            _studioHistory.value = trimmed
            _studioUiState.value = _studioUiState.value.copy(history = trimmed)
            try {
                val json = Json.encodeToString(trimmed)
                preferencesDataStore.setStudioHistoryJson(json)
            } catch (_: Exception) {}
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _studioHistory.value = emptyList()
            _studioUiState.value = _studioUiState.value.copy(history = emptyList())
            preferencesDataStore.clearStudioHistory()
        }
    }

    fun duplicateSession(session: PromptSession) {
        // Load session's template and prompt
        val template = PromptStudioLibrary.byId(session.templateId) ?: return
        _selectedStudioTemplate.value = template
        _rawPromptOverride.value = session.finalPrompt
        _wizardStep.value = WizardStep.PREVIEW
        _previewMode.value = PreviewMode.PREVIEW
    }

    fun setDefaultTemplate(id: String?) {
        viewModelScope.launch {
            preferencesDataStore.setStudioDefaultTemplate(id)
            _studioSettings.value = _studioSettings.value.copy(defaultTemplateId = id)
            _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
        }
    }

    fun setAutoPreview(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setStudioAutoPreview(enabled)
            _studioSettings.value = _studioSettings.value.copy(autoPreviewOn = enabled)
            _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
        }
    }

    fun setShowAdvanced(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setStudioShowAdvanced(enabled)
            _studioSettings.value = _studioSettings.value.copy(showAdvancedFields = enabled)
            _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
        }
    }

    fun setSaveHistory(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setStudioSaveHistory(enabled)
            _studioSettings.value = _studioSettings.value.copy(savePromptHistory = enabled)
            _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
        }
    }

    fun setEnableRefinement(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setStudioEnableRefinement(enabled)
            _studioSettings.value = _studioSettings.value.copy(enableRefinementSuggestions = enabled)
            _studioUiState.value = _studioUiState.value.copy(settings = _studioSettings.value)
        }
    }

    fun toggleFavoriteStudio(id: String) {
        viewModelScope.launch {
            val current = preferencesDataStore.favoritePromptIds.first()
            preferencesDataStore.setPromptFavorite(id, id !in current)
        }
    }
}

/**
 * UI state for the prompt library.
 */
data class PromptLibraryUiState(
    val query: String = "",
    val selectedCategory: PromptCategory = PromptCategory.ALL,
    val prompts: List<PromptTemplate> = PromptLibrary.prompts,
    val favorites: Set<String> = emptySet(),
    val categories: List<PromptCategory> = PromptCategory.entries
) {
    val favoriteCount: Int get() = favorites.size
}

/**
 * UI state for the Studio wizard.
 */
data class StudioUiState(
    val selectedTemplate: StudioTemplate? = null,
    val wizardStep: WizardStep = WizardStep.TEMPLATE,
    val studioCategory: StudioCategory? = null,
    val formValues: Map<String, String> = emptyMap(),
    val previewMode: PreviewMode = PreviewMode.PREVIEW,
    val validationErrors: Map<String, String> = emptyMap(),
    val previewText: String = "",
    val rawPrompt: String = "",
    val history: List<PromptSession> = emptyList(),
    val settings: PromptStudioSettings = PromptStudioSettings(),
    val isFavorite: Boolean = false
)
