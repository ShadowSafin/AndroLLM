# AndroLLM - Phase 1

Private AI. Offline. On your device.

Run powerful AI models locally on Android.

A production-grade Android application for running local LLMs offline, built with modern Android architecture.

## Project Structure

```
AndroLLM/
├── app/                    # Main application module
├── core/                   # Core shared modules
│   ├── common/            # Base classes, utilities, Result/UiState
│   ├── ui/                # Compose theme, typography, shapes, components
│   ├── database/          # Room database, DAOs, repositories
│   ├── datastore/         # DataStore preferences
│   ├── navigation/        # Navigation routes and graph
│   ├── models/            # Data models (Conversation, Message, Model, etc.)
│   ├── network/           # Ktor client setup (prepared for future use)
│   └── utils/             # Permission helpers, storage/device utilities
├── feature/               # Feature modules
│   ├── home/             # Home screen with status, quick actions, recent chats
│   ├── chat/             # Chat screen placeholder
│   ├── models/           # Models management screen
│   ├── settings/         # Settings screen (theme, language, storage, developer)
│   └── splash/           # Animated splash screen
├── engine/               # LLM inference engine (Phase 2)
└── docs/                 # Documentation
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose, Material Design 3
- **Architecture**: MVVM + Clean Architecture + Repository Pattern
- **DI**: Hilt
- **Navigation**: Navigation Compose
- **Async**: Kotlin Coroutines + Flow
- **Database**: Room
- **Preferences**: DataStore
- **Logging**: Timber
- **Networking**: Ktor Client (prepared)
- **Image Loading**: Coil (prepared)
- **Serialization**: kotlinx.serialization
- **Build**: Gradle Kotlin DSL with Version Catalog

## Features (Phase 1)

### Core Architecture
- Modular multi-module architecture
- Clean Architecture with clear layer separation
- Repository pattern with interfaces
- Hilt dependency injection
- Immutable UI state with StateFlow

### UI/UX
- Premium dark-first design
- Material 3 theming
- Custom colors: Primary #4F8CFF, Accent #6EA8FE
- Rounded cards (20dp), buttons (16dp)
- Smooth animations

### Screens
1. **Splash** - Animated logo with fade-in, auto-navigation
2. **Home** - Status card, quick actions, recent chats, model status
3. **Chat** - Placeholder with message bubbles, input field, disabled send
4. **Models** - Empty state with Import/Download buttons, storage info
5. **Settings** - Dark mode, theme, language, storage path, developer mode, about

### Persistence
- Room database with entities: Conversation, Message, Model, Settings
- DataStore for preferences: theme, language, developer mode, storage path

### Developer Experience
- Kotlin coding conventions
- No deprecated APIs
- Comprehensive documentation
- Extension points for Phase 2 (LLM engine)

## Building

Requires:
- Android Studio Iguana+ or command line
- JDK 17
- Android SDK 34+

```bash
./gradlew assembleDebug
```

## Phase 2 Preparation

The architecture is designed for seamless LLM engine integration:
- `engine/` module ready for inference engine
- Model entity supports GGUF/GGML/SafeTensors formats
- Download status tracking for model management
- Repository interfaces ready for engine callbacks
- Network module prepared for model downloads

## License

Proprietary - All rights reserved
