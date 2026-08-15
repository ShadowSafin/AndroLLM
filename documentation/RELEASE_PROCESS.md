# Release Process

Guide for creating and publishing release builds of AndroLLM.

---

## Pre-Release Checklist

Before cutting a release:

- [ ] All planned features for this version are implemented
- [ ] All critical bugs are fixed
- [ ] Test suite passes: `./gradlew test connectedAndroidTest`
- [ ] Code formatting checked: `./gradlew spotlessCheck`
- [ ] Static analysis clean: `./gradlew detekt`
- [ ] Changelog updated (`CHANGELOG.md`)
- [ ] Version number bumped in `gradle.properties`
- [ ] Firebase `google-services.json` is up to date
- [ ] Release keystore is available and not committed to repo
- [ ] Privacy policy reviewed and updated if needed
- [ ] Model catalog refreshed (if new models added)
- [ ] README updated (if features changed)

---

## Version Numbering

AndroLLM follows [Semantic Versioning](https://semver.org/):

```
MAJOR.MINOR.PATCH
  1    .   2    .    3
```

| Component | When to bump | Example |
|---|---|---|
| MAJOR | Breaking API/schema changes | 1→2 |
| MINOR | New features, backward compatible | 1.2→1.3 |
| PATCH | Bug fixes, no API changes | 1.2.3→1.2.4 |

Current version: **1.0.0** (code: 1)

The version is defined in `gradle.properties`:
```properties
versionName=1.0.0
versionCode=1
```

---

## Building a Release APK

### Step 1: Configure Signing

Add signing config to `gradle.properties` (do NOT commit passwords):

```properties
# Local signing config (DO NOT COMMIT)
ANDROLLM_STORE_FILE=/path/to/keystore.jks
ANDROLLM_STORE_PASSWORD=your_store_password
ANDROLLM_KEY_ALIAS=your_key_alias
ANDROLLM_KEY_PASSWORD=your_key_password
```

### Step 2: Build Release APK

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Step 3: Build Bundle (for Play Store)

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

---

## Signing Requirements

### Keystore Best Practices

1. **Never commit the keystore** to version control
2. Use a strong password (16+ characters, mixed case, numbers, symbols)
3. Store the keystore in a secure location (password manager, hardware token)
4. Back up the keystore — losing it means you can't update installed apps
5. Use different keystores for debug and release

### Debug vs Release

| Build Type | Signing | Minify | Use Case |
|---|---|---|---|
| `debug` | Auto-signed with debug keystore | No | Development, testing |
| `release` | Signed with release keystore | No (currently) | Distribution |

---

## Testing Before Release

### Manual Test Matrix

| Platform | Test |
|---|---|
| Physical device (arm64) | Full feature test |
| Emulator (arm64 image) | Core flows, engine fallback |
| Tablet/foldable | Adaptive navigation |
| Low-RAM device (4GB) | Model loading, memory pressure |
| No-GPU-delegate device | CPU fallback |
| No-network device | Offline mode |

### Key Flows to Test

1. **First run**: Splash → Onboarding → Auth → Home
2. **Local chat**: Load model → Send message → Receive response → Continue conversation
3. **Cloud chat**: Configure provider → Switch to cloud → Send message
4. **Voice assistant**: Enable → Wake word → Speak → Response → Barge-in
5. **Memory**: Enable memory → Have conversation → Check memories → Retrieve
6. **Model management**: Download from catalog → Install → Load → Unload → Delete
7. **Settings**: Change theme → Change language → Export logs → Reset

---

## Publishing

### Google Play Store

1. Upload `app-release.aab` to Play Console
2. Fill in release notes (from CHANGELOG.md)
3. Set release track (Internal → Closed → Production)
4. Complete content rating questionnaire
5. Submit for review

### Direct Distribution (APK)

1. Sign the APK with your keystore
2. Upload to Firebase App Distribution or similar
3. Share download link with testers
4. Include changelog in distribution note

---

## Post-Release

1. Create GitHub release with changelog
2. Tag the commit: `git tag -a v1.2.0 -m "Version 1.2.0"`
3. Push tag: `git push origin v1.2.0`
4. Update `CHANGELOG.md` with `[Unreleased]` → next version header
5. Monitor crash reports (when Crashlytics is integrated)
6. Respond to user feedback within 48 hours

---

## Hotfix Process

For critical bug fixes:

1. Create `hotfix/issue-description` branch from `main`
2. Apply fix
3. Run full test suite
4. Build release APK
5. Distribute hotfix APK to affected users
6. Merge to `main` with appropriate version bump
7. Document in CHANGELOG under "Hotfix" section

---

## Known Limitations

| Limitation | Impact | Mitigation |
|---|---|---|
| R8/minification disabled | Larger APK size (~40MB vs ~25MB) | Intentional for development velocity |
| No CI/CD | Manual build process | Documented in BUILDING.md |
| Single ABI (arm64-v8a) | No x86_64 build exists | Emulator testing requires an arm64 emulator image |
| No Play Store listing | Direct distribution only | Community-driven distribution |

---

## Planned Release Improvements

| Feature | Status | Notes |
|---|---|---|
| GitHub Actions build pipeline | 🚧 Planned | Automated debug builds on PR |
| Play Store listing | 🔮 Future | Requires brand asset preparation |
| Automated crash reporting | 🚧 Planned | Firebase Crashlytics integration |
| Multi-ABI support (x86_64) | 🔮 Future | Requires arm64+x86_64 LiteRT-LM packaging |
| Automated changelog generation | 🔮 Future | Conventional commits → changelog |
