# Font Pairing Principles

## The Four Contrast Dimensions

Effective font pairs create contrast along one or more dimensions while maintaining harmony.

### 1. Structure Contrast
Pair fonts with different structural DNA.
- Serif heading + Sans body (most common, safest)
- Script accent + Sans body
- Mono labels + Sans body

### 2. Proportion Contrast
Pair fonts with different proportional characteristics.
- Geometric sans heading + Humanist sans body
- Wide heading + Normal-width body
- Condensed heading + Regular body

### 3. Era Contrast
Pair fonts from different typographic traditions.
- Old Style serif heading + Neo-Grotesque sans body
- Transitional serif + Geometric sans

### 4. Weight Contrast
Use a single font family with weight differentiation.
- Same font: 800 heading, 400 body
- Safest approach — guaranteed harmony

## Pairing Rules

1. **Contrast in structure, similarity in proportion** — Fonts should differ in category but share similar x-height ratios
2. **Never pair same sub-class** — Two geometric sans-serifs or two old-style serifs compete
3. **Limit to 2-3 families** — More creates visual noise. Heading + Body + optional Mono
4. **Variable fonts preferred** — One HTTP request per family, full weight flexibility
5. **Verify weight overlap** — Heading needs 600-900. Body needs 400-700.

## Single Font Strategy (Strict Mode)

When one font must serve all roles:

**Requirements:**
- Weight range: minimum 400 + 700 (ideally 300-800+)
- Body-readable at 16px
- Distinct character at display sizes
- Variable font preferred

**Top single fonts:**
- Inter — neutral, professional, variable 100-900
- Plus Jakarta Sans — friendly, modern, variable
- DM Sans — geometric, clean, variable
- Source Sans 3 — humanist, accessible, variable
- Nunito Sans — soft, approachable, variable
- IBM Plex Sans — corporate, trustworthy
- Figtree — friendly, geometric, variable
- Lexend — accessibility-optimized, variable

## Decision Tree

```
Content-heavy (blog, docs, news)?
├── Want warmth? → Serif heading + Sans body (Structure contrast)
│   ├── Traditional: Merriweather + Source Sans 3
│   ├── Modern: Lora + Inter
│   └── Premium: Playfair Display + Inter
└── Want neutrality? → Single sans (Weight contrast)
    ├── Professional: Inter
    ├── Friendly: Plus Jakarta Sans
    └── Technical: IBM Plex Sans

App/Dashboard/SaaS?
├── Want personality? → Two sans (Proportion contrast)
│   ├── Tech: Space Grotesk + DM Sans
│   ├── Modern: Outfit + Work Sans
│   └── Friendly: Poppins + Open Sans
└── Want simplicity? → Single sans (Weight contrast)
    ├── Clean: Inter
    ├── Geometric: DM Sans
    └── Accessible: Lexend

Marketing/Landing?
├── Premium? → Serif + Sans (Structure contrast)
│   ├── Luxury: Cormorant + Montserrat
│   ├── Fashion: Bodoni Moda + Jost
│   └── Classic: Playfair Display + Inter
└── Bold? → Display + Sans (Weight contrast)
    ├── Impact: Bebas Neue + Source Sans 3
    ├── Energetic: Anton + Epilogue
    └── Artistic: Syne + Manrope
```

## x-Height Matching

Fonts that pair well share similar x-height ratios (lowercase letter height relative to cap height). This creates visual harmony at body sizes.

Good matches (similar x-height):
- Inter ↔ Source Serif 4
- DM Sans ↔ DM Serif Display
- IBM Plex Sans ↔ IBM Plex Serif
- Roboto ↔ Roboto Slab (superfamily)

## Superfamily Advantage

Fonts from the same designer/foundry often pair perfectly:
- IBM Plex: Sans, Serif, Mono
- Source: Sans 3, Serif 4, Code Pro
- Noto: Sans, Serif (every script)
- DM: Sans, Serif Display, Mono
- Fira: Sans, Code
