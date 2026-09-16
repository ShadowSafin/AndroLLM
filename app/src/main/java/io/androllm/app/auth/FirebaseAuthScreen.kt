package io.androllm.app.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudDialog
import io.androllm.core.ui.components.DotGridBackground
import io.androllm.core.ui.components.GitHubIcon
import io.androllm.core.ui.components.GoogleGlyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.sqrt

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

    var isLogin by remember { mutableStateOf(true) }
    var pendingAction by remember { mutableStateOf<AuthAction?>(null) }
    var legalDialog by remember { mutableStateOf<LegalDoc?>(null) }
    val isLoading = pendingAction != null

    // ── Google Sign-In (Credential Manager, per official Firebase docs) ──

    fun failGetCredential(e: GetCredentialException) {
        pendingAction = null
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
        pendingAction = null
        toast(context, message)
    }

    /** Last-resort handler — never leaves the UI stuck in a loading state. */
    fun unexpectedGoogleError(e: Exception) {
        pendingAction = null
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
            pendingAction = null
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
        pendingAction = AuthAction.GOOGLE
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
                    pendingAction = null
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
                pendingAction = null
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
        pendingAction = null
        Timber.i("[Auth] Authentication success (GitHub) — uid=${auth?.currentUser?.uid}")
        onAuthSuccess(result.additionalUserInfo?.isNewUser == true)
    }

    fun githubFailure(e: Exception, toastContext: Context) {
        pendingAction = null
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
        pendingAction = AuthAction.GITHUB
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // WebGL dot canvas.
        DotGridBackground(modifier = Modifier.fillMaxSize())

        // Vignette.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val centerPx = with(density) {
                Offset(maxWidth.toPx() / 2f, maxHeight.toPx() / 2f)
            }
            val radiusPx = with(density) {
                val hw = maxWidth.toPx() / 2f
                val hh = maxHeight.toPx() / 2f
                sqrt(hw * hw + hh * hh)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent
                            ),
                            center = centerPx,
                            radius = radiusPx
                        )
                    )
            )
        }

        // Modal card.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(12.dp),
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF121212))
                    .border(1.dp, Color(0xFF222222), RoundedCornerShape(12.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF111111))
                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    CloudBugdroidLogo(size = 26.dp, showMoon = false)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isLogin) "Sign in to Account" else "Sign up for Account",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.025).em,
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isLogin) "Sign in to your Account." else "Create a new account to get started.",
                    style = TextStyle(
                        color = Color(0xFF888888),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Social providers — the two shipped options: Google + GitHub.
                val socialNoun = if (isLogin) "Continue" else "Sign up"
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AuthSocialButton(
                        text = "$socialNoun with Google",
                        loading = pendingAction == AuthAction.GOOGLE,
                        onClick = ::googleSignIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        GoogleGlyph(size = 16.dp)
                    }
                    AuthSocialButton(
                        text = "$socialNoun with GitHub",
                        loading = pendingAction == AuthAction.GITHUB,
                        onClick = ::githubSignIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = GitHubIcon,
                            contentDescription = "GitHub",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Debug builds only: local guest entry so on-device validation
                // never depends on a Firebase account. Release builds keep the
                // strict provider gate — this branch is compiled out entirely.
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Continue as guest (debug)",
                        style = TextStyle(
                            color = Color(0xFF888888),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.clickable(enabled = !isLoading) {
                            Timber.i("[Auth] Guest mode entered (debug build) — on-device features only")
                            toast(context, "Guest mode — no cloud profile sync")
                            onAuthSuccess(false)
                        }
                    )
                }

                // Mode toggle.
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isLogin) "Don't have an account? " else "Already have an account? ",
                        style = TextStyle(color = Color(0xFF888888), fontSize = 14.sp)
                    )
                    Text(
                        text = if (isLogin) "Sign Up" else "Sign In",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.clickable(enabled = !isLoading) { isLogin = !isLogin }
                    )
                }

                // Legal footer.
                Spacer(modifier = Modifier.height(14.dp))
                AuthLegalFooter(onOpenDoc = { legalDialog = it })
            }
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
                style = TextStyle(
                    color = Color(0xFF888888),
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                )
            )
        }
    }
}

/** Which auth entry point is currently awaiting a result (button spinners). */
private enum class AuthAction {
    GOOGLE, GITHUB
}

@Composable
private fun ProviderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: Brush,
    textColor: Color = MaterialTheme.ledger.deskPaper,
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
