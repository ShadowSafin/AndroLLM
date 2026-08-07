#!/usr/bin/env python3
"""Generate llms-full.txt — comprehensive agent-readable font reference."""

import csv
import sys
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"
OUTPUT = Path(__file__).parent.parent / "showcase" / "llms-full.txt"


def main():
    lines = []
    lines.append("# Google Fonts MCP — Complete Agent Reference")
    lines.append("")
    lines.append("> 1,923 fonts, 73 pairings, 8 scales. Install: `uvx google-fonts-mcp`")
    lines.append("> Repo: https://github.com/sliday/google-fonts-skill")
    lines.append("> Gallery: https://sliday.github.io/google-fonts-skill/")
    lines.append("")

    # Scales
    lines.append("## Type Scales (8)")
    lines.append("")
    with open(DATA_DIR / "scales.csv", newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        scales = list(reader)
    lines.append("| Scale | Ratio | Best For | Mood |")
    lines.append("|-------|-------|----------|------|")
    for s in scales:
        lines.append(f"| {s['Scale_Name']} | {s['Ratio']} | {s['Best_For']} | {s['Mood']} |")
    lines.append("")

    # Pairings
    lines.append("## Font Pairings (73)")
    lines.append("")
    with open(DATA_DIR / "pairings.csv", newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        pairings = list(reader)
    lines.append("| Pairing | Heading | Body | Category | Contrast | Scale | Best For |")
    lines.append("|---------|---------|------|----------|----------|-------|----------|")
    for p in pairings:
        lines.append(f"| {p['Pairing_Name']} | {p['Heading_Font']} | {p['Body_Font']} | {p['Category']} | {p['Contrast_Type']} | {p['Scale_Recommendation']} | {p['Best_For'][:60]} |")
    lines.append("")

    # Top fonts
    with open(DATA_DIR / "fonts.csv", newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fonts = list(reader)

    tier_a = [f for f in fonts if f.get("Quality_Tier") == "A" and f.get("Body_Suitable") == "Yes"]
    tier_a.sort(key=lambda x: int(x.get("Popularity_Rank", 9999)))

    lines.append("## Top Body Fonts — Tier A")
    lines.append("")
    lines.append("| Family | Category | Personality | Contrast | Weight Range | Variable | Mood | Best For |")
    lines.append("|--------|----------|-------------|----------|-------------|----------|------|----------|")
    for f in tier_a:
        lines.append(f"| {f['Family']} | {f['Category']} | {f.get('Personality','')} | {f.get('Contrast','')} | {f.get('Weight_Range','')} | {f.get('Variable','')} | {f.get('Mood','')[:30]} | {f.get('Best_For','')[:40]} |")
    lines.append("")

    tier_b = [f for f in fonts if f.get("Quality_Tier") == "B" and f.get("Body_Suitable") == "Yes"]
    tier_b.sort(key=lambda x: int(x.get("Popularity_Rank", 9999)))
    lines.append("## Top Body Fonts — Tier B (top 30)")
    lines.append("")
    lines.append("| Family | Category | Personality | Weight Range | Variable | Best For |")
    lines.append("|--------|----------|-------------|-------------|----------|----------|")
    for f in tier_b[:30]:
        lines.append(f"| {f['Family']} | {f['Category']} | {f.get('Personality','')} | {f.get('Weight_Range','')} | {f.get('Variable','')} | {f.get('Best_For','')[:40]} |")
    lines.append("")

    # MCP Tool Reference
    lines.append("## MCP Tools")
    lines.append("")
    lines.append("### search_fonts")
    lines.append("- query: str — description, mood, or use case")
    lines.append("- mode: single (default) | pair | scale")
    lines.append("- tier: A | B | C (single mode only)")
    lines.append("- max_results: int (default 5)")
    lines.append("")
    lines.append("### generate_typography_system")
    lines.append("- heading: str — heading font name")
    lines.append("- body: str (optional, defaults to heading)")
    lines.append("- scale: minor-second | major-second | minor-third | major-third | perfect-fourth | augmented-fourth | perfect-fifth | golden-ratio")
    lines.append("- base: int (default 16)")
    lines.append("- format: css | tailwind | embed | all")
    lines.append("- Returns: {css, tailwind, embed}")
    lines.append("")
    lines.append("### lookup_font")
    lines.append("- name: str — exact font name")
    lines.append("")
    lines.append("### list_scales")
    lines.append("- Returns all 8 scales")
    lines.append("")
    lines.append("### list_pairings")
    lines.append("- category: str (optional) — filter by contrast type")
    lines.append("")

    # Quick decision guide
    lines.append("## Quick Decision Guide")
    lines.append("")
    lines.append("| Project Type | Mode | Scale | Search |")
    lines.append("|-------------|------|-------|--------|")
    lines.append("| SaaS / Dashboard | single | major-second | clean professional geometric |")
    lines.append("| Blog / Content | pair | major-third | editorial readable |")
    lines.append("| Marketing / Landing | pair | perfect-fourth | bold modern confident |")
    lines.append("| Luxury / Fashion | pair | augmented-fourth | elegant premium serif |")
    lines.append("| Documentation | single | minor-third | readable accessible humanist |")
    lines.append("| E-commerce | pair | minor-third | clean friendly conversion |")
    lines.append("| Portfolio | pair | perfect-fourth | creative distinctive |")
    lines.append("| Enterprise / Gov | single | major-second | trustworthy accessible corporate |")
    lines.append("")

    OUTPUT.parent.mkdir(exist_ok=True)
    text = "\n".join(lines)
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"Generated llms-full.txt: {len(text)} bytes, {len(lines)} lines", file=sys.stderr)


if __name__ == "__main__":
    main()
