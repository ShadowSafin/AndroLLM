# Getting Started with AndroLLM

Quick start guide for new users.

---

## Installation

### From Source

```bash
# Prerequisites: Android Studio, JDK 17, NDK r26, Vulkan SDK
git clone https://github.com/your-org/androllm.git
cd androllm
./gradlew assembleDebug
```

Install the APK on your device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### System Requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| Android version | 9 (API 28) | 14 (API 34) |
| Architecture | arm64-v8a | arm64-v8a |
| RAM | 4 GB | 8 GB+ |
| Storage | 500 MB free | 2 GB free |
| Vulkan | Optional (CPU fallback) | Supported |

---

## First Run

### 1. Install and Launch

Open the app. You'll see the splash screen followed by the onboarding flow.

### 2. Sign In (Optional)

You can use the app as a guest, or sign in with:
- **Google**: Tap Google Sign-In, select your account
- **GitHub**: Tap GitHub Sign-In, authorize the app

Guest mode gives you full access to local features. Authentication enables cloud sync.

### 3. Complete Profile Setup

If this is your first sign-in, set your display name and optional bio. This data is stored in Firebase Firestore.

### 4. Load a Model

1. Go to the Models screen (bottom navigation)
2. Tap the **Catalog** tab
3. Browse recommended models based on your device's RAM
4. Tap a model → Download
5. Once downloaded, tap **Load**

**Recommended starting models:**
- Qwen2.5-1.5B-Instruct-Q4_K_M (small phones, 2-4GB RAM)
- Gemma-2-2B-Q4_K_M (mid-range, 4-6GB RAM)
- Qwen2.5-7B-Q5_K_M (flagship, 8GB+ RAM)

### 5. Start Chatting

1. Go to the Chat screen (default home screen)
2. Type a message and tap Send
3. Watch tokens stream in real-time
4. Try different models from the model selector in the chat

### 6. Configure Memory (Optional)

1. Go to Settings → On-device Memory
2. Toggle memory on
3. Adjust similarity threshold and retrieval count
4. Have a conversation — memories are extracted automatically

### 7. Try the Voice Assistant (Optional)

1. Go to Settings → Voice Assistant
2. Toggle on
3. Grant microphone permission when prompted
4. Say **"Hey Andro"** to activate
5. Ask a question — the app will respond with text and speech

---

## Basic Usage

### Sending a Message

Type in the input field and press Send (or tap the send button).

### Switching Models

Tap the model name in the top bar → select from installed models or catalog.

### Starting a New Conversation

Tap the drawer icon (top left) → New Conversation.

### Searching Conversations

Tap the search icon in the chat drawer → type to filter.

### Exporting a Conversation

Open the conversation drawer → tap the three-dot menu → Export.

---

## Key Settings

| Setting | Location | Description |
|---|---|---|
| Theme | Settings → Appearance | Light, Dark, or System |
| Context length | Models → Model Parameters | Token context window size |
| Temperature | Models → Model Parameters | Randomness (0.0–2.0) |
| Memory threshold | Settings → Memory | Similarity cutoff for retrieval |
| Voice sensitivity | Settings → Voice | Wake word detection sensitivity |
| Battery saver | Settings → Voice | Single-threaded voice mode |

---

## Troubleshooting

| Issue | Quick Fix |
|---|---|
| Model won't load | Check RAM requirements; try a smaller model |
| Voice not responding | Check mic permission; speak louder/closer |
| Cloud provider errors | Verify API key; check internet connection |
| App crashes on startup | Clear app data: Settings → Apps → AndroLLM → Clear Data |
| Slow generation | Enable Vulkan (check Developer diagnostics); use smaller model |

For detailed troubleshooting, see [TROUBLESHOOTING.md](../../TROUBLESHOOTING.md).

---

## Next Steps

- Read [ARCHITECTURE.md](../../ARCHITECTURE.md) to understand how the app works
- Check [MODEL_SUPPORT.md](../../MODEL_SUPPORT.md) for model recommendations
- Visit [FAQ.md](../../FAQ.md) for common questions
- Join the community: [GitHub Discussions](https://github.com/your-org/androllm/discussions)
