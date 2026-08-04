# Cloud Intelligence Design System (DESIGN.md)

## Design Philosophy
"Floating above the clouds."
Calmness. Intelligence. Elegance. Premium Quality. Privacy. Performance. Weightlessness.

## Visual Emotion
Curiosity, Wonder, Comfort, Focus, Creativity, Calm, Confidence.

## Color Tokens
- **Primary**: Deep Midnight Blue (`#0A0F1D`), Sky Blue (`#38BDF8`), Azure (`#0284C7`), Cloud White (`#F8FAFC`), Moon Silver (`#E2E8F0`), Soft Cyan (`#2DD4BF`).
- **Secondary**: Lavender (`#C084FC`), Deep Indigo (`#1E1B4B`), Purple Glow (`#A855F7`).
- **Accent**: Electric Blue (`#3B82F6`), Aurora Cyan (`#06B6D4`), Moonlight White (`#FFFFFF`).
- **Background**: 6-Layer Animated Atmospheric Gradient.

## Core Shapes & Geometry
- **Cloud Islands (Cards)**: `RoundedCornerShape(28.dp)` with glassmorphic borders and ambient drop shadows.
- **Cloud Capsules (Buttons & Inputs)**: `RoundedCornerShape(50.dp)` floating pill shape.
- **Spacing System**: Strict 8dp grid with large breathing margins.

## Glassmorphism Rules
- Translucent surface mask (`0x260F172A`).
- Ambient border highlight (`0x33E2E8F0` / `0x6638BDF8`).
- Floating elevation shadow (`8.dp` - `16.dp`).
- Never compromise text contrast or legibility.

## Micro-Interactions
- Spring press compression on buttons.
- Soft elevation float on card hovers/taps.
- Smooth slide & fade animations on streaming chat tokens.
