package io.androllm.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import io.androllm.core.ui.components.CloudAvatar
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudScaffold
import io.androllm.core.ui.components.CloudSection
import io.androllm.core.ui.components.CloudTextField
import io.androllm.core.ui.components.CloudTopBar
import io.androllm.core.ui.components.CloudAccentOptions
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow

/**
 * First-run profile creation — shown once after the first successful sign-in.
 * The user picks an avatar preset, a display name, an optional username and an
 * accent color. Everything is persisted locally and mirrored to Firebase
 * best-effort, then [onDone] hands over to Home.
 */
@Composable
fun ProfileSetupScreen(
    onDone: () -> Unit,
    viewModel: ProfileSetupViewModel = hiltViewModel()
) {
    val auth = remember { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    val currentUser = remember { auth?.currentUser }
    val isSaving by viewModel.isSaving.collectAsState()

    var displayName by remember { mutableStateOf(currentUser?.displayName ?: "") }
    var username by remember { mutableStateOf("") }
    var avatarIndex by remember { mutableIntStateOf(0) }
    var accent by remember { mutableStateOf(CloudAccentOptions.first()) }

    CloudScaffold(
        topBar = {
            CloudTopBar(title = "Set Up Your Profile")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CloudAvatar(
                size = 104.dp,
                index = avatarIndex,
                initials = displayName
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Make it yours",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = DeskPaper
                )
            )

            currentUser?.email?.let { email ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = DeskInk
                    )
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            CloudSection(title = "Avatar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    repeat(6) { index ->
                        val selected = index == avatarIndex
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) LampGlow.copy(alpha = 0.3f) else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { avatarIndex = index }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CloudAvatar(
                                size = 48.dp,
                                index = index,
                                initials = if (selected) displayName else ""
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            CloudTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Display name",
                placeholder = "Your name"
            )

            Spacer(modifier = Modifier.height(12.dp))

            CloudTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username (optional)",
                placeholder = "@handle"
            )

            Spacer(modifier = Modifier.height(24.dp))

            CloudSection(title = "Accent color") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CloudAccentOptions.forEach { option ->
                        val selected = option == accent
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(option.color)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) DeskPaper else option.color.copy(alpha = 0.6f),
                                    shape = CircleShape
                                )
                                .clickable { accent = option }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            CloudCapsuleButton(
                text = "Continue",
                onClick = {
                    viewModel.save(
                        displayName = displayName,
                        username = username,
                        avatarIndex = avatarIndex,
                        accentHex = accent.argbHex,
                        onDone = onDone
                    )
                },
                enabled = displayName.isNotBlank() && !isSaving,
                gradient = Brush.horizontalGradient(listOf(LampAmber, LampGlow)),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "You can change all of this anytime in Settings.",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = DeskInk
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
