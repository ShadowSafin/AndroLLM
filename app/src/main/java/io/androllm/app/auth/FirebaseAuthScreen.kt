package io.androllm.app.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import io.androllm.app.BuildConfig
import io.androllm.app.R
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudDialog
import io.androllm.core.ui.components.CloudProgress
import io.androllm.core.ui.components.GitHubIcon
import io.androllm.core.ui.components.GoogleGlyph
import io.androllm.core.ui.theme.CloudCapsuleShape
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Authentication Entrance — Cloud Intelligence edition.
 *
 * Supports exactly two providers, per the product decision:
 *   • Google  — Credential Manager / Google Identity Services flow
 *               ([GetGoogleIdOption] + [GetCredentialRequest]), the SDK now
 *               required by the official Firebase docs. Uses the web client id
 *               from google-services.json (`default_web_client_id`).
 *   • GitHub  — Firebase OAuth provider ("github.com") via a Custom Chrome Tab,
 *               with scopes `read:user` + `user:email` and a
 *               [FirebaseAuth.pendingAuthResult] check so an Activity reclaimed
 *               during the flow never forces a duplicate sign-in.
 *
 * No email/password, no phone, no anonymous, no guest mode. Sessions persist
 * through Firebase, so returning users go Splash → Home automatically.
 *
 * [onAuthSuccess] reports whether the account was just created
 * (additionalUserInfo.isNewUser), letting the host route first-time users
 * through profile setup.
 */
@Composable
fun FirebaseAuthScreen(
    onAuthSuccess: (isNewUser: Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val scope = rememberCoroutineScope()
    val auth = remember {
        runCatching {
            FirebaseAuth.getInstance().also {
                Timber.d("[Auth] Firebase initialized (project: ${it.app.options.projectId})")
            }
        }.getOrNull()
    }
    val credentialManager = remember {
        runCatching { androidx.credentials.CredentialManager.create(context) }.getOrNull()
    }

    var isLoading by remember { mutableStateOf(false) }
    var legalDialog by remember { mutableStateOf<LegalDoc?>(null) }

    // ── Google Sign-In (Credential Manager, per official Firebase docs) ──

    fun failGetCredential(e: GetCredentialException) {
        isLoading = false
        when (e) {
            is GetCredentialProviderConfigurationException -> {
                // Documented cause: missing/wrong SHA-1 (or SHA-256) fingerprint
                // on the Firebase/Google console Android client, or a mismatched
                // server (web) client id.
                Timber.e(
                    e,
                    "[Auth] Google provider configuration invalid — " +
                        "check SHA-1/SHA-256 fingerprints and server client id in the Firebase console"
                )
                toast(context, "Missing SHA fingerprint — check your Firebase console configuration")
            }
            is NoCredentialException -> {
                Timber.e(e, "[Auth] No Google account available on this device")
                toast(context, "No Google account available on this device")
            }
            else -> {
                Timber.e(e, "[Auth] Google Sign-In failed (${e::class.java.simpleName})")
                toast(context, "Google Play Services unavailable")
            }
        }
    }

    /** User-facing handling of a Google credential problem. */
    fun handleGoogleFailure(message: String) {
        isLoading = false
        toast(context, message)
    }

    /** Last-resort handler — never leaves the UI stuck in a loading state. */
    fun unexpectedGoogleError(e: Exception) {
        isLoading = false
        Timber.e(e, "[Auth] Google Sign-In failed (unexpected error: ${e::class.java.simpleName})")
        toast(context, "Google Play Services unavailable")
    }

    /** Shared Firebase credential-exchange failure mapping for both providers. */
    fun failFirebaseAuth(e: Exception?, isGoogle: Boolean) {
        val provider = if (isGoogle) "Google" else "GitHub"
        val errorCode = (e as? FirebaseAuthException)?.errorCode
        Timber.e(e, "[Auth] Authentication failure ($provider): ${e?.message} (errorCode=$errorCode)")
        when (e) {
            is FirebaseAuthInvalidUserException ->
                toast(context, "Your $provider account was disabled or deleted")
            is FirebaseAuthInvalidCredentialsException ->
                if (isGoogle) {
                    toast(context, "Expired credential — please sign in again")
                } else {
                    toast(context, "Invalid GitHub OAuth configuration")
                }
            is FirebaseNetworkException ->
                toast(context, "Network unavailable — check your connection")
            is FirebaseAuthException ->
                if (!isGoogle && errorCode == "ERROR_INVALID_CREDENTIAL") {
                    // For OAuth providers this code means the Firebase console
                    // configuration is wrong: client ID, client secret or the
                    // OAuth redirect URI don't match the provider app.
                    Timber.e("[Auth] Invalid GitHub OAuth configuration (errorCode=$errorCode) — " +
                        "verify client ID, client secret and redirect URI in the Firebase console")
                    toast(context, "Invalid GitHub OAuth configuration (check client ID, secret and redirect URI)")
                } else {
                    toast(context, "Internal Firebase error — please try again")
                }
            else -> toast(context, "Internal Firebase error — please try again")
        }
    }

    /** Exchanges a Google ID token for a Firebase credential and signs in. */
    fun firebaseAuthWithGoogle(idToken: String) {
        Timber.d("[Auth] Google credential received — exchanging for Firebase credential")
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth?.signInWithCredential(credential)?.addOnCompleteListener { task ->
            isLoading = false
            if (task.isSuccessful) {
                Timber.i("[Auth] Authentication success (Google) — uid=${auth?.currentUser?.uid}")
                onAuthSuccess(task.result?.additionalUserInfo?.isNewUser == true)
            } else {
                failFirebaseAuth(task.exception, isGoogle = true)
            }
        }
    }

    /** Handles a returned [GetCredentialResponse], validating the Google ID token. */
    fun handleGoogleCredential(response: GetCredentialResponse) {
        val credential: Credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdToken = runCatching { GoogleIdTokenCredential.createFrom(credential.data) }
                .getOrElse { e ->
                    // createFrom throws GoogleIdTokenParsingException for expired/malformed tokens.
                    Timber.e(e, "[Auth] Google ID token parsing failed — credential expired or malformed")
                    return handleGoogleFailure("Expired credential — please sign in again")
                }
            firebaseAuthWithGoogle(googleIdToken.idToken)
        } else {
            Timber.w("[Auth] Google credential was not a Google ID token (type=${credential.type})")
            handleGoogleFailure("Google Sign-In is not configured correctly")
        }
    }

    /** Runs the [GetGoogleIdOption] flow, retrying with all accounts when needed. */
    fun googleSignIn() {
        if (auth == null || credentialManager == null) {
            Timber.e("[Auth] Google Sign-In blocked — auth=${auth != null}, credentialManager=${credentialManager != null}")
            toast(context, "Google Play Services unavailable")
            return
        }
        val serverClientId = runCatching { context.getString(R.string.default_web_client_id) }
            .getOrNull().orEmpty()
        if (serverClientId.isBlank()) {
            Timber.e("[Auth] Google Sign-In blocked — default_web_client_id missing (invalid Firebase configuration)")
            toast(context, "Invalid Firebase configuration")
            return
        }

        Timber.d("[Auth] Google Sign-In started (Credential Manager)")
        isLoading = true
        scope.launch {
            try {
                // First attempt: only accounts previously authorized for this app.
                val result = credentialManager.getCredential(
                    context,
                    GetCredentialRequest.Builder()
                        .addCredentialOption(
                            GetGoogleIdOption.Builder()
                                .setServerClientId(serverClientId)
                                .setFilterByAuthorizedAccounts(true)
                                .build()
                        )
                        .build()
                )
                handleGoogleCredential(result)
            } catch (e: NoCredentialException) {
                // No previously authorized account — retry with all accounts so
                // first-time sign-in works (official Google Identity pattern).
                Timber.i("[Auth] No authorized Google account — retrying with all accounts")
                try {
                    val result = credentialManager.getCredential(
                        context,
                        GetCredentialRequest.Builder()
                            .addCredentialOption(
                                GetGoogleIdOption.Builder()
                                    .setServerClientId(serverClientId)
                                    .setFilterByAuthorizedAccounts(false)
                                    .build()
                            )
                            .build()
                    )
                    handleGoogleCredential(result)
                } catch (e2: GetCredentialCancellationException) {
                    Timber.i("[Auth] Google Sign-In cancelled by user")
                    isLoading = false
                    toast(context, "Sign-in cancelled")
                } catch (e2: GetCredentialException) {
                    failGetCredential(e2)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    unexpectedGoogleError(e2)
                }
            } catch (e: GetCredentialCancellationException) {
                Timber.i("[Auth] Google Sign-In cancelled by user")
                isLoading = false
                toast(context, "Sign-in cancelled")
            } catch (e: GetCredentialException) {
                failGetCredential(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpectedGoogleError(e)
            }
        }
    }

    // ── GitHub Sign-In (Firebase OAuth provider, per official Firebase docs) ──

    fun githubSuccess(result: AuthResult, toastContext: Context) {
        isLoading = false
        Timber.i("[Auth] Authentication success (GitHub) — uid=${auth?.currentUser?.uid}")
        onAuthSuccess(result.additionalUserInfo?.isNewUser == true)
    }

    fun githubFailure(e: Exception, toastContext: Context) {
        isLoading = false
        val errorCode = (e as? FirebaseAuthException)?.errorCode
        Timber.e(e, "[Auth] Authentication failure (GitHub): ${e.message} (errorCode=$errorCode)")
        when (e) {
            is FirebaseAuthInvalidUserException ->
                toast(toastContext, "Your GitHub account was disabled or deleted")
            is FirebaseAuthInvalidCredentialsException ->
                toast(toastContext, "Invalid GitHub OAuth configuration")
            is FirebaseNetworkException ->
                toast(toastContext, "Network unavailable — check your connection")
            is FirebaseAuthException ->
                if (errorCode == "ERROR_INVALID_CREDENTIAL") {
                    // OAuth flow failed the token exchange: the console config
                    // (client ID, client secret, redirect URI) is wrong.
                    Timber.e("[Auth] Invalid GitHub OAuth configuration (errorCode=$errorCode) — " +
                        "verify client ID, client secret and redirect URI in the Firebase console")
                    toast(toastContext, "Invalid GitHub OAuth configuration (check client ID, secret and redirect URI)")
                } else {
                    toast(toastContext, "Internal Firebase error — please try again")
                }
            is ApiException -> {
                if (e.statusCode == CommonStatusCodes.CANCELED) {
                    Timber.i("[Auth] GitHub OAuth cancelled by user")
                    toast(toastContext, "Sign-in cancelled")
                } else {
                    Timber.e(e, "[Auth] GitHub OAuth failed (statusCode=${e.statusCode})")
                    toast(toastContext, "Internal Firebase error — please try again")
                }
            }
            else -> toast(toastContext, "Internal Firebase error — please try again")
        }
    }

    fun githubSignIn() {
        val a = activity
        val fbAuth = auth
        if (fbAuth == null || a == null) {
            Timber.e("[Auth] GitHub Sign-In blocked — auth=${fbAuth != null}, activity=${a != null}")
            toast(context, "GitHub sign-in unavailable")
            return
        }
        // Use the application context for toasts attached to the OAuth listeners:
        // the Custom Chrome Tab flow backgrounds this Activity, and the Firebase
        // docs warn against referencing the Activity from those listeners.
        val appContext = context.applicationContext

        Timber.d("[Auth] GitHub OAuth launched (scopes: read:user, user:email)")
        isLoading = true
        val provider = OAuthProvider.newBuilder("github.com")
            .setScopes(listOf("read:user", "user:email"))
            .build()

        // The Activity can be reclaimed while the Custom Chrome Tab is up. If a
        // pending result already exists, finish that flow instead of starting a
        // duplicate sign-in (official Firebase docs requirement).
        val pendingResultTask = fbAuth.pendingAuthResult
        if (pendingResultTask != null) {
            Timber.d("[Auth] GitHub OAuth pending result found — resuming existing flow")
            pendingResultTask
                .addOnSuccessListener { result ->
                    Timber.d("[Auth] GitHub callback received (pending)")
                    githubSuccess(result, appContext)
                }
                .addOnFailureListener { e -> githubFailure(e, appContext) }
            return
        }

        fbAuth.startActivityForSignInWithProvider(a, provider)
            .addOnSuccessListener { result ->
                Timber.d("[Auth] GitHub callback received")
                githubSuccess(result, appContext)
            }
            .addOnFailureListener { e -> githubFailure(e, appContext) }
    }

    CloudAtmosphericBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CloudBugdroidLogo(size = 128.dp)

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Welcome back",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = io.androllm.core.ui.theme.DeskPaper
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your private AI stays on your device.\nSign in to keep your profile in sync.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = io.androllm.core.ui.theme.DeskInk
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            if (isLoading) {
                CloudProgress(size = 64.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connecting securely…",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = io.androllm.core.ui.theme.DeskInk,
                        letterSpacing = 1.sp
                    )
                )
            } else {
                // Continue with Google
                ProviderButton(
                    text = "Continue with Google",
                    onClick = ::googleSignIn,
                    gradient = Brush.horizontalGradient(
                        listOf(Color(0xFFFFFFFF), Color(0xFFF8F9FA))
                    ),
                    textColor = Color(0xFF1F1F1F),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GoogleGlyph(size = 20.dp)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Continue with GitHub
                ProviderButton(
                    text = "Continue with GitHub",
                    onClick = ::githubSignIn,
                    gradient = Brush.horizontalGradient(
                        listOf(Color(0xFF24292E), Color(0xFF181717))
                    ),
                    textColor = Color(0xFFFFFFFF),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = GitHubIcon,
                        contentDescription = "GitHub",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Debug builds only: local guest entry so on-device validation
                // (e.g. the NPU/QNN probe chain) never depends on a Firebase
                // account. Release builds keep the strict two-provider gate —
                // this branch is compiled out entirely.
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(14.dp))
                    ProviderButton(
                        text = "Continue as guest",
                        onClick = {
                            Timber.i("[Auth] Guest mode entered (debug build) — on-device features only")
                            toast(context, "Guest mode — no cloud profile sync")
                            onAuthSuccess(false)
                        },
                        gradient = Brush.horizontalGradient(
                            listOf(Color(0xFF263238), Color(0xFF1B2226))
                        ),
                        textColor = Color(0xFFB0BEC5),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = GitHubIcon,
                            contentDescription = "Guest",
                            tint = Color(0xFFB0BEC5),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Legal footer
            val legal = buildAnnotatedString {
                append("By continuing you agree to the ")
                withStyle(SpanStyle(color = io.androllm.core.ui.theme.LampDeep, fontWeight = FontWeight.SemiBold)) {
                    append("Privacy Policy")
                }
                append(" and ")
                withStyle(SpanStyle(color = io.androllm.core.ui.theme.LampDeep, fontWeight = FontWeight.SemiBold)) {
                    append("Terms of Service")
                }
                append(".")
            }
            Text(
                text = legal,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = io.androllm.core.ui.theme.DeskInk,
                    fontSize = 11.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable { legalDialog = LegalDoc.PRIVACY }
                    .padding(4.dp)
            )
        }
    }

    legalDialog?.let { doc ->
        CloudDialog(
            title = doc.title,
            onDismiss = { legalDialog = null },
            onConfirm = { legalDialog = null },
            confirmText = "Got it"
        ) {
            Text(
                text = doc.body,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = io.androllm.core.ui.theme.DeskInk,
                    lineHeight = 18.sp
                )
            )
        }
    }
}

/**
 * Premium capsule action button with a custom leading glyph.
 */
@Composable
private fun ProviderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: Brush,
    textColor: Color = io.androllm.core.ui.theme.DeskPaper,
    glyph: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "providerButtonScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(CloudCapsuleShape)
            .background(gradient)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            glyph()
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            )
        }
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/**
 * Resolves the hosting [Activity] from any context, or null when none exists.
 * Needed by the Firebase OAuth provider flow ([FirebaseAuth.startActivityForSignInWithProvider]).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class LegalDoc(val title: String, val body: String) {
    PRIVACY(
        "Privacy Policy",
        "AndroLLM is a private, on-device AI assistant. Your conversations, models and prompts " +
            "stay on your device and are never uploaded.\n\n" +
            "When you sign in, we store only your profile information (name, avatar, accent color, " +
            "favorites) to keep your experience consistent across devices. We never sell your data."
    ),
    TERMS(
        "Terms of Service",
        "AndroLLM runs large language models locally on your device. Model output is generated by " +
            "on-device inference and may occasionally be inaccurate — always verify important content.\n\n" +
            "By continuing you agree to use the app responsibly and to comply with applicable laws."
    )
}
