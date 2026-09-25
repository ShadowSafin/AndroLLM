# Firebase Auth Quick Guide

Quick reference for Firebase authentication in AndroLLM.

---

## Overview

Firebase Authentication is optional. You can use AndroLLM fully as a guest without signing in. Authentication enables profile sync and future cloud features.

---

## Sign-In Options

### Google Sign-In

1. Tap **Sign in with Google** on the auth screen
2. Select your Google account (or sign in)
3. You're redirected back to the app automatically

### GitHub Sign-In

1. Tap **Sign in with GitHub**
2. Authorize the app in the browser (scopes: `read:user`, `user:email`)
3. You're redirected back to the app

---

## What Authentication Enables

| Feature | Requires Auth? |
|---|---|
| Local model inference | No |
| Cloud provider chat | No |
| Voice assistant | No |
| Persistent memory | No |
| Profile sync | Yes |
| Cross-device conversation sync | Yes (planned) |

---

## Session Management

- Sessions persist across app restarts
- No re-authentication required unless token expires
- Sign out from Settings → Account → Sign Out
- Account deletion removes all Firebase data

## Settings Page Auth & Sync Status

`feature/settings` (`SettingsViewModel` + `SettingsScreen`) is the single
reader for the Account & Sync header — FirebaseAuth stays the source of truth:

- Auth state: `FirebaseAuth.AuthStateListener` → `SettingsViewModel.user`.
  Signed-in shows the identity + **Sign out**; signed-out shows
  **Sign in with Google**. The button flips immediately via the listener (plus
  a synchronous clear on logout) — no restart, Google flow unchanged.
  Tapping **Sign out** calls `SettingsViewModel.signOut()` then navigates to
  `Routes.AUTH` clearing the back stack, so the user lands on the
  login/signup page (same behavior in Profile → Sign Out).
- Downloaded models: `ModelRepository.observeDownloaded()` count (`null` =
  loading → "…" placeholder).
- Storage: `StorageUtils.getStorageStats()` used bytes, re-read on every
  model-list change and on `refreshSettingsSummary()`.
- Execution backend: active `EngineRepository.engineState` backend wins;
  otherwise the persisted `backendPreference` (NPU/GPU/CPU); otherwise AUTO
  resolves via `BackendSelector.bestAvailable(backendCapabilities)`
  ("Auto · GPU"). Failures fall back to "Unknown", never hardcoded values.
- Web Dashboard: `WebDashboardViewModel` reconciles `GET /me`
  (`web_connected`) with the `PreferencesDataStore` display cache, so the
  Connected / Not connected / Sign-in required states are always real.

---

## Troubleshooting

| Issue | Fix |
|---|---|
| "SHA-256 fingerprint missing" | Add debug keystore hash to Firebase Console |
| "No Google account on device" | Add Google account in Android Settings |
| GitHub OAuth fails | Verify callback URL in GitHub OAuth app settings |
| App crashes on auth screen | Firebase may be misconfigured — check google-services.json |

Getting the debug SHA-256:
```bash
keytool -list -v -keystore ~/.android/debug.keystore \
    -alias androiddebugkey -storepass android -keypass android -rfc
```

---

## See Also

- [Firebase Auth Architecture](backend/firebase-auth.md) — Full technical deep dive
- [README](../README.md) — Feature overview
