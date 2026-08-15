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

---

## Token Architecture

The Parchment Ledger is implemented as a **three-layer token system** — never
skip a layer, and never reference a primitive from a component:

```
Layer 1 · Primitive   raw values (palettes, scales) — the single source of truth
        ↓
Layer 2 · Semantic    purpose aliases (--canvas, --accent, --ink …) — themed
        ↓
Layer 3 · Component   component-scoped tokens (--btn-*, --card-*, --callout-* …)
```

| Layer | Naming | Example | Where |
|-------|--------|---------|-------|
| Primitive | `--prm-<group>-<step>` | `--prm-ember-600: #d97757` | `globals.css` `:root` |
| Semantic | `--<role>` | `--accent: var(--prm-ember-600)` | `globals.css` `:root` + `.dark` |
| Component | `--<component>-<part>` | `--btn-primary-bg: var(--accent)` | `globals.css` Layer 3 |

**Rule:** components must only ever reference Layer 2/3 tokens. Raw hex inside
a component is a design-system violation.

### Layer 1 · Primitive tokens

Defined once in `:root`. Grouped by palette and scale:

- **Parchment** (light surfaces): `--prm-parchment-050` … `--prm-parchment-700`,
  `--prm-white`
- **Ink** (text): `--prm-ink-600` … `--prm-ink-900`
- **Ember** (terracotta accent): `--prm-ember-500` … `--prm-ember-700`
- **Lamp** (dark-theme accent): `--prm-lamp-500` … `--prm-lamp-600`
- **Night** (dark surfaces): `--prm-night-020` … `--prm-night-950`
- **Status**: `--prm-ok-*`, `--prm-warn-*`, `--prm-err-*`, `--prm-info-*`
- **System chrome**: `--prm-dot-close`, `--prm-dot-minimize`, `--prm-dot-maximize`
- **Scales**: `--prm-space-1` … `--prm-space-24` (4 px base grid),
  `--prm-radius-sm/md/lg/xl/pill/full`, `--prm-text-2xs` … `--prm-display-xl`,
  `--prm-ease-silky`, `--prm-duration-fast/base/slow`

### Layer 2 · Semantic tokens (light / dark)

| Token | Light | Dark | Usage |
|-------|-------|------|-------|
| `--canvas` | `#F5F4ED` | `#141414` | Page background |
| `--surface` | `#FBFAF4` | `#1F1F1E` | Cards, dialogs |
| `--elevated` | `#FFFFFF` | `#232322` | Raised surfaces, mockups |
| `--ink` | `#141413` | `#DCDCDC` | Primary text |
| `--muted` | `#5E5D59` | `#9B9B9B` | Secondary text |
| `--faint` | `#8F8D87` | `#6F6F6B` | Tertiary text |
| `--accent` | `#D97757` | `#C78871` | Primary actions (terracotta stamp) |
| `--accent-soft` | `#E69D81` | `#D9A08C` | Accent variants |
| `--accent-deep` | `#B3573E` | `#B3573E` | Deep accent, links |
| `--accent-contrast` | `#FBFAF4` | `#FBFAF4` | Text on accent fills |
| `--line` | `#E8E6DC` | `#2A2A28` | Card borders |
| `--ok` / `--success` | `#52C41A` | `#81C784` | Success states |
| `--warn` / `--warning` | `#E0A33D` | `#E8C15C` | Warning states |
| `--err` / `--error` | `#C7442F` | `#E8836C` | Error states |
| `--info` | `#4FC3F7` | `#7CD4FB` | Informational states |
| `--ring` | accent 45% | accent 60% | Focus rings |
| `--glow` | accent 16% | accent 10% | Ambient ember glow |
| `--card-shadow` | ink-based | black-based | Rest elevation |
| `--card-shadow-hover` | ember 22% | black 70% | Hover elevation |

Shadow tokens derive from semantic colors with `color-mix()` so a single accent
change re-themes every elevation: `--shadow-ember`, `--shadow-ember-float`,
`--shadow-ember-float-soft`, `--nav-shadow`, `--mockup-shadow`,
`--mockup-shadow-desktop`.

### Layer 3 · Component tokens

| Token | Maps to | Purpose |
|-------|---------|---------|
| `--btn-primary-bg` / `--btn-primary-bg-hover` | `--accent` / `--accent-deep` | Terracotta stamp |
| `--btn-primary-text` | `--accent-contrast` | Text on the stamp |
| `--btn-primary-shadow-hover` | ember 70% | Stamp hover glow |
| `--btn-ghost-bg-hover` / `--btn-outline-bg-hover` | faint 12% / accent 6% | Ghost & outline hover |
| `--card-bg` / `--card-border` / `--card-radius` / `--card-elevation(-hover)` | semantic | Index card |
| `--badge-accent-bg` / `--badge-accent-text(-dark)` / `--badge-glow-shadow` | semantic | Badges |
| `--callout-note/warning/danger/tip-*` | status colors | Docs callouts |
| `--frame-phone-*` / `--frame-desktop-*` | semantic + night | Device mockups |

---

## Color Tokens (classic reference)

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

---

## Website Implementation

The website (`website/`) implements the same system as CSS custom properties:

| Concept | Location |
|---------|----------|
| Three token layers | `website/app/globals.css` |
| Tailwind mapping | `website/tailwind.config.ts` |
| Component tokens | Layer 3 of `globals.css` |
| Dark theme | `.dark` semantic overrides |

Components reference tokens via arbitrary values (`bg-[var(--surface)]`,
`shadow-[var(--card-shadow)]`) so theme switching is instant and any accent
re-theme propagates everywhere. Decorative effect palettes (e.g. the aurora
backgrounds) are treated as artwork data and may keep their own colors.
