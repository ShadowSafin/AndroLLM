#!/usr/bin/env python3
"""
Reads typography.csv and transforms it into pairings.csv for the auto-google-font skill.

Computes: Contrast_Type, Scale_Recommendation, Heading_Weights, Body_Weights
"""

import csv
import re
import sys
from pathlib import Path

INPUT = Path.home() / ".claude/skills/ui-ux-pro-max/data/typography.csv"
OUTPUT = Path(__file__).resolve().parent.parent / "data" / "pairings.csv"


def derive_contrast_type(category: str, heading: str, body: str) -> str:
    cat = category.strip().lower()
    if heading.strip().lower() == body.strip().lower():
        return "Weight"
    if cat == "serif + sans":
        return "Structure"
    if cat == "sans + sans":
        return "Proportion"
    if cat == "serif + serif":
        return "Era"
    if cat.startswith("display +"):
        return "Weight"
    if cat.startswith("script +"):
        return "Structure"
    if cat.startswith("mono +"):
        return "Structure"
    return "Proportion"


def derive_scale(best_for: str, mood: str) -> str:
    combined = (best_for + " " + mood).lower()
    if any(k in combined for k in ("dashboard", "admin", "data", "dense")):
        return "major-second"
    if any(k in combined for k in ("blog", "reading", "editorial", "content", "magazine")):
        return "major-third"
    if any(k in combined for k in ("marketing", "landing", "portfolio", "agency")):
        return "perfect-fourth"
    if any(k in combined for k in ("luxury", "fashion", "premium", "hero")):
        return "augmented-fourth"
    if any(k in combined for k in ("saas", "startup", "app", "corporate")):
        return "minor-third"
    if any(k in combined for k in ("children", "playful", "gaming", "fun")):
        return "minor-third"
    return "major-third"


def parse_weights_from_css(css_import: str, font_name: str) -> str:
    """Extract wght@ values for a specific font from the CSS import URL."""
    # Normalize font name for URL matching: "Playfair Display" -> "Playfair+Display"
    url_name = font_name.strip().replace(" ", "+")
    # Pattern: family=Font+Name:wght@300;400;500
    pattern = re.escape(url_name) + r":wght@([\d;]+)"
    match = re.search(pattern, css_import)
    if match:
        return match.group(1)
    # Check if font appears without weights (e.g., single weight like Bebas Neue)
    if url_name in css_import:
        return "400"
    return "400"


def main():
    rows = []
    with open(INPUT, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            heading = row["Heading Font"]
            body = row["Body Font"]
            category = row["Category"]
            best_for = row["Best For"]
            mood = row["Mood/Style Keywords"]
            css_import = row["CSS Import"]

            rows.append({
                "Pairing_Name": row["Font Pairing Name"],
                "Category": category,
                "Heading_Font": heading,
                "Body_Font": body,
                "Mood_Keywords": mood,
                "Best_For": best_for,
                "Contrast_Type": derive_contrast_type(category, heading, body),
                "Scale_Recommendation": derive_scale(best_for, mood),
                "Heading_Weights": parse_weights_from_css(css_import, heading),
                "Body_Weights": parse_weights_from_css(css_import, body),
                "Google_Fonts_URL": row["Google Fonts URL"],
                "CSS_Import": css_import,
                "Notes": row["Notes"],
            })

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "Pairing_Name", "Category", "Heading_Font", "Body_Font",
        "Mood_Keywords", "Best_For", "Contrast_Type", "Scale_Recommendation",
        "Heading_Weights", "Body_Weights", "Google_Fonts_URL", "CSS_Import", "Notes",
    ]
    with open(OUTPUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Produced {len(rows)} pairings -> {OUTPUT}")


if __name__ == "__main__":
    main()
