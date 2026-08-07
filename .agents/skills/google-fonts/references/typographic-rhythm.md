# Typographic Rhythm System

Following Tailwind CSS Typography plugin principles for consistent vertical rhythm.

## Modular Type Scales

A modular scale multiplies a base size by a consistent ratio. Each step up = base * ratio^n.

| Scale | Ratio | Character | Best For |
|-------|-------|-----------|----------|
| Minor Second | 1.067 | Very subtle | Dense UI, dashboards |
| Major Second | 1.125 | Gentle | Apps, admin panels |
| Minor Third | 1.2 | Moderate | General purpose |
| Major Third | 1.25 | Clear | Blogs, content sites |
| Perfect Fourth | 1.333 | Strong | Marketing, editorial |
| Augmented Fourth | 1.414 | Bold | Magazines, expressive |
| Perfect Fifth | 1.5 | Very bold | Display-heavy, posters |
| Golden Ratio | 1.618 | Maximum | Hero sections, artistic |

## Size Tiers (at 16px base)

```
--font-size-xs:   base / ratio^2
--font-size-sm:   base / ratio
--font-size-base: base (16px = 1rem)
--font-size-lg:   base * ratio
--font-size-xl:   base * ratio^2
--font-size-2xl:  base * ratio^3
--font-size-3xl:  base * ratio^4
--font-size-4xl:  base * ratio^5
```

## Line Height

Inversely proportional to font size. Larger text needs tighter leading.

| Tier | Line Height | Rationale |
|------|-------------|-----------|
| xs, sm | 1.6 | Small text needs air for legibility |
| base | 1.5 | WCAG minimum for body text |
| lg | 1.45 | Slightly tighter for subheadings |
| xl | 1.35 | Heading territory |
| 2xl | 1.25 | Large headings |
| 3xl | 1.15 | Display size |
| 4xl | 1.1 | Hero/display — tight for impact |

## Letter Spacing (Tracking)

Tighter as font size increases. Larger text appears optically loose.

| Tier | Spacing | Notes |
|------|---------|-------|
| xs, sm | +0.01em | Slightly open for small text clarity |
| base | 0em | Normal |
| lg | -0.005em | Subtle tightening |
| xl | -0.01em | Noticeable tightening |
| 2xl | -0.015em | Tight |
| 3xl | -0.02em | Very tight |
| 4xl | -0.025em | Maximum tightening |

## Margin Rhythm

Bottom margins create vertical rhythm between elements.

| Tier | Margin | Usage |
|------|--------|-------|
| xs, sm | 0.5em | Inline text, captions |
| base | 0.75em | Paragraphs |
| lg | 1em | Subheadings |
| xl | 1.25em | Section headings |
| 2xl | 1.5em | Major headings |
| 3xl | 1.75em | Page sections |
| 4xl | 2em | Page-level breaks |

## Measure (Line Length)

Optimal characters per line for readability:

- **Narrow**: 45ch — Mobile, sidebars
- **Optimal**: 65ch — Default body text (the sweet spot)
- **Wide**: 75ch — Wide layouts, large screens

CSS: `max-width: 65ch;` or `max-width: 35rem;`

## Responsive Strategy

Scale ratio should decrease on smaller viewports:

```
Mobile (< 640px):  Use one step down (e.g., major-third → minor-third)
Tablet (640-1024):  Base scale
Desktop (> 1024):  Base scale or one step up
```

Tailwind implementation:
```html
<article class="prose prose-sm md:prose-base lg:prose-lg">
```

## Vertical Rhythm Formula

All spacing derives from base line-height:

```
rhythm-unit = base-font-size * base-line-height
            = 16px * 1.5 = 24px

paragraph-margin = 1 rhythm-unit = 24px
heading-margin-top = 2 rhythm-units = 48px
heading-margin-bottom = 1 rhythm-unit = 24px
```
