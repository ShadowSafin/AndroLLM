package io.androllm.feature.prompts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.navigation.Routes
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger

/**
 * Prompt Studio — guided 4-step wizard (Template → Form → Preview → Send)
 * Replaces the simple text box with a polished, card-based, Material 3 wizard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptLibraryScreen(
    navController: NavController,
    viewModel: PromptLibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val studioState by viewModel.studioUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Studio settings and history
    val studioSettings = studioState.settings
    val history = studioState.history

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = Routes.PROMPTS,
            onTabSelected = { tab -> if (tab.route != Routes.PROMPTS) navController.navigate(tab.route) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Prompt Studio",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.ledger.deskPaper
                                )
                            )
                            Text(
                                text = when (studioState.wizardStep) {
                                    WizardStep.TEMPLATE -> "${PromptStudioLibrary.templates.size} templates • ${uiState.favoriteCount} favorites"
                                    WizardStep.FORM -> studioState.selectedTemplate?.title ?: "Details"
                                    WizardStep.PREVIEW -> "Preview • ${studioState.previewMode.label}"
                                    WizardStep.SEND -> "Ready to send"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.ledger.lampDeep,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp
                                )
                            )
                        }
                    },
                    navigationIcon = {
                        if (studioState.wizardStep != WizardStep.TEMPLATE) {
                            IconButton(onClick = { viewModel.previousStep() }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.ledger.deskPaper)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* Could open history sheet */ }) {
                            Icon(Icons.Filled.History, contentDescription = "History", tint = MaterialTheme.ledger.deskInk)
                        }
                        IconButton(onClick = { viewModel.setWizardStep(WizardStep.TEMPLATE) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Reset", tint = MaterialTheme.ledger.deskInk)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Stepper
                StudioStepper(
                    currentStep = studioState.wizardStep,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                // Preview mode toggle (only on preview step)
                if (studioState.wizardStep == WizardStep.PREVIEW) {
                    PreviewModeTabs(
                        selected = studioState.previewMode,
                        onSelect = { viewModel.setPreviewMode(it) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }

                // Content by step with smooth transitions
                AnimatedContent(
                    targetState = studioState.wizardStep,
                    transitionSpec = {
                        slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it / 4 }) + fadeIn(tween(300)) togetherWith
                            slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { -it / 4 }) + fadeOut(tween(300))
                    },
                    label = "wizardStep"
                ) { step ->
                    when (step) {
                        WizardStep.TEMPLATE -> TemplateGalleryStep(
                            viewModel = viewModel,
                            studioState = studioState,
                            onTemplateSelected = { viewModel.selectStudioTemplate(it) }
                        )
                        WizardStep.FORM -> TemplateFormStep(
                            viewModel = viewModel,
                            studioState = studioState,
                            onNext = { viewModel.nextStep() }
                        )
                        WizardStep.PREVIEW -> PreviewStep(
                            viewModel = viewModel,
                            studioState = studioState,
                            onSend = { finalPrompt ->
                                val promptToSend = viewModel.sendPrompt()
                                navController.navigate(Routes.chatWithPrompt(promptToSend))
                            }
                        )
                        WizardStep.SEND -> PreviewStep(
                            viewModel = viewModel,
                            studioState = studioState,
                            onSend = { finalPrompt ->
                                val promptToSend = viewModel.sendPrompt()
                                navController.navigate(Routes.chatWithPrompt(promptToSend))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioStepper(currentStep: WizardStep, modifier: Modifier = Modifier) {
    val steps = WizardStep.entries
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        steps.forEachIndexed { index, step ->
            val isCompleted = step.ordinal < currentStep.ordinal
            val isCurrent = step == currentStep
            val bg = when {
                isCompleted -> MaterialTheme.ledger.lampDeep
                isCurrent -> MaterialTheme.ledger.lampAmber
                else -> MaterialTheme.ledger.deskHairline
            }
            val fg = if (isCompleted || isCurrent) Color.White else MaterialTheme.ledger.deskInk
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = fg)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.deskInk
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .height(2.dp)
                        .background(if (isCompleted) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskHairline)
                )
            }
        }
    }
}

@Composable
private fun PreviewModeTabs(selected: PreviewMode, onSelect: (PreviewMode) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateGalleryStep(
    viewModel: PromptLibraryViewModel,
    studioState: StudioUiState,
    onTemplateSelected: (StudioTemplate) -> Unit
) {
    val context = LocalContext.current
    val studioCategoryFilter = studioState.studioCategory
    val grouped = PromptStudioLibrary.grouped()
    val categories = StudioCategory.entries

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Category chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = studioCategoryFilter == null,
                        onClick = { viewModel.selectStudioCategory(null) },
                        label = { Text("All") }
                    )
                }
                items(categories.size) { idx ->
                    val cat = categories[idx]
                    FilterChip(
                        selected = studioCategoryFilter == cat,
                        onClick = { viewModel.selectStudioCategory(if (studioCategoryFilter == cat) null else cat) },
                        label = { Text(cat.label) }
                    )
                }
            }
        }

        // Search for studio templates (reuse old search query for simplicity)
        // Show favorites and history counts
        if (studioState.history.isNotEmpty()) {
            item {
                Text(
                    text = "Recent sessions • ${studioState.history.size}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(studioState.history.take(5).size) { idx ->
                        val session = studioState.history[idx]
                        AssistChip(
                            onClick = { viewModel.duplicateSession(session) },
                            label = { Text(session.templateTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Filled.History, null, modifier = Modifier.size(14.dp)) }
                        )
                    }
                }
            }
        }

        // Templates grouped by category
        val filteredCategories = if (studioCategoryFilter != null) listOf(studioCategoryFilter) else categories
        filteredCategories.forEach { category ->
            val templates = grouped[category] ?: emptyList()
            if (templates.isNotEmpty()) {
                item {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
                    )
                }
                items(templates.size) { idx ->
                    val template = templates[idx]
                    StudioTemplateCard(
                        template = template,
                        isFavorite = template.id in viewModel.uiState.collectAsStateWithLifecycle().value.favorites,
                        onToggleFavorite = { viewModel.toggleFavoriteStudio(template.id) },
                        onUse = { onTemplateSelected(template) },
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Prompt", template.promptTemplate))
                            Toast.makeText(context, "Template copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        // Settings for studio
        item {
            StudioSettingsCard(
                settings = studioState.settings,
                onUpdate = { newSettings ->
                    if (newSettings.defaultTemplateId != studioState.settings.defaultTemplateId) viewModel.setDefaultTemplate(newSettings.defaultTemplateId)
                    if (newSettings.autoPreviewOn != studioState.settings.autoPreviewOn) viewModel.setAutoPreview(newSettings.autoPreviewOn)
                    if (newSettings.showAdvancedFields != studioState.settings.showAdvancedFields) viewModel.setShowAdvanced(newSettings.showAdvancedFields)
                    if (newSettings.savePromptHistory != studioState.settings.savePromptHistory) viewModel.setSaveHistory(newSettings.savePromptHistory)
                    if (newSettings.enableRefinementSuggestions != studioState.settings.enableRefinementSuggestions) viewModel.setEnableRefinement(newSettings.enableRefinementSuggestions)
                }
            )
        }
    }
}

@Composable
private fun StudioTemplateCard(
    template: StudioTemplate,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onUse: () -> Unit,
    onCopy: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), onClick = onUse) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = template.icon, contentDescription = null, tint = MaterialTheme.ledger.lampDeep, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = template.description,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInkFaint
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = template.exampleUseCase,
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CloudChip(
                        text = template.category.label,
                        accentColor = when (template.category) {
                            StudioCategory.CODE, StudioCategory.EXPLAIN -> MaterialTheme.ledger.lampDeep
                            StudioCategory.WRITING, StudioCategory.REWRITE -> MaterialTheme.ledger.lampAmber
                            StudioCategory.EMAIL, StudioCategory.SOCIAL_POST -> MaterialTheme.ledger.lampGlow
                            else -> MaterialTheme.ledger.deskInk
                        }
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            template.qualityScore >= 90 -> MaterialTheme.ledger.lampDeep.copy(alpha = 0.15f)
                            template.qualityScore >= 80 -> MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f)
                            else -> MaterialTheme.ledger.deskHairline.copy(alpha = 0.3f)
                        }
                    ) {
                        Text(
                            text = "${template.qualityScore} • ${template.usefulnessTag}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                    }
                    Button(onClick = onUse, modifier = Modifier.height(36.dp)) {
                        Text("Use", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateFormStep(
    viewModel: PromptLibraryViewModel,
    studioState: StudioUiState,
    onNext: () -> Unit
) {
    val template = studioState.selectedTemplate ?: return
    val formValues = studioState.formValues
    val errors = studioState.validationErrors
    val showAdvanced = studioState.settings.showAdvancedFields
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.take(8000) ?: ""
                // Assume code field for file upload
                val codeField = template.fields.find { f -> f.type == PromptFieldType.CODE }?.id ?: "code"
                viewModel.updateField(codeField, text)
                Toast.makeText(context, "File loaded", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = template.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
            )
            Text(
                text = template.description,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
            )
        }
        // Dynamic fields
        items(template.fields.size) { idx ->
            val field = template.fields[idx]
            if (field.isAdvanced && !showAdvanced) return@items
            FieldRenderer(
                field = field,
                value = formValues[field.id] ?: "",
                error = errors[field.id],
                onValueChange = { viewModel.updateField(field.id, it) },
                onFilePick = { filePicker.launch(arrayOf("text/*", "application/*")) }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { viewModel.resetForm() }, modifier = Modifier.weight(1f)) {
                    Text("Reset")
                }
                Button(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text("Preview")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Show validation summary if errors
            if (errors.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Please fix:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        )
                        errors.values.forEach { msg ->
                            Text(
                                text = "• $msg",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onErrorContainer)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldRenderer(
    field: PromptField,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onFilePick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.label + if (field.required) " *" else "",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.ledger.deskPaper),
                modifier = Modifier.weight(1f)
            )
            if (field.isAdvanced) {
                CloudChip(text = "Advanced", accentColor = MaterialTheme.ledger.deskInkFaint)
            }
        }
        if (field.helperText.isNotBlank()) {
            Text(text = field.helperText, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint))
        }
        when (field.type) {
            PromptFieldType.SELECT -> {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(field.placeholder) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        isError = error != null,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.ledger.lampAmber,
                            unfocusedBorderColor = MaterialTheme.ledger.deskHairline
                        )
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        field.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    onValueChange(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            PromptFieldType.CODE -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(field.placeholder) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.ledger.lampAmber,
                        unfocusedBorderColor = MaterialTheme.ledger.deskHairline
                    )
                )
                if (field.id == "code") {
                    TextButton(onClick = onFilePick) {
                        Text("Upload file", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            PromptFieldType.FILE -> {
                OutlinedButton(onClick = onFilePick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Description, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose file")
                }
                if (value.isNotBlank()) {
                    Text(text = "Selected: ${value.take(40)}", style = MaterialTheme.typography.labelSmall)
                }
            }
            else -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(field.placeholder) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (field.type == PromptFieldType.TEXT_AREA) 3 else 1,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.ledger.lampAmber,
                        unfocusedBorderColor = MaterialTheme.ledger.deskHairline
                    )
                )
            }
        }
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.error))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewStep(
    viewModel: PromptLibraryViewModel,
    studioState: StudioUiState,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    val previewText = studioState.previewText
    val rawPrompt = studioState.rawPrompt
    val mode = studioState.previewMode
    val template = studioState.selectedTemplate

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (template != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.lampAmber.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Template: ${template.title}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Text(text = "Category: ${template.category.label}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Quality: ${template.qualityScore} • ${template.usefulnessTag}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Quality helpers
        if (studioState.settings.enableRefinementSuggestions) {
            Text(text = "Refine prompt", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityHelper.entries.forEach { helper ->
                    AssistChip(
                        onClick = { viewModel.applyQualityHelper(helper) },
                        label = { Text(helper.label, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = MaterialTheme.ledger.lampDeep)
                    )
                }
            }
        }

        // Editable preview
        when (mode) {
            PreviewMode.FORM -> {
                // Show form values summary
                studioState.formValues.forEach { (k, v) ->
                    if (v.isNotBlank()) {
                        Text(text = "$k: ${v.take(120)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            PreviewMode.PREVIEW -> {
                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = "Final prompt preview",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        // Editable preview
                        OutlinedTextField(
                            value = previewText,
                            onValueChange = { viewModel.updateRawPrompt(it) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 8,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.ledger.lampAmber,
                                unfocusedBorderColor = MaterialTheme.ledger.deskHairline
                            )
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { viewModel.resetForm() }) { Text("Reset") }
                            OutlinedButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Prompt", previewText))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                            Button(onClick = { viewModel.updateRawPrompt(previewText); viewModel.setPreviewMode(PreviewMode.PREVIEW) }) {
                                Text("Regenerate")
                            }
                        }
                    }
                }
            }
            PreviewMode.RAW -> {
                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(text = "Raw prompt (plain text)", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                        OutlinedTextField(
                            value = rawPrompt,
                            onValueChange = { viewModel.updateRawPrompt(it) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 10,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.ledger.lampAmber,
                                unfocusedBorderColor = MaterialTheme.ledger.deskHairline
                            )
                        )
                    }
                }
            }
        }

        // Validation and send
        val hasErrors = studioState.validationErrors.isNotEmpty()
        if (hasErrors) {
            Text(
                text = "Fix validation errors before sending",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.error)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.resetWizard() }, modifier = Modifier.weight(1f)) {
                Text("Start over")
            }
            Button(
                onClick = {
                    if (viewModel.validateForm()) {
                        onSend(viewModel.getFinalPrompt())
                    } else {
                        Toast.makeText(context, "Please fill required fields", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !hasErrors || viewModel.validateForm()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Send to model")
            }
        }

        // History
        if (studioState.history.isNotEmpty()) {
            Text(text = "Recent sessions", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            studioState.history.take(5).forEach { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.duplicateSession(session) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(text = session.templateTitle, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            text = session.finalPrompt.take(120) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.duplicateSession(session) }) { Text("Reuse", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Prompt", session.finalPrompt))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            TextButton(onClick = { viewModel.clearHistory() }) {
                Text("Clear history", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StudioSettingsCard(
    settings: PromptStudioSettings,
    onUpdate: (PromptStudioSettings) -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Prompt Studio settings", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Auto-preview", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.autoPreviewOn, onCheckedChange = { onUpdate(settings.copy(autoPreviewOn = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Show advanced fields", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.showAdvancedFields, onCheckedChange = { onUpdate(settings.copy(showAdvancedFields = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Save prompt history", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.savePromptHistory, onCheckedChange = { onUpdate(settings.copy(savePromptHistory = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Refinement suggestions", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.enableRefinementSuggestions, onCheckedChange = { onUpdate(settings.copy(enableRefinementSuggestions = it)) })
            }
            // Default template picker
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = settings.defaultTemplateId ?: "None",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Default template") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("None") }, onClick = { onUpdate(settings.copy(defaultTemplateId = null)); expanded = false })
                    PromptStudioLibrary.templates.forEach { t ->
                        DropdownMenuItem(text = { Text(t.title) }, onClick = { onUpdate(settings.copy(defaultTemplateId = t.id)); expanded = false })
                    }
                }
            }
        }
    }
}

private fun navigateToTab(tab: io.androllm.core.ui.components.CloudTab, navController: NavController) {
    if (tab.route != Routes.PROMPTS) {
        navController.navigate(tab.route)
    }
}
