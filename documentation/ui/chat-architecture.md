# Chat Architecture Guide

Detailed architecture of the chat feature — message flow, streaming, and UI components.

---

## Module Structure

**Module:** `feature:chat`  
**Namespace:** `io.androllm.feature.chat`

```
feature/chat/src/main/java/io/androllm/feature/chat/
├── ChatScreen.kt              # Main composable
├── ChatViewModel.kt           # State management, generation orchestration
├── export/
│   ├── ConversationExporter.kt # JSON/Text export utilities
│   └── ConversationSharer.kt   # Android share sheet integration
└── ui/
    ├── components/
    │   ├── ComposeInputArea.kt    # Text input with send/cancel
    │   ├── GenerationStatsPanel.kt # Tokens/sec, model info
    │   ├── MessageBubble.kt       # Message avatar + content
    │   ├── MessageCard.kt         # Full message card (bubble + meta)
    │   ├── EmptyStateComponents.kt # Empty chat states
    │   ├── ModelParameterSheet.kt # Sampler settings dialog
    │   ├── SearchOverlay.kt       # Conversation text search
    │   └── TypingAndThinkingIndicator.kt # Loading state
    ├── drawer/
    │   └── ConversationDrawer.kt  # Sidebar with conversation list
    └── markdown/
        ├── CodeBlockCard.kt       # Syntax-highlighted code blocks
        └── MarkdownRenderer.kt    # Markdown → AnnotatedString
```

---

## Data Flow

### Sending a Message

```
User types text in ComposeInputArea
         │
         ▼
ChatViewModel.sendMessage(text: String)
         │
         ├── 1. Create Conversation if needed
         │       conversationRepository.upsert(conversation)
         │
         ├── 2. Create User Message
         │       messageRepository.upsert(Message(role=USER, content=text))
         │
         ├── 3. Add Pending Assistant Message
         │       messageRepository.upsert(Message(role=ASSISTANT, content="", isPending=true))
         │
         ├── 4. Build Chat Context
         │       memoryManager.buildContext(text, filters, conversationId, topK=5)
         │       → injects relevant memories into system prompt
         │
         ├── 5. Route to Inference
         │       if (cloudGateway.isConfigured() && cloudEnabled) {
         │           cloudGateway.streamChat(messages, config) → Flow<CloudStreamEvent>
         │       } else {
         │           engineRepository.generateChatStream(messages, config) → Flow<StreamChunk>
         │       }
         │
         └── 6. Collect Streaming Response
                 _messages.add(StreamChunk(delta, finished))
                 if (finished) {
                     messageRepository.upsert(finalMessage)  // Persist to Room
                     memoryManager.processExchange(exchange) // Async, 2s delay
                 }
```

### Streaming Architecture

Token streaming is throttled to ~60fps to prevent O(n²) string copying in Compose:

```kotlin
// In ChatViewModel
private fun collectStream(flow: Flow<Result<StreamChunk>>) {
    viewModelScope.launch {
        flow.collect { result ->
            when (result) {
                is Result.Success -> {
                    val chunk = result.data
                    _currentTokenBuffer.append(chunk.delta)
                    
                    if (chunk.finished) {
                        // Finalize message
                        finalizeMessage(_currentTokenBuffer.toString())
                        _currentTokenBuffer.clear()
                    }
                }
                is Result.Failure -> handleError(result.error)
            }
        }
    }
}

// Throttled UI emission (~60fps)
private fun emitToUi() {
    kotlinx.coroutines.delay(16L)  // ~60fps
    _messages.value = currentMessages
}
```

### Message Ordering

The UI uses `remember(msg.id)` for stable per-item callbacks. This prevents the entire message list from recomposing when a new token arrives — only the typing indicator or last message updates.

---

## ChatViewModel

**File:** [`feature/chat/src/main/java/io/androllm/feature/chat/ChatViewModel.kt`](../../feature/chat/src/main/java/io/androllm/feature/chat/ChatViewModel.kt)

### State

```kotlin
data class ChatData(
    val conversation: Conversation?,
    val messages: List<Message>,
    val model: Model?,
    val isGenerating: Boolean,
    val error: String?,
    val searchQuery: String,
    val drawerOpen: Boolean
)

sealed interface UiState<out T> {
    object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
```

### Key Methods

| Method | Purpose |
|---|---|
| `sendMessage(text: String)` | Send user message, start generation |
| `cancelGeneration()` | Stop current generation |
| `regenerate(lastAssistantIndex: Int)` | Regenerate from a specific message |
| `editMessage(index: Int, newText: String)` | Edit a user message and regenerate |
| `deleteMessage(index: Int)` | Remove a message |
| `bookmarkMessage(index: Int)` | Toggle bookmark |
| `search(query: String)` | Filter messages by text |
| `loadConversation(id: String)` | Load existing conversation |
| `createNewConversation()` | Start fresh conversation |
| `selectModel(model: Model)` | Change active model |
| `updateSamplerParams(params: SamplerParams)` | Update generation parameters |

---

## Markdown Rendering

**File:** [`feature/chat/src/main/java/io/androllm/feature/chat/ui/markdown/MarkdownRenderer.kt`](../../feature/chat/src/main/java/io/androllm/feature/chat/ui/markdown/MarkdownRenderer.kt)

Converts markdown to Android `AnnotatedString` for Compose text display:

```kotlin
fun markdownToAnnotatedString(markdown: String): AnnotatedString {
    // Parsing order:
    // 1. Code blocks (```...```) → CodeBlockCard with syntax highlighting
    // 2. Inline code (`...`) → monospace styling
    // 3. Bold (**...** or __...__)
    // 4. Italic (*...* or _..._)
    // 5. Strikethrough (~~...~~)
    // 6. Links ([text](url)) → clickable spans
    // 7. Headers (# ## ###)
    // 8. Lists (-, *, 1.)
    // 9. Blockquotes (>)
    // 10. Horizontal rules (---)
}
```

### Code Block Rendering

Code blocks are rendered as specialized `CodeBlockCard` composables with:
- Syntax highlighting (language-specific tokenizer)
- Copy button
- Language label
- Collapsible overflow for very long blocks

---

## Message Types

### Roles

| Role | Origin | Styling |
|---|---|---|
| `USER` | Typed by user / Voice input | Aligned right, terracotta accent |
| `ASSISTANT` | Generated by LLM | Aligned left, parchment styled |
| `SYSTEM` | System prompt (not shown) | Invisible in UI |

### Origins

| Origin | Description |
|---|---|
| `TYPED` | User typed the message |
| `VOICE` | User spoke the message (voice assistant) |
| `AUTOMATION` | System-generated (e.g., command response) |

---

## Conversation Drawer

**File:** [`feature/chat/src/main/java/io/androllm/feature/chat/ui/drawer/ConversationDrawer.kt`](../../feature/chat/src/main/java/io/androllm/feature/chat/ui/drawer/ConversationDrawer.kt)

Features:
- Lists all conversations (pinned first, then by recency)
- Shows last message preview and timestamp
- Pin/unpin conversations
- Archive conversations
- Delete conversations
- Search within conversation titles
- New conversation button

Interaction:
- Opens as a `ModalNavigationDrawer` from the left
- Backdrop dismisses the drawer
- Selecting a conversation navigates to it (or loads it in the current screen)

---

## Export and Share

**Exporters:**

| Format | File | Description |
|---|---|---|
| JSON | `ConversationExporter` | Full conversation as JSON (messages, model, timestamp) |
| Text | `ConversationExporter` | Plain text transcript |
| Markdown | `ConversationExporter` | Markdown-formatted transcript |

**Sharer:**
- Uses Android's `ShareCompat` to open the system share sheet
- Shares the selected format
- Works for both local and cloud-generated conversations

---

## Model Parameter Sheet

**File:** [`feature/chat/src/main/java/io/androllm/feature/chat/ui/components/ModelParameterSheet.kt`](../../feature/chat/src/main/java/io/androllm/feature/chat/ui/components/ModelParameterSheet.kt)

Allows adjusting generation parameters per-conversation:

| Parameter | Range | Default | Description |
|---|---|---|---|
| Temperature | 0.0 – 2.0 | 0.8 | Randomness of sampling |
| Top-K | 1 – 100 | 40 | Number of highest-probability tokens to consider |
| Top-P | 0.0 – 1.0 | 0.9 | Cumulative probability threshold |
| Min-P | 0.0 – 1.0 | 0.0 | Minimum probability threshold |
| Typical-P | 0.0 – 2.0 | 1.0 | Locally typical sampling parameter |
| DRY Penalty | 0.0 – 10.0 | 0.0 | Repetition penalty (DRY algorithm) |
| DRY Base | 1.0 – 10.0 | 1.75 | DRY penalty base |
| DRY Allowed Length | 0 – 1000 | 2 | DRY penalty span |
| Seed | -1 – 2^31-1 | -1 | Reproducibility seed (-1 = random) |
| Grammar | — | — | EBNF grammar for constrained decoding |

Changes are applied immediately to the next generation.

---

## Generation Stats Panel

**File:** [`feature/chat/src/main/java/io/androllm/feature/chat/ui/components/GenerationStatsPanel.kt`](../../feature/chat/src/main/java/io/androllm/feature/chat/ui/components/GenerationStatsPanel.kt)

Shows real-time generation statistics:
- Tokens per second
- Time to first token
- Total tokens generated
- Model name and quantization
- Backend (CPU/GPU)
- Context length used
- Memory stats

Toggle visibility with a long-press on the message input area (developer feature).

---

## Empty States

**File:** [`feature/chat/src/main/java/io/androllm/feature/chat/ui/components/EmptyStateComponents.kt`](../../feature/chat/src/main/java/io/androllm/feature/chat/ui/components/EmptyStateComponents.kt)

| State | Content |
|---|---|
| No conversations | "Start your first conversation" + New Chat button |
| No model loaded | "Load a model to start chatting" + Go to Models button |
| No cloud provider | "Connect a cloud provider" + Go to Settings button |
| Generation error | Error message with Retry button |
| Search no results | "No messages match your search" |

---

## Smart Reply Chips

Follow-up suggestion chips appear after each assistant response:
- Generated by the model itself (via a special prompt)
- Shown as pill-shaped buttons below the response
- Tapping a chip sends it as the next message
- Hidden when the model doesn't provide suggestions or in voice-only mode

---

## Chat Attachments (Cloud Only)

Attachments (files, images, camera shots, screenshots) are a **cloud-only**
capability, gated by `ProviderCapabilities.supportsAttachments(modelId)` — a
capability flag, never a provider-name check:

- **Cloud models** (`supportsAttachments = true`): the paperclip appears in the
  composer; files are parsed/OCR'd on-device and the extracted content (or
  native image parts for vision models) rides with the prompt.
- **Local models** (`supportsAttachments = false`): the paperclip is removed
  entirely (no gap — the composer row simply lacks it), no parsing/OCR runs,
  and a request carrying attachments is **rejected** at the ViewModel with
  "Attachments are not supported for local models." Attachment content is
  never injected into a local inference prompt.
- **Switching cloud → local with pending attachments** shows a confirmation
  dialog ("switching will remove the current attachments"); confirming clears
  the chips and the conversation's temporary cache before switching.
- **Old conversations** opened on a local model keep their attachment cards
  visible but non-interactive, with a subtle notice: "This conversation
  contains cloud-only attachments. Switch to a cloud model to use them."
- **Settings → Chat Attachments** is hidden for local models (OCR, image
  upload and cache settings are cloud-only).

---

## Keyboard Handling

The chat screen uses `android:windowSoftInputMode="adjustResize"` in the manifest. This ensures:
- The keyboard pushes the UI up rather than covering it
- The message input area remains visible
- The message list scrolls to show the latest message
- Auto-scroll after sending respects the keyboard height

---

## Testing

**Unit tests:**
- `ChatViewModelTest` — Message sending, state transitions, error handling
- `ChatViewModelStabilizationTest` — Prevents unnecessary recomposition
- `ConversationExporterTest` — Export format correctness

**UI tests:**
- `ChatScreenUiTest` — Compose UI interactions (typing, sending, scrolling)

See [TESTING.md](../TESTING.md) for test conventions.
