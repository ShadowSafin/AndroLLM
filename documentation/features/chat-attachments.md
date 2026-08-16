# Chat Attachments

AndroLLM supports **temporary, conversation-scoped file attachments**, inspired
by ChatGPT, Claude and Gemini. Attach files to a message, ask a question, and
the AI analyzes them *within that conversation* — nothing is permanently
indexed, and nothing is stored in a searchable library.

- Attachments belong **only to the active conversation**.
- Files are processed **temporarily** — parsed and extracted on-device, then
  the extracted content rides with the prompt.
- Files are **not permanently indexed** and **not stored in a searchable
  document library**.
- Users simply attach files and ask questions naturally.

---

## Supported File Types

| Type | Formats |
|---|---|
| Documents | PDF, DOCX, PPTX, XLSX, EPUB |
| Text | TXT, Markdown, CSV, JSON, HTML |
| Images | PNG, JPEG, HEIC, WebP (passed through OCR), screenshots |
| Camera | Photos captured directly into the conversation |

Additional formats — such as **audio and video** — are planned for future
releases; the attachment architecture is designed to extend to them without
rewriting the pipeline.

---

## Cloud-Only Availability

Chat Attachments are currently available **only for cloud models**.

> Local models currently cannot reliably process large attachments or provide
> cloud-scale document understanding.

| Model type | Attachments |
|---|---|
| Cloud models | ✅ Attachments supported |
| Local models | ❌ Attachments not available |

For local models the attachment feature simply does not exist:

- The paperclip / "+" button is hidden — the composer shows no empty gap.
- No parsing, no OCR, no document extraction, no uploads.
- Attachment-related settings are hidden.

This is enforced by a capability flag (`supportsAttachments`), so a future
local runtime can opt in by enabling the flag — no UI or backend changes
required.

---

## User Workflow

1. Select a **cloud model** (cloud chat mode with a configured provider).
2. Tap the **paperclip** ("+") icon beside the message box.
3. Choose **Files**, **Images**, **Camera** or **Gallery**.
4. Attach one or more files — selected files appear as chips above the
   composer with name, size, and a processing indicator.
5. Ask a question — e.g. *"Summarize this PDF"* or *"Find the errors in this
   log."*
6. The AI analyzes the attachments within the current conversation.
7. Continue chatting naturally.

Attachments remain visible in the conversation history as attachment cards
under their message. Tapping a card opens the original file. Reopening an old
conversation shows the same attachment cards.

---

## Switching Models

### Cloud → Local

If you switch to a local model while files are staged in the composer, you are
asked to confirm first:

> "Attachments are only supported by cloud models. Switching to a local model
> will remove the current attachments."

Confirming removes the pending attachments and the conversation's temporary
cache, then completes the switch.

### Old conversations on a local model

Cloud conversations that contain attachments remain fully visible when you
open them on a local model, but the attachments become **read-only**: the
cards stay in the history, interaction with them is disabled, and a subtle
notice is shown:

> "This conversation contains cloud-only attachments. Switch to a cloud model
> to use them."

Switch back to a cloud model to interact with them again.

---

## Settings

Attachment settings appear under **Settings → Chat Attachments** and are shown
**only when a cloud model is active** (local models hide them entirely):

| Setting | Purpose |
|---|---|
| Maximum attachment size | Cap on a single file's bytes |
| Maximum attachments per message | Cap on files per message |
| OCR language | Language used when OCR-ing images / scanned PDFs |
| Image processing quality | JPEG quality for image compression |
| Preserve original filenames | Keep source names vs. sanitized copies |
| Temporary attachment cache | Enable/disable caching parsed copies for the conversation |
| Clear attachment cache | Immediately wipe all temporary attachment files |

---

## Privacy

- Attachments are processed **only for the active conversation**.
- Files are **not permanently indexed** — no vector database, no chunk
  storage, no persistent embeddings.
- **Temporary caches are automatically removed** when a conversation is
  deleted, or when the attachment cache is cleared.
- Only the extracted content required for the current answer is sent to the
  cloud provider. **Entire documents are never uploaded automatically.**
- There is **no global searchable document library**.

---

## UI Reference

| Element | Description |
|---|---|
| Attachment button | Paperclip / "+" beside the message box — Files, Images, Camera, Gallery |
| Attachment chips | Selected files above the composer: icon, name, size, remove ✕ |
| Processing indicator | Per-chip "Processing…" status while a file is parsed/OCR'd |
| Attachment cards | Rendered under messages in the history; tap to open the file |
| Read-only state | Cards on a local model: visible but non-interactive, with the cloud-only notice |

---

## Architecture

```
User
   │
   ▼
Attach Files
   │
   ▼
Temporary Parsing          (on-device: PDF/Office/text parsers + OCR)
   │
   ▼
Conversation Context       (extracted content injected with the prompt)
   │
   ▼
Cloud Model
   │
   ▼
Response
```

The pipeline is **conversation-scoped** (`core:attachments`):

- **Parsing** — a registry of parsers (PDF via PDFBox, Office formats via
  zip/DOM extraction, plain text, images via ML Kit OCR). Runs once per
  attachment, on-device.
- **Capability flags** — `ProviderCapabilities.supportsAttachments`,
  `supportsVision`, `supportsStreaming`, `supportsToolCalling`. The UI and
  backend gate on these flags, never on provider names.
- **Lifecycle** — attachments exist for the turn they were attached to; the
  cache directory per conversation is removed when the conversation is
  deleted.
- **Cloud send** — text documents contribute extracted text; vision-capable
  providers additionally receive native image parts. Local inference engines
  never receive attachment content or metadata.

---

## Feature Comparison

| Feature | Cloud Models | Local Models |
|---|---|---|
| Chat | ✅ | ✅ |
| Attach Files | ✅ | ❌ |
| Images | ✅ | ❌ |
| OCR | ✅ | ❌ |
| Web Search | Depends on provider | Depends on configuration |
| Tool Calling | Depends on provider | Depends on model capabilities |
