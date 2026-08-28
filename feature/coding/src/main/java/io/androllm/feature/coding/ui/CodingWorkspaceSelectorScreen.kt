package io.androllm.feature.coding.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceSource

/**
 * Mandatory first step of the coding agent: choose (or create / open) the
 * workspace folder the agent will operate on. The coding chat cannot open until
 * a workspace is selected — this screen is the gate.
 *
 * Workspaces are real folders in shared storage:
 *  - "New workspace" creates a folder under `/storage/emulated/0/AndroLLM/workspaces`.
 *  - "Open folder" uses one of the user's own folders DIRECTLY — the agent
 *    reads and writes files right there, nothing is copied into app storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodingWorkspaceSelectorScreen(
    onBack: () -> Unit,
    onWorkspaceSelected: () -> Unit,
    viewModel: CodingChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val busy by viewModel.selectorBusy.collectAsStateWithLifecycle()
    val selectorError by viewModel.selectorError.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var hasStorageAccess by remember { mutableStateOf(hasStorageAccess(context)) }

    LaunchedEffect(Unit) { viewModel.loadWorkspaces() }

    // Settings "All files access" page (Android 11+). Re-check on return.
    val allFilesSettings = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasStorageAccess = hasStorageAccess(context)
    }

    // Legacy runtime permission (Android 9/10).
    val legacyStoragePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasStorageAccess = granted
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                allFilesSettings.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }.onFailure {
                runCatching { allFilesSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // SAF folder picker → the chosen folder is opened DIRECTLY as the workspace.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.openFolderUri(uri.toString(), onWorkspaceSelected)
        }
    }

    CloudAtmosphericBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Column {
                            Text(
                                "AI Agent Coding",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.ledger.deskPaper
                                )
                            )
                            Text(
                                "CHOOSE A WORKSPACE FOLDER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.ledger.lampDeep,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.ledger.deskPaperDim
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "The coding agent reads, edits and runs commands inside the folder you pick. " +
                            "Files are written directly into that folder — nothing is copied away.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }

                if (!hasStorageAccess) {
                    item {
                        StorageAccessCard(onRequest = { requestStorageAccess() })
                    }
                }

                if (selectorError != null) {
                    item {
                        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    selectorError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.ledger.lampAmber
                                    )
                                )
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = { viewModel.dismissSelectorError() }) {
                                    Text("Dismiss", color = MaterialTheme.ledger.deskPaperDim)
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CloudCapsuleButton(
                            text = "New workspace",
                            onClick = {
                                if (hasStorageAccess) showCreateDialog = true else requestStorageAccess()
                            },
                            icon = Icons.Filled.Add,
                            modifier = Modifier.weight(1f)
                        )
                        CloudCapsuleButton(
                            text = "Open folder",
                            onClick = {
                                if (hasStorageAccess) folderPicker.launch(null) else requestStorageAccess()
                            },
                            icon = Icons.Filled.FolderOpen,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = "Your workspaces",
                        subtitle = "${workspaces.size} on this device"
                    )
                }

                if (busy) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.ledger.lampAmber)
                        }
                    }
                }

                if (workspaces.isEmpty() && !busy) {
                    item {
                        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.ledger.deskInkFaint,
                                    modifier = Modifier.height(36.dp).width(36.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "No workspaces yet",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.ledger.deskPaper
                                    )
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Create one or open one of your own project folders to begin.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                                )
                            }
                        }
                    }
                }

                items(workspaces, key = { it.id }) { ws ->
                    WorkspaceRow(
                        workspace = ws,
                        onClick = { viewModel.selectWorkspace(ws, onWorkspaceSelected) },
                        onDelete = { viewModel.deleteWorkspace(ws) }
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        NamePromptDialog(
            title = "New workspace",
            label = "Workspace name",
            confirmText = "Create",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                viewModel.createWorkspace(name, onWorkspaceSelected)
            }
        )
    }
}

/** True when the app may read/write shared storage folders directly. */
private fun hasStorageAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/** Explains why All-files access is needed and offers the Settings shortcut. */
@Composable
private fun StorageAccessCard(onRequest: () -> Unit) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Storage access needed",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Workspaces are real folders on your device. Grant \"All files access\" so the " +
                    "agent can create and open project folders in shared storage and write files " +
                    "directly into them.",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
            )
            Spacer(Modifier.height(10.dp))
            CloudCapsuleButton(
                text = "Allow access",
                onClick = onRequest,
                icon = Icons.Filled.FolderOpen
            )
        }
    }
}

@Composable
private fun WorkspaceRow(
    workspace: CodingWorkspace,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .width(44.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.lampAmber,
                    modifier = Modifier.height(24.dp).width(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    workspace.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    workspace.absolutePath,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.ledger.deskInk,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    maxLines = 1
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CloudChip(
                        text = when (workspace.source) {
                            WorkspaceSource.OPENED -> "device folder"
                            WorkspaceSource.IMPORTED -> "legacy import"
                            else -> "local"
                        },
                        accentColor = MaterialTheme.ledger.lampDeep
                    )
                    CloudChip(
                        text = workspace.id.takeLast(6),
                        accentColor = MaterialTheme.ledger.deskInkFaint
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete, modifier = Modifier.height(40.dp).width(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete workspace",
                    tint = MaterialTheme.ledger.deskInkFaint,
                    modifier = Modifier.height(20.dp).width(20.dp)
                )
            }
        }
    }
}

@Composable
private fun NamePromptDialog(
    title: String,
    label: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifBlank { "workspace" }) },
                enabled = text.isNotBlank()
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
