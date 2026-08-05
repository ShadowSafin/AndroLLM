package io.androllm.feature.profile

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.androllm.core.common.UiState
import io.androllm.core.models.Model
import io.androllm.core.navigation.Routes
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.CloudUsageBar
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.utils.StorageUtils

/**
 * Writer's Night Desk — Profile. The desk ledger: identity at the lamp,
 * real usage statistics, starred models, and account actions. Works fully
 * in guest mode, everything kept under the desk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val authMessage by viewModel.authMessage.collectAsStateWithLifecycle()
    val isAvatarUploading by viewModel.isAvatarUploading.collectAsStateWithLifecycle()
    val isSavingProfile by viewModel.isSavingProfile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = (uiState as? UiState.Success)?.data ?: ProfileData()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.uploadAvatar(it) }
    }

    LaunchedEffect(authMessage) {
        authMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeAuthMessage()
        }
    }

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = Routes.PROFILE,
            onTabSelected = { tab -> if (tab.route != Routes.PROFILE) navController.navigate(tab.route) },
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Text(
                            text = "Profile",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = DeskPaper
                            )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    IdentityHeader(user = user, onResendVerification = { viewModel.sendVerificationEmail() })
                }

                item {
                    SectionHeader(title = "Your Intelligence", subtitle = "Live on-device statistics")
                    Spacer(modifier = Modifier.height(10.dp))
                    StatsGrid(data = data)
                }

                item {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        CloudUsageBar(
                            label = "Model Storage",
                            valueText = "${StorageUtils.formatBytes(data.storageUsedBytes)} of ${StorageUtils.formatBytes(data.storageTotalBytes)}",
                            fraction = if (data.storageTotalBytes > 0) data.storageUsedBytes.toFloat() / data.storageTotalBytes else 0f,
                            accent = LampAmber
                        )
                    }
                }

                if (data.favoriteModels.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Starred Models", subtitle = "${data.favoriteModels.size} starred")
                    }
                    items(data.favoriteModels, key = { it.id }) { model ->
                        FavoriteModelRow(model = model)
                    }
                }

                item {
                    SectionHeader(title = "Spaces & Account", subtitle = "Everything about your AI")
                    Spacer(modifier = Modifier.height(10.dp))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ActionRow(
                                icon = Icons.Filled.Code,
                                title = "Prompt Studio",
                                subtitle = "Curated prompt templates",
                                accent = LampGlow,
                                onClick = { navController.navigate(Routes.PROMPTS) }
                            )
                            ActionRow(
                                icon = Icons.Filled.Speed,
                                title = "Developer Mode",
                                subtitle = "Live engine & device telemetry",
                                accent = LampAmber,
                                onClick = { navController.navigate(Routes.DEVELOPER) }
                            )
                            ActionRow(
                                icon = Icons.Filled.Tune,
                                title = "Settings",
                                subtitle = "Appearance, generation, privacy",
                                accent = LampGlow,
                                onClick = { navController.navigate(Routes.SETTINGS) }
                            )
                            if (user != null && user?.isGuest == false) {
                                ActionRow(
                                    icon = Icons.Filled.Star,
                                    title = "Edit Profile",
                                    subtitle = "Display name & avatar",
                                    accent = LampAmber,
                                    onClick = {
                                        editNameText = user?.displayName ?: ""
                                        showEditDialog = true
                                    }
                                )
                                ActionRow(
                                    icon = Icons.Filled.Logout,
                                    title = "Sign Out",
                                    subtitle = "Return to local-only mode",
                                    accent = LampDeep,
                                    onClick = { viewModel.signOut() }
                                )
                                ActionRow(
                                    icon = Icons.Filled.Delete,
                                    title = "Delete Account",
                                    subtitle = "Permanently remove account data",
                                    accent = EmberRed,
                                    onClick = { showDeleteDialog = true }
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Profile") },
            text = {
                Column {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(LampGlow, LampAmber, LampDeep)
                                )
                            )
                            .clickable {
                                avatarPicker.launch("image/*")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAvatarUploading) {
                            CircularProgressIndicator(
                                color = DeskPaper,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Text(
                                text = "Upload\navatar",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeskPaper
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = editNameText,
                        onValueChange = { editNameText = it },
                        singleLine = true,
                        label = { Text("Display name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEditDialog = false
                        viewModel.updateDisplayName(editNameText)
                    },
                    enabled = !isSavingProfile
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete account?") },
            text = { Text("This permanently deletes your Firebase account. Local models and letters on this device are never touched.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteAccount { success, message ->
                        if (!success) {
                            Toast.makeText(context, message ?: "Delete failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Delete", color = EmberRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun IdentityHeader(
    user: UserIdentity?,
    onResendVerification: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lamp-glow avatar
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(LampGlow, LampAmber, LampDeep)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!user?.photoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = user?.photoUrl,
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = (user?.displayName?.firstOrNull()?.uppercase())
                            ?: (user?.email?.firstOrNull()?.uppercase()?.toString())
                            ?: "AI",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = DeskPaper
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = user?.displayName ?: "Local Intelligence",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = DeskPaper
                )
            )
            Text(
                text = user?.email ?: "Guest — 100% on-device",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = DeskInk
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (user == null) {
                    CloudChip(text = "Offline Mode", accentColor = LampGlow, icon = Icons.Filled.Memory)
                } else {
                    CloudChip(
                        text = if (user?.isEmailVerified == true) "Verified" else "Not verified",
                        accentColor = if (user?.isEmailVerified == true) LampGlow else LampAmber,
                        icon = Icons.Filled.VerifiedUser
                    )
                    CloudChip(text = "Synced", accentColor = LampAmber, icon = Icons.Filled.Storage)
                }
            }

            if (user != null && user?.isEmailVerified == false && user?.isGuest == false) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = onResendVerification) {
                    Icon(
                        imageVector = Icons.Filled.MarkEmailRead,
                        contentDescription = null,
                        tint = LampGlow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Resend verification email", color = LampGlow)
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(data: ProfileData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCell(value = data.conversationCount.toString(), label = "Chats", accent = LampGlow, modifier = Modifier.weight(1f))
        StatCell(value = data.modelCount.toString(), label = "Models", accent = LampAmber, modifier = Modifier.weight(1f))
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCell(value = data.downloadCount.toString(), label = "Downloads", accent = LampGlow, modifier = Modifier.weight(1f))
        StatCell(value = String.format("%.1f", data.tokensPerSecond), label = "Tokens/sec", accent = LampAmber, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = accent
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = DeskInk
                )
            )
        }
    }
}

@Composable
private fun FavoriteModelRow(model: Model) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = LampGlow,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeskPaper
                    )
                )
                Text(
                    text = "${model.quantization.ifBlank { "GGUF" }} • ${model.contextLength} ctx",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = DeskInkFaint
                    )
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeskPaper
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = DeskInk
                )
            )
        }
    }
}
