---
name: google-fonts
description: |
  Google Fonts typography system generator. Suggests single fonts (strict mode) and font pairs
  (contrast mode) with complete typographic scales following Tailwind Typography rhythm concepts.
  Generates CSS custom properties, Tailwind config, and embed links.
  Trigger: "choose a font", "font pairing", "typography system", "type scale", "Google Font",
  "heading font", "body font", "web font", "font for my site", "typographic scale",
  "font suggestion", "which font", "font recommendation", "serif or sans".
---

# Auto Google Font

Typography system generator for web projects using Google Fonts. Searches 1,923 fonts enriched
with typographic personality tags (geometric, humanist, neo-grotesque, etc.), contrast levels,
and body-suitability ratings. Generates complete typographic rhythm systems.

## Data Files

- `data/fonts.csv` — 1,923 Google Fonts with: Family, Category, Personality, Contrast, Width,
  Weight_Range, Variable, Body_Suitable, Quality_Tier (A/B/C), Mood, Best_For, CSS_Import
- `data/pairings.csv` — 73 proven font pairings with contrast type and scale recommendations
- `data/scales.csv` — 8 modular type scales with sizes, line-heights, letter-spacing, margins
- `references/typographic-rhythm.md` — Scale math, line-height tiers, spacing rules
- `references/pairing-principles.md` — Contrast theory, decision trees, superfamilies

## Modes

### Single Font (Strict)
One font for headings AND body. Must have weight range covering 400+700. Body-suitable required.

```bash
python3 scripts/search.py "modern clean SaaS" --mode single
python3 scripts/search.py "geometric professional" --mode single --tier A
```

### Font Pair (Contrast)
Two fonts with typographic contrast. Searches proven pairings first.

```bash
python3 scripts/search.py "elegant editorial luxury" --mode pair
python3 scripts/search.py "tech startup bold" --mode pair
```

### Full System Output
Generate CSS + Tailwind + embed link from font selection + scale choice.

```bash
# Single font
python3 scripts/generate-css.py --font "Inter" --scale major-third --format all

# Font pair
python3 scripts/generate-css.py --heading "Playfair Display" --body "Inter" \
  --scale perfect-fourth --format all

# Custom weights and base size
python3 scripts/generate-css.py --heading "Space Grotesk" --body "DM Sans" \
  --heading-weights "400,700" --body-weights "300,400,500,700" \
  --scale minor-third --base 16 --format css
```

## Workflow

1. **Understand context** — Ask: product type, mood, content density, existing brand
2. **Search fonts** — Run search.py with relevant keywords and mode
3. **Present top 3** — Show Family, Personality, Contrast, Quality Tier, Weight Range
4. **Generate system** — Run generate-css.py with chosen font(s) and appropriate scale
5. **Deliver** — Provide CSS custom properties, Tailwind config, and embed link

## Quick Decision Guide

| Project Type | Mode | Scale | Suggested Search |
|-------------|------|-------|------------------|
| SaaS / Dashboard | single | major-second | "clean professional geometric" |
| Blog / Content | pair | major-third | "editorial readable" |
| Marketing / Landing | pair | perfect-fourth | "bold modern confident" |
| Luxury / Fashion | pair | augmented-fourth | "elegant premium serif" |
| Documentation | single | minor-third | "readable accessible humanist" |
| E-commerce | pair | minor-third | "clean friendly conversion" |
| Portfolio | pair | perfect-fourth | "creative distinctive" |
| Enterprise / Gov | single | major-second | "trustworthy accessible corporate" |

## Scale Reference

| Scale | Ratio | Best For |
|-------|-------|----------|
| minor-second | 1.067 | Dense UI, dashboards |
| major-second | 1.125 | Apps, admin panels |
| minor-third | 1.2 | General purpose |
| major-third | 1.25 | Blogs, content |
| perfect-fourth | 1.333 | Marketing, editorial |
| augmented-fourth | 1.414 | Magazines, expressive |
| perfect-fifth | 1.5 | Display-heavy |
| golden-ratio | 1.618 | Hero sections |

## Pairing Contrast Types

- **Structure**: Serif + Sans (safest, most contrast)
- **Proportion**: Geometric sans + Humanist sans (subtle, modern)
- **Era**: Old-style serif + Neo-grotesque sans (dramatic)
- **Weight**: Single font family, different weights (simplest)

## Quality Rules

1. Always use `display=swap` in Google Fonts embed
2. Prefer variable fonts (fewer HTTP requests, full weight flexibility)
3. Never suggest a single-weight font for body text
4. Verify Weight_Range covers needed weights before suggesting
5. For body text: only suggest Body_Suitable=="Yes" fonts
6. Tier A fonts first, then B, then C
7. Include `preconnect` hints for fonts.googleapis.com and fonts.gstatic.com
8. Max 3 font families per project (heading + body + optional mono)

## Output Includes

- CSS custom properties: font families, sizes, line-heights, letter-spacing, measure
- Tailwind config: fontFamily, fontSize with lineHeight and letterSpacing
- Google Fonts `<link>` embed with preconnect
- Responsive breakpoint guidance

## Showcase Gallery

100 pre-made typography systems applied to fictional projects. Browse at `showcase/index.html`
or reference `showcase/showcase.json` for programmatic access.

When a user says "show me examples", "pick a style for me", or "what would work for a SaaS app",
read `showcase/showcase.json` and filter by project type to suggest a pre-built system.

### Regenerating

```bash
python3 scripts/generate-showcase.py
```

For detailed typographic theory, read `references/typographic-rhythm.md`.
For pairing decision trees, read `references/pairing-principles.md`.
