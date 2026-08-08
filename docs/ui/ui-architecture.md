# UI Architecture Guide

Architecture and design system documentation for AndroLLM's user interface.

---

## Design System: The Parchment Ledger

AndroLLM uses a custom design system called **"The Parchment Ledger"**, defined in [`DESIGN.md`](../../DESIGN.md). It evokes a warm daylight desk aesthetic — every conversation is a letter kept in ink on parchment, every action is a terracotta stamp.

### Core Principles

- **Calm and focused**: No neon, no glassmorphism, no gradient chrome
- **Warm editorial**: Serif headlines, system sans body, monospace metadata
- **One accent color**: Terracotta `#D97757` for primary actions
- **Generous spacing**: 8dp baseline grid with breathing margins
- **Handcrafted feel**: Soft shadows, paper-like surfaces, subtle textures

---

## Color Tokens

### Light Theme

| Token | Hex | Usage |
|---|---|---|
| `canvas` | `#F5F4ED` | Background |
| `raised` | `#ECEBE3` | Elevated surfaces |
| `deep` | `#EFEEE6` | Card backgrounds |
| `surface` | `#FBFAF4` | Dialogs, sheets |
| `ink` | `#141413` | Primary text |
| `muted` | `#5E5D59` | Secondary text |
| `faint` | `#8F8D87` | Tertiary text |
| `accent` | `#D97757` | Primary buttons, highlights |
| `accentLight` | `#E69D81` | Accent variants |
| `accentDeep` | `#B3573E` | Deep accent |
| `border` | `#E8E6DC` | Card borders |
| `success` | `#52C41A` | Success states |
| `warning` | `#E0A33D` | Warning states |
| `error` | `#C7442F` | Error states |

### Dark Theme

| Token | Hex | Usage |
|---|---|---|
| `canvas` | `#141414` | Background |
| `surface` | `#272727` | Cards, dialogs |
| `ink` | `#DCDCDC` | Primary text |
| `muted` | `#9B9B9B` | Secondary text |
| `accent` | `#C78871` | Primary buttons |

---

## Typography

| Style | Font Family | Weight | Size Range | Usage |
|---|---|---|---|---|
| Headline | `FontFamily.Serif` | Semi-bold / Bold | 24–48sp | Screen titles, section headers |
| Body | System sans | Regular | 14–16sp | Body text, messages |
| Caption | System sans | Regular | 12sp | Metadata, timestamps |
| Ledger | Monospace | Regular | 11–12sp | Model info, tokens, benchmarks |

---

## Shapes and Geometry

| Element | Shape | Corner Radius | Elevation |
|---|---|---|---|
| Index Card (primary container) | `RoundedCornerShape(16.dp)` | 16dp | 1dp cream border + soft shadow |
| Paper Slip (chat bubble) | `RoundedCornerShape(10.dp)` | 10dp | Subtle |
| Button (terracotta stamp) | `RoundedCornerShape(32.dp)` | 32dp (capsule) | Spring press animation |
| Chip | `RoundedCornerShape(8.dp)` | 8dp | Minimal |

---

## Navigation Architecture

### Adaptive Navigation

**File:** [`core/ui/src/main/java/io/androllm/core/ui/components/CloudAdaptiveNavigation.kt`](../../core/ui/src/main/java/io/androllm/core/ui/components/CloudAdaptiveNavigation.kt)

Uses `WindowSizeClass.calculateWindowSizeClass()` to determine navigation style:

| Window Size | Navigation Style | Components |
|---|---|---|
| Compact (phone) | Bottom navigation bar | `CloudBottomNavigationBar` |
| Medium (foldable/open phone) | Bottom navigation bar | `CloudBottomNavigationBar` |
| Expanded (tablet) | Floating navigation rail | `CloudNavigationRail` |

### Route Registry

See [PROJECT_STRUCTURE.md](../../PROJECT_STRUCTURE.md) for the complete route list and navigation graph.

---

## Component Library

### Shared Composables (`core:ui`)

| Component | Description | Parameters |
|---|---|---|
| `CloudGlassCard` | Parchment-styled card with press animation | content: @Composable |
| `CloudCapsuleButton` | Terracotta capsule button with spring compression | onClick, label, enabled |
| `BrandButton` | Primary action button (same as CloudCapsuleButton) | onClick, label |
| `CloudChip` | Small capsule label | label, selected, onClick |
| `SectionHeader` | Serif title with small-caps subtitle | title, subtitle |
| `EmptyState` | Empty desk/blank paper illustration | icon, title, subtitle, action |
| `LoadingIndicator` | Centered amber ring | — |
| `GradientBackground` | Atmospheric gradient background | — |
| `CloudAtmosphericBackground` | Subtle atmospheric effect | — |
| `CloudBugdroidLogo` | App logo component | size |
| `ModelWalletCard` | Model info card widget | model, onSelect, onUnload |
| `DebugOverlay` | Visual debug overlay | metrics |
| `PromptStudioCarousel` | Prompt library carousel | prompts, onSelect |

### Chat Components (`feature:chat`)

| Component | Description |
|---|---|
| `ChatScreen` | Main chat screen with drawer, messages, input |
| `MessageCard` | Individual message bubble with markdown rendering |
| `ComposeInputArea` | Text input with send/cancel buttons |
| `TypingAndThinkingIndicator` | Animated loading state |
| `GenerationStatsPanel` | Tokens/sec, latency, model info |
| `ModelParameterSheet` | Sampler settings dialog (temperature, top-p, etc.) |
| `SearchOverlay` | Conversation text search |
| `SmartReplyChips` | Follow-up suggestion chips |
| `MarkdownRenderer` | Markdown → AnnotatedString converter |
| `CodeBlockCard` | Syntax-highlighted code block |
| `ConversationDrawer` | Slide-out conversation list |

---

## Theming

### Theme Application

**File:** [`app/src/main/java/io/androllm/app/MainActivity.kt`](../../app/src/main/java/io/androllm/app/MainActivity.kt)

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply theme based on DataStore preference
        setTheme(when (themeMode) {
            ThemeMode.SYSTEM -> R.style.Theme_AndroLLM
            ThemeMode.LIGHT -> R.style.Theme_AndroLLM_Light
            ThemeMode.DARK -> R.style.Theme_AndroLLM_Dark
        })
        setContent {
            AndroLLMTheme {
                AppNavHost(preferencesDataStore)
            }
        }
    }
}
```

### Reduce Motion

Respects the system's reduce motion setting:
```kotlin
val isReducedMotion = LocalAccessibilityManager.current.isReduceMotionEnabled
// Disable spring animations, waveform effects when true
```

---

## Screen Architecture

### Home Screen (`feature:home`)

Layout:
1. Status card (model loaded / cloud connected)
2. Quick actions (new chat, models, voice)
3. Recent conversations list
4. Storage indicator

### Chat Screen (`feature:chat`)

Layout:
1. Top app bar (model status, menu)
2. Message list (LazyColumn, auto-scroll)
3. Input area (ComposeInputArea)
4. Generation stats (collapsible panel)
5. Conversation drawer (ModalNavigationDrawer)

Streaming behavior:
- Tokens are throttled to ~60fps (16ms delay between emissions)
- `remember(msg.id)` prevents re-composition of unchanged messages
- `rememberUpdatedState` keeps callbacks fresh without recreating closures
- Auto-scroll follows new tokens with `animateScrollToItem`

### Models Screen (`feature:models`)

Tabs:
1. **Installed** — Load/unload, set default, favorite, benchmark, delete
2. **Downloads** — Progress, pause/resume/cancel per download
3. **Catalog** — Official models with filters, sorting, recommendations
4. **HuggingFace** — Remote search and download
5. **Diagnostics** — Hardware info (CPU, GPU, RAM)

### Settings Screen (`feature:settings`)

Sections:
1. User profile header (avatar, name, email, guest badge)
2. Statistics row (downloaded count, storage, backend)
3. Firebase auth card (sign in/manage)
4. Appearance (theme, reduce motion)
5. Storage (path, free space, clear cache)
6. On-device memory (toggle, threshold, retrieval count, summarization)
7. Voice assistant (toggle, sensitivity, battery saver, overlay)
8. Cloud providers (link to management screen)
9. Developer options (toggle)
10. Logs & diagnostics (export, preview)
11. About & privacy (version, privacy guarantee)

---

## State Management

### ViewModel Pattern

All screens use Hilt-managed ViewModels:

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engineRepository: EngineRepository,
    private val memoryManager: MemoryManager,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository
) : BaseViewModel() {
    private val _state = MutableStateFlow<UiState<ChatData>>(UiState.Loading)
    val state: StateFlow<UiState<ChatData>> = _state.asStateFlow()
    
    // ...
}
```

### UiState Sealed Interface

```kotlin
sealed interface UiState<out T> {
    object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
    object Empty : UiState<Nothing>
}
```

### combine() for Multi-Flow State

Complex screens merge multiple flows using `combine()`:

```kotlin
// ModelsViewModel merges 5+ flows into one UiState
val combined = combine(
    installedModels,
    catalogState,
    remoteModels,
    storageStats,
    hardwareInfo,
    benchmarkResults
) { installed, catalog, remote, storage, hardware, benchmark ->
    UiState.Success(ModelsData(installed, catalog, remote, storage, hardware, benchmark))
}
```

---

## Accessibility

| Feature | Implementation |
|---|---|
| Content descriptions | All icons and buttons have `contentDescription` |
| Touch targets | Minimum 48dp × 48dp |
| Color contrast | All text meets WCAG AA (4.5:1) |
| Reduce motion | Respects system setting |
| Screen reader | Full TalkBack support via semantic roles |
| Dynamic type | Supports system font scaling (limited) |

---

## Responsive Design

### Phone (Compact)
- Single column layout
- Bottom navigation bar
- Full-width chat messages
- Drawer slides from left

### Tablet (Medium/Expanded)
- Navigation rail on left
- Split pane: conversation list + chat
- Wider message bubbles
- Persistent sidebar for model catalog

Detection:
```kotlin
val windowSizeClass = WindowSizeClass.calculateFromWindow(windowManager)
when (windowSizeClass.widthSizeClass) {
    WidthSizeClass.Compact -> BottomNavBar
    WidthSizeClass.Medium, WidthSizeClass.Expanded -> NavigationRail
}
```

---

## Animation

| Animation | Type | Duration | Trigger |
|---|---|---|---|
| Card press | Spring compression | 300ms | Touch down on CloudGlassCard |
| Card float | Soft elevation | 200ms | Touch on card |
| Token appear | Fade + slide | 16ms per token | Streaming generation |
| Drawer slide | Linear | 300ms | Menu button tap |
| Waveform bars | Canvas animation | 60fps | Voice overlay active |
| Splash fade | Alpha | 500ms | App launch |

All animations respect the reduce motion setting.
