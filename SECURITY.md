# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| Latest release | ✅ Yes        |
| Older releases   | ❌ No         |

Only the most recent release receives security updates. Users should keep their
apps updated by downloading new releases from the official repository.

---

## Reporting a Vulnerability

We take the security of AndroLLM seriously. If you discover a security
vulnerability, please report it responsibly.

**Do NOT create a public GitHub issue for security vulnerabilities.**

Instead, report via:

1. **GitHub Security Advisories**: Use the "Report a vulnerability" button on
   the [AndroLLM repository](https://github.com/your-org/androllm/security)
2. **Email**: Send details to the maintainers at the email listed in the
   repository's `package.json` or commit history

### What to Include

When reporting a vulnerability, please include:

- A clear description of the vulnerability
- Steps to reproduce the issue
- The impact (what an attacker could achieve)
- Your preferred contact method for follow-up questions
- Allow 72 hours for an initial response before following up

### What to Expect

- **Acknowledgment**: We will acknowledge receipt of your report within 48 hours
- **Assessment**: We will evaluate the severity and provide a timeline for a fix
- **Fix**: We will work to resolve the issue and publish a security advisory
- **Credit**: We will credit reporters in the advisory (unless they prefer anonymity)

---

## Security Architecture

### API Key Storage

All cloud provider API keys are encrypted using **Android Keystore-backed AES-256/GCM**.
The encryption key never leaves the secure hardware enclave where available.

- Implementation: [`core/cloud/security/KeyCipher.kt`](core/cloud/src/main/java/io/androllm/core/cloud/security/KeyCipher.kt)
- The raw plaintext key is never persisted to disk or shared preferences
- In memory, keys are only decrypted at the point of use and immediately cleared

### Authentication

- Firebase Authentication is used for optional user sign-in
- Google Sign-In uses Credential Manager with `GetGoogleIdOption`
- GitHub Sign-In uses Firebase OAuth with scoped permissions (`read:user`, `user:email`)
- Sessions persist across app restarts via Firebase Auth state listeners
- Guest mode is always available without authentication

### Local Data Protection

- Room database files are stored in the app's private sandbox (`/data/data/io.androllm.app/`)
- Model files (GGUF) are stored in the app's internal storage directory
- Memory embeddings are stored in a separate Room database instance in the same sandbox
- No data is encrypted at rest beyond the Android sandbox boundary (user-controlled feature placeholder)

### Network Security

- Cloud provider requests use HTTPS exclusively; HTTP is rejected
- OkHttp client enforces TLS 1.2+ with certificate pinning readiness
- The `android:usesCleartextTraffic="false"` default applies
- Streaming responses use SSE with `X-Accel-Buffering: no` to prevent proxy buffering delays

### Dependency Security

The project uses the following dependency categories tracked for security:

| Category | Libraries | Notes |
|---|---|---|
| Android SDK | AGP 8.6.0, Kotlin 2.1.20 | Updated regularly via Dependabot |
| Firebase | BoM 34.12.0 | Google-maintained, auto-updated |
| Networking | Ktor 3.0.3, OkHttp 4.12.0 | Both receive regular security patches |
| Native | llama.cpp (vendored upstream) | Track upstream commits for CVEs |
| Voice | sherpa-onnx 1.13.4 | ONNX Runtime Mobile — track k2-fsa releases |

---

## Known Security Considerations

### Release Keystore

⚠️ The project currently has a release keystore file (`androllm-release.jks`) committed to
the repository. **This must be removed from the repository and added to `.gitignore`
before any public release.**

Recommended steps:
1. Add `*.jks` and `*.keystore` to `.gitignore`
2. Move the keystore to a secure location outside the repo
3. Reference it via `local.properties` or environment variables for builds
4. Rotate the keystore if any commits containing it are public

### Model Files

GGUF model files can be downloaded from untrusted sources (HuggingFace, direct URLs).
The app validates GGUF headers via [`GgufValidator.kt`](engine/src/main/java/io/androllm/engine/utils/GgufValidator.kt)
but does not perform full model integrity checks. Users should:

- Only download models from trusted sources
- Verify SHA-256 checksums when provided by the model author
- Be aware that malicious GGUF files could trigger buffer overflows in the C++ engine

### Microphone Access

The voice assistant requires `RECORD_AUDIO` and runs as a foreground service. This is
legitimate functionality but users should be aware:

- The service runs continuously while active (visible notification)
- Barge-in uses energy-based VAD, not cloud processing
- No audio is transmitted to any server during local voice operation

---

## Responsible Disclosure Timeline

| Stage | Target Response Time |
|---|---|
| Acknowledge receipt | 48 hours |
| Confirm vulnerability | 5 business days |
| Provide fix or mitigation | 30 business days |
| Public disclosure | After fix is available |

If the vulnerability affects upstream dependencies (llama.cpp, sherpa-onnx, etc.),
we will also coordinate disclosure with those projects.

---

## References

- [Google Android Security Documentation](https://developer.android.com/privacy-and-security)
- [Firebase Security Guidelines](https://firebase.google.com/docs/security)
- [OWASP Android Security Checklist](https://cheatsheetseries.owasp.org/cheatsheets/Android_Security_Chart.html)
- [Cryptography Best Practices for Android](https://developer.android.com/topic/security/crypto)
