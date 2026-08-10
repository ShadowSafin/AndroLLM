# TTS Text Normalization

How AndroLLM turns raw LLM output into speech that the offline TTS voice can
actually pronounce — and why that matters.

---

## The Problem

The app's offline VITS voice ships with a **plain-text lexicon that has no
digit or symbol tokens**. Feed it `"10 + 10 = 20"` and sherpa-onnx emits
`OOV 10. Ignore it!` warnings and produces an **empty audio buffer**
(silence).

Worse, words that aren't in the lexicon at all (brands, jargon, model names
— "LLM", "AndroLLM", "Kotlin") are silently **dropped mid-synthesis**.

So every sentence must be converted to natural English words before
synthesis, and any remaining out-of-lexicon word must be spelled out.

---

## The Pipeline

```
LLM output
   │
   ▼
┌───────────────────────────────┐
│ TextNormalizationEngine       │  stage-gated, < 20 ms, fully offline
│  1. URLs & emails → spoken    │
│  2. Dates & times             │
│  3. Units glued to numbers    │
│  4. Math symbols & operators  │
│  5. Numbers (int/float/%)     │
│  6. Currencies                │
│  7. Symbols, emoji, stray     │
└───────────────┬───────────────┘
                ▼
┌───────────────────────────────┐
│ EnglishTtsNormalizer          │  per-stage helpers + number→words
└───────────────┬───────────────┘
                ▼
┌───────────────────────────────┐
│ spellOutOfLexicon(lexicon)    │  OOV words → letter-by-letter
└───────────────┬───────────────┘
                ▼
         sherpa-onnx VITS
```

Every stage can be toggled independently in **Settings → Text
Normalization**; the whole pipeline can be disabled (raw model output).

---

## Stage Reference

| Stage | Example |
|---|---|
| Numbers | `123 → one hundred twenty-three` · `3.14` · `94%` · `21st` · `v2.4.1` |
| Dates & times | `08/09/2026 → August ninth twenty twenty six` · `14:30 → two thirty` |
| Currencies | `$20 → twenty dollars` · `€19.99 → nineteen euros and ninety nine cents` |
| Units | `1200 MHz → one thousand two hundred megahertz` · `15 km → kilometers` · `32°C → degrees celsius` |
| Math | `2+2 → two plus two` · `10×5` · `16:9` · `≈ ≥ ≤ ÷` |
| Emoji & symbols | `😊 → smiling face` · `# @ & °` |
| URLs & emails | `user@example.com → user at example dot com` |
| Phones | `+91 98765… → digit by digit` |
| Abbreviations | `CPU → C P U` · `LLM → L L M` · `GHz → gigahertz` |

---

## Out-of-Lexicon Spelling

`EnglishTtsNormalizer.spellOutOfLexicon(text, lexicon)` re-spells every word
missing from the VITS lexicon **letter by letter** (all single letters exist
in the lexicon), so no word is ever silently skipped:

| Input | Output |
|---|---|
| `LLM` | `L L M` |
| `state-of-the-art` | `state of the art` (hyphenated compounds are decomposed) |
| `llamas` | `llama s` (plural inflections keep the root word) |

The lexicon is loaded once from the bundled `lexicon.txt` on engine init; if
it can't be read, OOV spelling is disabled gracefully.

---

## Debug Mode

Turn on **Debug mode** in the settings to trace every stage to logcat
(`TN [stage]` tags) — intended for authoring new normalization rules.

---

## Where It Runs

`SherpaOnnxOfflineTtsEngine.synthesize()` normalizes the text before
calling the VITS model — every spoken reply (chat answers, voice assistant
turns, confirmation questions) goes through the same pipeline. The model's
speech speed (0.5×–2.0×) is applied at synthesis time, never by editing text.

---

## See Also

- [Voice Assistant](voice-assistant.md) — the full voice pipeline
- [Agent Platform](../agent/agent-platform.md) — voice confirmations and tool-driven turns
