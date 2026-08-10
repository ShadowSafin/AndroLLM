# Security Architecture

Deep dive into the security mechanisms protecting user data in AndroLLM.

---

## Security Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                    Layer 1: Android Sandbox                      │
│  App data isolated in /data/data/io.androllm.app/               │
│  No other app can read our files without root access             │
├─────────────────────────────────────────────────────────────────┤
│                    Layer 2: Android Keystore                     │
│  AES-256/GCM encryption for API keys                            │
│  Keys never leave hardware-backed secure enclave (when available)│
├─────────────────────────────────────────────────────────────────┤
│                    Layer 3: Network Security                     │
│  HTTPS-only traffic; TLS 1.2+ enforced                          │
│  Cleartext disabled via android:usesCleartextTraffic="false"    │
├─────────────────────────────────────────────────────────────────┤
│                    Layer 4: Application Logic                    │
│  Minimal permissions; no telemetry; no analytics                │
│  Guest mode always available                                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## API Key Encryption

### KeyCipher Implementation

**File:** [`core/cloud/src/main/java/io/androllm/core/cloud/security/KeyCipher.kt`](../../core/cloud/src/main/java/io/androllm/core/cloud/security/KeyCipher.kt)

```kotlin
interface KeyCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
    fun delete()  // Removes the Keystore key
}

class AndroidKeyCipher @Inject constructor(context: Context) : KeyCipher {
    private val keyAlias = "androllm_cloud_api_keys"

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        
        // 1. Generate 12-byte random IV
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        
        // 2. Get Keystore key
        val key = getKeyFromKeystore()
        
        // 3. AES-256/GCM encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameters(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // 4. Prepend IV to ciphertext
        val combined = iv + ciphertext
        
        // 5. Base64 encode
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        
        // 1. Base64 decode
        val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
        
        // 2. Split IV (first 12 bytes) from ciphertext
        val iv = combined.sliceArray(0..11)
        val ct = combined.sliceArray(12 until combined.size)
        
        // 3. AES-256/GCM decrypt
        val key = getKeyFromKeystore()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameters(iv))
        val plaintext = cipher.doFinal(ct)
        
        return String(plaintext, Charsets.UTF_8)
    }

    override fun delete() {
        // Remove key alias from Keystore
        keyStore.deleteEntry(keyAlias)
    }

    private fun getKeyFromKeystore(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)  // Available even when locked
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
```

### Security Properties

| Property | Implementation |
|---|---|
| Algorithm | AES-256 in GCM mode |
| Key generation | `KeyGenerator` with `KeyGenParameterSpec` |
| Key storage | Android Keystore (hardware-backed on supported devices) |
| Key lifetime | Persists across app restarts; deleted on `delete()` |
| IV | 12-byte random, prepended to ciphertext |
| Encoding | Base64 for storage compatibility |
| Plaintext exposure | Only in memory during API request; never logged |

---

## Permissions Minimalism

### Declared Permissions

| Permission | Max SDK | Purpose | Required? |
|---|---|---|---|
| `INTERNET` | — | Network access for cloud providers, model downloads | ✅ Yes |
| `ACCESS_NETWORK_STATE` | — | Check connectivity before operations | ✅ Yes |
| `RECORD_AUDIO` | — | Voice assistant microphone input | ⚠️ Conditional |
| `FOREGROUND_SERVICE` | — | Voice assistant foreground service | ⚠️ Conditional |
| `FOREGROUND_SERVICE_MICROPHONE` | 34 | Android 14+ foreground service type | ⚠️ Conditional |
| `POST_NOTIFICATIONS` | 33 | Voice assistant notification | ⚠️ Conditional |
| `SYSTEM_ALERT_WINDOW` | — | Floating voice overlay | ❌ Optional |
| `CAMERA` | — | Not currently used; declared but unused | ❌ Unused |
| `READ_EXTERNAL_STORAGE` | 28 | Legacy file access | ⚠️ Legacy |
| `WRITE_EXTERNAL_STORAGE` | 28 | Legacy file access | ⚠️ Legacy |
| `MANAGE_EXTERNAL_STORAGE` | — | Not currently used | ❌ Unused |
| `WAKE_LOCK` | — | Prevent sleep during long generations | ⚠️ Optional |

### Permission Request Strategy

Permissions are requested **lazily** — only when the feature requiring them is activated:
- Microphone: when user enables voice assistant
- Notifications: when voice service starts (Android 13+)
- Overlay: when user opens voice overlay settings

No permissions are requested at app launch.

---

## Local Data Protection

### Database Security

- Room databases stored in app-private directory: `/data/data/io.androllm.app/databases/`
- WAL journal mode for atomic writes
- No SQL debugging enabled in release builds
- Schema exported for review (`exportSchema = true`)

### Model Files

- GGUF files stored in app-private storage
- Not accessible to other apps without root
- Delete removes both file and database entry atomically

### Memory Data

- Separate Room database instance (`memory.db`)
- Vectors stored as BLOB (serialized FloatArray)
- No external backup of memory data

---

## Network Security

### TLS Configuration

```kotlin
// OkHttp (cloud stack)
OkHttpClient.Builder()
    .certificatePinner(CertificatePinner.Builder()
        .add("api.openai.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build())
    .build()

// Ktor (general stack)
install(HttpClientEngineConfig) {
    sslContext = ...  // Trust anchor validation
}
```

### Header Security

- `X-Accel-Buffering: no` prevents proxy buffering delays in SSE streams
- `Authorization` header contains decrypted API key (only at request time)
- No sensitive data in URL query parameters

---

## Firebase Security

### Authentication

- Google Sign-In uses Credential Manager (secure, Google-managed flow)
- GitHub OAuth uses Firebase OAuth with scoped permissions
- Session tokens managed by Firebase SDK
- No password handling in AndroLLM code

### Data Access

- Firestore rules controlled by project owner
- Profile data is best-effort (never blocks offline use)
- Authentication state is optional (guest mode always available)

---

## Threat Model

### Threat: Malicious Model File

**Scenario:** User downloads a GGUF file from an untrusted source.

**Mitigations:**
1. `GgufValidator` validates header structure before native loading
2. Invalid magic bytes rejected before any native code runs
3. Unknown architectures rejected by `SupportedArchitectures` whitelist
4. The native engine runs in a separate process (no shared memory with attacker code)

**Remaining risk:** Buffer overflow vulnerabilities in llama.cpp itself. Mitigated by keeping the vendored llama.cpp updated to latest upstream.

### Threat: API Key Leakage

**Scenario:** Attacker gains access to device storage.

**Mitigations:**
1. Keys encrypted with Android Keystore — key material never written to disk
2. Even if `app.db` is extracted, encrypted keys are useless without Keystore access
3. Keystore keys are hardware-backed on most modern devices
4. No logging of API keys (Timber filters sensitive fields)

**Remaining risk:** Rooted device with Keystore access. Acceptable trade-off for optional cloud features.

### Threat: Voice Assistant Eavesdropping

**Scenario:** Attacker monitors microphone input.

**Mitigations:**
1. Audio processed entirely on-device (sherpa-onnx ONNX models)
2. No audio transmitted to external servers
3. Foreground notification makes listening visible
4. Barge-in detection allows immediate stop
5. VAD ensures audio isn't continuously recorded when not needed

**Remaining risk:** Malware with microphone access on rooted device. No software solution can prevent this.

### Threat: Man-in-the-Middle Attack

**Scenario:** Attacker intercepts cloud API requests.

**Mitigations:**
1. HTTPS enforcement (TLS 1.2+)
2. Certificate pinning readiness (can be enabled per-provider)
3. API keys encrypted at rest; only decrypted in memory
4. No cleartext traffic allowed

---

## Security Audit Checklist

When reviewing security-sensitive changes:

- [ ] Are secrets ever logged? (Check Timber tags)
- [ ] Are API keys decrypted only at point of use?
- [ ] Is the Keystore key deletion implemented correctly?
- [ ] Are network requests using HTTPS only?
- [ ] Are permissions requested lazily, not at launch?
- [ ] Is user data isolated in the app sandbox?
- [ ] Are native inputs validated before passing to C++?
- [ ] Is there a guest mode that works without authentication?

---

## Security Responsiveness

| Issue Severity | Response Time | Disclosure |
|---|---|---|
| Critical (RCE, key exposure) | 24 hours | Immediate advisory |
| High (auth bypass, data leak) | 72 hours | Coordinated disclosure |
| Medium (weak encryption) | 1 week | Public issue tracker |
| Low (informational) | Next release | Changelog |

Report vulnerabilities via [SECURITY.md](../../SECURITY.md).

---

## Planned Security Enhancements

| Feature | Status | Priority |
|---|---|---|
| Biometric unlock for app | 🚧 Planned | High |
| Encrypted database at rest | 🔮 Future | Medium |
| Certificate pinning for providers | 🚧 Planned | High |
| Secure enclave key transfer | 🔮 Future | Low |
| Zero-knowledge proof of auth | 🔮 Future | Low |
