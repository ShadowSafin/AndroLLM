# Firebase Authentication Guide

Authentication architecture and usage in AndroLLM.

---

## Overview

AndroLLM uses Firebase Authentication for optional user sign-in. Authentication enables cloud sync features but is **not required** — the app works fully in guest mode.

---

## Supported Providers

| Provider | Method | Scopes |
|---|---|---|
| **Google** | Credential Manager + Google Identity Services | None required (email retrieved from ID token) |
| **GitHub** | Firebase OAuth | `read:user`, `user:email` |

---

## Authentication Flow

```
SplashScreen checks: isSignedInToFirebase()
        │
        ├── Signed in → HomeScreen
        │
        └── Not signed in
              │
              ├── Onboarding completed?
              │     ├── Yes → AuthScreen
              │     └── No  → OnboardingScreen → AuthScreen
              │
AuthScreen
  ├── Google Sign-In
  │     ├── Try authorized accounts first
  │     ├── Fall back to all accounts on NoCredentialException
  │     ├── Exchange ID token via GoogleAuthProvider.getCredential()
  │     └── Navigate to ProfileSetup (new user) or Home (returning)
  │
  └── GitHub Sign-In
        ├── OAuthProvider.newBuilder("github.com")
        ├── Handle pendingAuthResult (reclaimed activity)
        ├── startActivityForResultForSignInWithProvider()
        └── Navigate to ProfileSetup (new user) or Home (returning)
```

---

## Google Sign-In Implementation

**File:** [`app/src/main/java/io/androllm/app/auth/FirebaseAuthScreen.kt`](../../app/src/main/java/io/androllm/app/auth/FirebaseAuthScreen.kt)

```kotlin
// Uses Credential Manager with Google Identity Services
val googleIdOption = GetGoogleIdOption.Builder()
    .setFilterByAuthorizedAccounts(true)  // Try authorized accounts first
    .setServerClientId(defaultWebClientId)  // From google-services.json
    .build()

val request = GetCredentialRequest.Builder()
    .addCredentialOption(googleIdOption)
    .build()

// On NoCredentialException, retry with filterByAuthorizedAccounts=false
```

**Error handling:**
- SHA fingerprint errors → toast prompting Firebase console update
- No Google account on device → toast message
- Network unavailable → "Network unavailable" error
- Invalid credentials → provider-specific error message

---

## GitHub Sign-In Implementation

```kotlin
val githubProvider = OAuthProvider.newBuilder("github.com")
    .setScopes listOf("read:user", "user:email")
    .build()

// Handle pending result for activity recreation
Firebase.auth.pendingAuthResult
    .addOnSuccessListener { authResult -> signInSuccess(authResult) }
    .addOnFailureListener { handleError(it) }

// Start the OAuth flow
FirebaseAuth.getInstance()
    .startActivityForSignInWithProvider(activity, githubProvider)
    .addOnSuccessListener { signInSuccess(it) }
    .addOnFailureListener { handleError(it) }
```

---

## Profile Setup

**File:** [`app/src/main/java/io/androllm/app/profile/ProfileSetupScreen.kt`](../../app/src/main/java/io/androllm/app/profile/ProfileSetupScreen.kt)

First-time users complete a one-time profile setup:
- Display name
- Avatar (optional)
- Bio (optional)

Profile data is stored in Firebase Firestore (best-effort; never blocks offline use).

---

## Session Management

### Persistence

Firebase Auth sessions persist across app restarts automatically:
- `FirebaseAuth.getInstance().authStateListener` tracks sign-in state
- Session tokens are cached in Android's secure storage
- No re-authentication required unless the token expires

### Sign-Out

```kotlin
FirebaseAuth.getInstance().signOut()
```

Sign-out:
- Clears the Firebase session
- Does NOT delete local data (conversations, models, memories)
- Routes back to the auth screen
- Profile data in Firestore is preserved (can be deleted separately)

### Account Deletion

```kotlin
FirebaseAuth.getInstance().currentUser?.delete()
    ?.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            // Session cleared, data deleted from Firebase
        }
    }
```

Account deletion:
- Deletes the Firebase Auth account
- Deletes Firestore profile data
- Does NOT delete local data (user must clear app data separately)

---

## Navigation Integration

**File:** [`app/src/main/java/io/androllm/app/navigation/AppNavHost.kt`](../../app/src/main/java/io/androllm/app/navigation/AppNavHost.kt)

```kotlin
fun isSignedInToFirebase(): Boolean =
    runCatching { FirebaseAuth.getInstance().currentUser != null }
        .getOrDefault(false)
```

The splash screen checks this function to determine the entry point:
- `true` → HomeScreen
- `false` → OnboardingScreen (if not onboarded) or AuthScreen (if onboarded)

**Important:** Firebase misconfiguration never crashes navigation. The `runCatching` wrapper ensures that an uninitialized Firebase instance falls back to guest mode gracefully.

---

## Firebase Configuration

**File:** `app/google-services.json`

The Firebase project configuration is embedded in the app module. Key fields:

| Field | Purpose |
|---|---|
| `project_number` | Firebase project identifier |
| `project_id` | Unique project ID (`androllm`) |
| `storage_bucket` | Firebase Storage bucket |
| `mobilesdk_app_id` | Android app identifier |
| `oauth_client.client_id` | Web client ID for Google Sign-In |
| `api_key.current_key` | Firebase API key |

### Setting Up Firebase

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
2. Register the Android app with package name `io.androllm.app`
3. Download `google-services.json` and place it in `app/`
4. Enable Authentication → Sign-in method → Google and GitHub
5. For Google: Add your SHA-256 fingerprint to the Firebase project settings
6. For GitHub: Create a GitHub OAuth app and add the callback URL

---

## Debugging

### Common Issues

| Issue | Cause | Solution |
|---|---|---|
| "Sign in failed" with SHA error | Debug keystore hash not registered | Add SHA-256 to Firebase Console |
| OAuth popup closes immediately | Missing `pendingAuthResult` handler | Ensure `startActivityForSignInWithProvider` is used |
| Google sign-in returns null account | No Google account on device | Add a Google account in Android settings |
| GitHub OAuth fails with redirect error | Wrong callback URL in GitHub OAuth app | Use `https://app103114921522648382222.auth.firebase.google.com/v1/taskChainCompletion/` (auto-generated by Firebase) |
| App crashes on startup (no Firebase) | Missing `google-services.json` | Add the file or comment out the plugin for local builds |

### Getting Debug SHA-256

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
    -alias androiddebugkey -storepass android -keypass android -rfc
```

Add the SHA-256 certificate fingerprint to your Firebase project:
Firebase Console → Project Settings → Your Apps → Add Fingerprint

---

## Security Notes

1. **Guest mode is always available** — authentication is optional
2. **Local data is never deleted** by signing out or deleting a Firebase account
3. **SHA fingerprints must match** — debug and release keystores need separate entries in Firebase Console
4. **GitHub scopes are minimal** — only `read:user` and `user:email` are requested
5. **No password storage** — Firebase handles credential security; AndroLLM never sees passwords

---

## Firebase Services Used

| Service | Usage | Required? |
|---|---|---|
| Firebase Auth | User sign-in (Google, GitHub) | No (guest mode available) |
| Firebase Firestore | Profile data storage | No (local-first) |
| Firebase Analytics | None currently | No |
| Firebase Crashlytics | Not yet integrated | No |

---

## Planned Authentication Features

| Feature | Status | Notes |
|---|---|---|
| Email/password sign-in | 🔮 Future | Traditional auth option |
| Apple Sign-In | 🔮 Future | iOS parity; Android TBD |
| Multi-account support | 🔮 Future | Switch between accounts |
| SSO enterprise | 🔮 Future | OIDC/SAML for organizations |
| Firebase Crashlytics | 🚧 Planned | Crash reporting integration |
