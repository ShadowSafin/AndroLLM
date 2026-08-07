# Google Fonts MCP

MCP server and Claude Code skill for typography system generation using Google Fonts. Searches 1,923 enriched fonts, suggests singles or pairs, and generates complete CSS/Tailwind typographic systems.

![CleanShot 2026-03-21 at 19 08 00](https://github.com/user-attachments/assets/937a8a6c-d6ab-4f39-bfa4-848450932b32)

## What It Does

- **Font search** with BM25 ranking across personality, mood, and use-case tags
- **Single font mode** — one font for heading + body (body-suitable, multi-weight)
- **Pair mode** — 73 proven pairings with contrast type classification
- **CSS generation** — custom properties, Tailwind config, Google Fonts embed links
- **8 modular scales** — from minor-second (dense UI) to golden-ratio (hero sections)
- **100-project showcase** — [browsable gallery](https://sliday.github.io/google-fonts-skill/) of pre-made typography systems

## How It Works

1. Tell Claude what you're building (SaaS, blog, e-commerce...)
2. Skill searches 1,923 fonts or 73 proven pairings
3. Pick a font + scale → get CSS custom properties, Tailwind config, and embed link
4. Ship

## Installation

### MCP Server (any agent)

```bash
uvx google-fonts-mcp
```

Or install permanently:

```bash
pip install google-fonts-mcp
```

### Claude Code

```bash
claude mcp add google-fonts -- uvx google-fonts-mcp
```

Or as a plugin:

```bash
claude plugin marketplace add sliday/google-fonts-skill
claude plugin install google-fonts
```

### Cursor

Add to `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "google-fonts": {
      "command": "uvx",
      "args": ["google-fonts-mcp"]
    }
  }
}
```

### Windsurf

Add to `~/.codeium/windsurf/mcp_config.json`:

```json
{
  "mcpServers": {
    "google-fonts": {
      "command": "uvx",
      "args": ["google-fonts-mcp"]
    }
  }
}
```

### Any MCP Client

```json
{
  "command": "uvx",
  "args": ["google-fonts-mcp"]
}
```

## Usage

The skill activates automatically when you mention fonts, typography, or type scales in Claude Code.

### CLI Scripts

```bash
# Search for a single body-suitable font
python3 scripts/search.py "modern clean SaaS" --mode single

# Search proven font pairings
python3 scripts/search.py "elegant editorial luxury" --mode pair

# Look up a specific font
python3 scripts/search.py "Inter" --mode lookup

# Search type scales
python3 scripts/search.py "marketing bold" --mode scale

# Generate CSS + Tailwind + embed for a single font
python3 scripts/generate-css.py --font "Inter" --scale major-third --format all

# Generate for a font pair
python3 scripts/generate-css.py --heading "Playfair Display" --body "Inter" \
  --scale perfect-fourth --format all
```

![CleanShot 2026-03-21 at 19 08 08](https://github.com/user-attachments/assets/c19d43a4-55d1-4dfc-a9a7-d6f82c507a0f)

## Data

| File | Records | Description |
|------|---------|-------------|
| `data/fonts.csv` | 1,923 | Google Fonts with personality, contrast, body-suitability, quality tier |
| `data/pairings.csv` | 73 | Proven pairings with contrast type and scale recommendations |
| `data/scales.csv` | 8 | Modular type scales with sizes, line-heights, letter-spacing |

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

## Showcase Gallery

**[Browse 100 Typography Systems →](https://sliday.github.io/google-fonts-skill/)**

100 pre-made typography systems applied to fictional projects — SaaS dashboards, editorial blogs, luxury brands, gaming sites, and more. Each page renders live with actual Google Fonts.

Regenerate with:

```bash
python3 scripts/generate-showcase.py
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `search_fonts` | Search fonts by mood/use-case. Modes: single, pair, scale |
| `generate_typography_system` | Full CSS + Tailwind + embed from font + scale |
| `lookup_font` | Get full metadata for a specific font |
| `list_scales` | All 8 typographic scales |
| `list_pairings` | All 73 proven pairings (filterable by contrast type) |

## Project Structure

```
├── src/google_fonts_mcp/             # MCP server (PyPI package)
│   ├── server.py                     # FastMCP server with 5 tools
│   ├── core.py                       # Search engine + CSS generation
│   └── data/                         # Bundled font data
├── SKILL.md                          # Claude Code skill definition
├── data/                             # Canonical font data (CSV)
├── scripts/                          # CLI tools + generators
├── showcase/                         # 100-project gallery + SEO
│   ├── llms-full.txt                 # Agent-readable full reference
│   └── pages/                        # Individual HTML previews
├── tests/                            # pytest suite
└── registry/                         # MCP registry submission files
```

## License

MIT
