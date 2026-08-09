# Android Permissions Reference

Complete reference for all permissions declared in AndroLLM.

---

## Permission Declarations

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

---

## Permission Details

### Required Permissions

| Permission | SDK | Runtime? | Purpose | Can Be Denied? |
|---|---|---|---|---|
| `INTERNET` | All | No | Network requests (cloud providers, model downloads) | No — app non-functional without it |
| `ACCESS_NETWORK_STATE` | All | No | Check connectivity before operations | No — graceful degradation only |

### Conditional Permissions (Voice Assistant)

| Permission | Min SDK | Runtime? | Purpose | Requested When |
|---|---|---|---|---|
| `RECORD_AUDIO` | All | Yes | Microphone input for voice assistant | Voice assistant enabled in settings |
| `FOREGROUND_SERVICE` | All | No | Run voice service in foreground | Voice assistant started |
| `FOREGROUND_SERVICE_MICROPHONE` | 34 | No | Android 14+ foreground service type | Voice assistant started |
| `POST_NOTIFICATIONS` | 33 | Yes | Show voice service notification | Voice assistant started (Android 13+) |
| `WAKE_LOCK` | All | No | Prevent device sleep during generation | First model load |

### Optional Permissions

| Permission | Runtime? | Purpose | Impact if Denied |
|---|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Yes (settings) | Floating voice overlay UI | Overlay hidden; service still runs via notification |
| `MANAGE_EXTERNAL_STORAGE` | Yes (settings) | Access files outside app sandbox | Limited to app-internal storage |

### Unused / Legacy

| Permission | Status | Notes |
|---|---|---|
| `READ_EXTERNAL_STORAGE` | Legacy | maxSdkVersion=28; not needed on Android 10+ |
| `WRITE_EXTERNAL_STORAGE` | Legacy | maxSdkVersion=28; not needed on Android 10+ |
| `CAMERA` | Declared but unused | Kept for potential future vision model support |

---

## Permission Request Flow

```
User enables feature requiring permission
         │
         ▼
PermissionUtils.checkAndRequest()
         │
         ├── Permission already granted? → Proceed
         │
         └── Not granted → RequestMultiplePermissions.launch()
                              │
                              ├── Granted → Feature activates
                              └── Denied → Show rationale dialog
                                              │
                                              └── Again denied → Settings deep link
```

### Rationale Messages

| Permission | Rationale |
|---|---|
| RECORD_AUDIO | "The voice assistant needs microphone access to hear 'Hey Andro' and your questions. Audio is processed entirely on-device." |
| POST_NOTIFICATIONS | "A notification keeps the voice assistant running in the background. It will show 'Listening for Hey Andro'." |
| SYSTEM_ALERT_WINDOW | "Overlay permission lets the voice assistant show a floating panel. The assistant still works without it via the notification." |

---

## Dynamically Checked Permissions

The app checks permissions at runtime rather than assuming they're granted:

```kotlin
// Permission check helper
suspend fun requirePermission(
    context: Context,
    permission: String,
    rationale: String
): Boolean {
    return if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        true
    } else {
        // Show rationale
        withPermissionLauncher.launch(permission)
        false
    }
}

// Usage in VoiceAssistantSection
if (!requirePermission(context, RECORD_AUDIO, "Microphone permission...")) {
    return
}
```

---

## Android Version Considerations

| Android Version | Permission Behavior |
|---|---|
| 9 (API 28) | `READ/WRITE_EXTERNAL_STORAGE` required for file access |
| 10 (API 29) | Scoped storage; external storage access limited |
| 11 (API 30) | `MANAGE_EXTERNAL_STORAGE` for full filesystem access |
| 12 (API 31) | `POST_NOTIFICATIONS` not required yet |
| 13 (API 33) | `POST_NOTIFICATIONS` becomes required for notifications |
| 14 (API 34) | `FOREGROUND_SERVICE_MICROPHONE` required for mic service |
| 14+ | Foreground service type must be declared in manifest |

---

## Debugging Permission Issues

### Check Granted Permissions

```bash
adb shell pm list permissions -g | grep androllm
```

### Force Grant/Deny

```bash
# Grant
adb shell pm grant io.androllm.app android.permission.RECORD_AUDIO
# Deny
adb shell pm deny io.androllm.app android.permission.RECORD_AUDIO
# Check status
adb shell dumpsys package io.androllm.app | grep RECORD_AUDIO
```

### Common Issues

| Symptom | Cause | Fix |
|---|---|---|
| Mic doesn't work after grant | Service needs restart | Stop and restart voice assistant |
| Notification doesn't appear | POST_NOTIFICATIONS denied | Grant in Settings → Apps |
| Overlay not showing | SYSTEM_ALERT_WINDOW denied | Grant in Settings → Special access |
| Crash on permission check | Not using `ContextCompat` | Use `ContextCompat.checkSelfPermission()` |
