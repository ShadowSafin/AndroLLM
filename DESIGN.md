# AndroLLM Design System (DESIGN.md)

## The Parchment Ledger
A warm daylight desk: every conversation is a letter kept in ink on parchment,
every action is a terracotta stamp. Clean editorial layout, one warm accent,
no glass, neon, or gradient chrome.

Sourced from `design-system/` (Claude-inspired):
- Canvas `#F5F4ED`, ink `#141413`, terracotta `#D97757`
- Muted `#5E5D59`, cream borders `#F0EEE6` / `#E8E6DC`

## Visual Emotion
Calm, focused, handcrafted, trustworthy, warm, editorial, precise.

## Color Tokens
- **Canvas**: Parchment `#F5F4ED`, raised `#ECEBE3`, deep `#EFEEE6`
- **Surface**: Warm white `#FBFAF4`, elevated `#FFFFFF`
- **Ink**: `#141413` (headings/body), `#5E5D59` (secondary), `#8F8D87` (faint)
- **Accent**: Terracotta `#D97757`, light `#E69D81`, deep `#B3573E`
- **Success** `#52C41A` · **Warning** `#E0A33D` · **Error** `#C7442F`
- **Dark variant**: canvas `#141414`, surface `#272727`, text `#DCDCDC`,
  primary `#C78871` (tokens.dark.json)

## Core Shapes & Geometry
- **Index Cards**: `RoundedCornerShape(16.dp)` with 1dp cream borders and soft
  warm shadows.
- **Paper Slips**: `RoundedCornerShape(10.dp)` for chat letters, dialogs.
- **Terracotta Stamp (Buttons)**: `RoundedCornerShape(32.dp)` capsule, one per
  screen.
- **Spacing**: 8dp baseline grid with generous breathing margins.

## Typography
- **Headlines**: serif (`FontFamily.Serif`), semi-bold/bold — the editorial voice.
- **Body**: system sans, 14sp/22 leading.
- **Ledger labels**: monospace small-caps for model meta, tokens, benchmarks.

## Micro-Interactions
- Spring press compression on the terracotta stamp.
- Soft warm elevation float on card taps.
- Smooth slide & fade on streaming chat tokens.
- Breathing terracotta ember progress indicators.
