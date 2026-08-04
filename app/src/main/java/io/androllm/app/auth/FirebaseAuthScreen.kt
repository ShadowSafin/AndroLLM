package io.androllm.app.auth

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.CloudCapsuleShape
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudGlassBorderHighlight
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.ElectricBlue
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SoftCyan
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach
import io.androllm.core.ui.theme.SunsetGlowAmber
import kotlinx.coroutines.launch

/**
 * Firebase Authentication Entrance Screen.
 * Features Email/Password sign-in, Google Sign-In placeholder, and non-blocking Guest Mode.
 */
@Composable
fun FirebaseAuthScreen(
    onAuthSuccess: () -> Unit,
    onContinueAsGuest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { FirebaseAuth.getInstance() }

    var isSignUpTab by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    CloudAtmosphericBackground {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Cloud Bugdroid & Moon Logo
                CloudBugdroidLogo(size = 140.dp)

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "AndroLLM",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = CloudWhite,
                        letterSpacing = 2.sp
                    )
                )

                Text(
                    text = "Intelligence Above The Clouds",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = SunsetCloudPeach,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Auth Form Glass Card
                CloudGlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Sign In / Register Tabs
                        TabRow(
                            selectedTabIndex = if (isSignUpTab) 1 else 0,
                            containerColor = Color.Transparent,
                            contentColor = CloudWhite,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(tabPositions[if (isSignUpTab) 1 else 0]),
                                    color = SunsetCloudPeach
                                )
                            }
                        ) {
                            Tab(
                                selected = !isSignUpTab,
                                onClick = { isSignUpTab = false },
                                text = {
                                    Text(
                                        "Sign In",
                                        fontWeight = if (!isSignUpTab) FontWeight.Bold else FontWeight.Normal,
                                        color = if (!isSignUpTab) CloudWhite else MoonSilver.copy(alpha = 0.6f)
                                    )
                                }
                            )
                            Tab(
                                selected = isSignUpTab,
                                onClick = { isSignUpTab = true },
                                text = {
                                    Text(
                                        "Create Account",
                                        fontWeight = if (isSignUpTab) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSignUpTab) CloudWhite else MoonSilver.copy(alpha = 0.6f)
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Email Input
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            placeholder = { Text("Email address", color = MoonSilver.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = SkyBlue) },
                            singleLine = true,
                            shape = CloudCapsuleShape,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SunsetCloudPeach,
                                unfocusedBorderColor = CloudGlassBorder,
                                focusedTextColor = CloudWhite,
                                unfocusedTextColor = CloudWhite
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Password Input
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("Password", color = MoonSilver.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = SkyBlue) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle password visibility",
                                        tint = MoonSilver.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            shape = CloudCapsuleShape,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SunsetCloudPeach,
                                unfocusedBorderColor = CloudGlassBorder,
                                focusedTextColor = CloudWhite,
                                unfocusedTextColor = CloudWhite
                            )
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action Button (Sign In / Register)
                        if (isLoading) {
                            CircularProgressIndicator(color = SoftCyan, modifier = Modifier.size(36.dp))
                        } else {
                            CloudCapsuleButton(
                                text = if (isSignUpTab) "Create Account" else "Sign In",
                                onClick = {
                                    if (email.isBlank() || password.isBlank()) {
                                        Toast.makeText(context, "Please fill email and password", Toast.LENGTH_SHORT).show()
                                        return@CloudCapsuleButton
                                    }
                                    isLoading = true
                                    if (isSignUpTab) {
                                        auth.createUserWithEmailAndPassword(email, password)
                                            .addOnCompleteListener { task ->
                                                isLoading = false
                                                if (task.isSuccessful) {
                                                    Toast.makeText(context, "Account created successfully", Toast.LENGTH_SHORT).show()
                                                    onAuthSuccess()
                                                } else {
                                                    Toast.makeText(context, "Registration failed: ${task.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                    } else {
                                        auth.signInWithEmailAndPassword(email, password)
                                            .addOnCompleteListener { task ->
                                                isLoading = false
                                                if (task.isSuccessful) {
                                                    Toast.makeText(context, "Signed in successfully", Toast.LENGTH_SHORT).show()
                                                    onAuthSuccess()
                                                } else {
                                                    Toast.makeText(context, "Sign in failed: ${task.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                    }
                                },
                                gradient = Brush.horizontalGradient(listOf(SunsetCloudOrange, SunsetGlowAmber)),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Forgot Password Link
                        if (!isSignUpTab) {
                            TextButton(
                                onClick = {
                                    if (email.isNotBlank()) {
                                        auth.sendPasswordResetEmail(email)
                                            .addOnCompleteListener { task ->
                                                if (task.isSuccessful) {
                                                    Toast.makeText(context, "Reset email sent to $email", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "Reset failed: ${task.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                    } else {
                                        Toast.makeText(context, "Enter your email to reset password", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text("Forgot Password?", color = MoonSilver.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Non-Blocking Guest Mode Fallback Button
                CloudCapsuleButton(
                    text = "Continue as Guest (Offline AI)",
                    onClick = onContinueAsGuest,
                    icon = Icons.Default.OfflineBolt,
                    gradient = Brush.horizontalGradient(listOf(CloudWhite.copy(alpha = 0.15f), SkyBlue.copy(alpha = 0.25f))),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "100% On-Device AI • No cloud account required to run local GGUF models",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MoonSilver.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
