#!/usr/bin/env python3
"""
Fetch Google Fonts typographic tags from the google/fonts GitHub repo,
merge with existing font data, and produce an enriched fonts.csv.
"""

import csv
import io
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE_CSV = os.path.expanduser(
    "~/.claude/skills/ui-ux-pro-max/data/google-fonts.csv"
)
OUTPUT_CSV = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "data",
    "fonts.csv",
)

FAMILIES_URL = (
    "https://raw.githubusercontent.com/google/fonts/main/tags/all/families.csv"
)
QUANT_URL = (
    "https://raw.githubusercontent.com/google/fonts/main/tags/all/quant.csv"
)

MAX_RETRIES = 3
RETRY_DELAY = 2  # seconds

# Maps (Category, Personality, Body_Suitable) -> Best_For string
BEST_FOR_MAP = {
    ("Sans Serif", "Geometric", True): "SaaS, tech, apps, UI",
    ("Sans Serif", "Geometric", False): "Headlines, branding, logos",
    ("Sans Serif", "Neo Grotesque", True): "Corporate, editorial, dashboards",
    ("Sans Serif", "Neo Grotesque", False): "Headlines, signage",
    ("Sans Serif", "Humanist", True): "Education, healthcare, government, accessibility",
    ("Sans Serif", "Humanist", False): "Friendly headlines, children's content",
    ("Sans Serif", "Grotesque", True): "General purpose, web body text",
    ("Sans Serif", "Grotesque", False): "Display, branding",
    ("Sans Serif", "Rounded", True): "Friendly apps, children, casual UI",
    ("Sans Serif", "Rounded", False): "Playful headlines, gaming",
    ("Serif", "Transitional", True): "News, editorial, long-form reading",
    ("Serif", "Transitional", False): "Headlines, magazine covers",
    ("Serif", "Old Style", True): "Books, literary, academic",
    ("Serif", "Old Style", False): "Decorative headers, invitations",
    ("Serif", "Modern", True): "Fashion, luxury, editorial",
    ("Serif", "Modern", False): "Luxury branding, fashion headlines",
    ("Serif", "Slab", True): "Marketing, bold editorial, ads",
    ("Serif", "Slab", False): "Bold headlines, posters",
    ("Monospace", None, True): "Code editors, terminals, data tables",
    ("Monospace", None, False): "Code display, tech branding",
    ("Display", None, False): "Headlines, posters, branding",
    ("Handwriting", None, False): "Invitations, greeting cards, personal branding",
}


def log(msg: str) -> None:
    print(msg, file=sys.stderr)


def fetch_url(url: str) -> str | None:
    """Fetch URL content with retries. Returns text or None on failure."""
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            log(f"  Fetching {url} (attempt {attempt}/{MAX_RETRIES})...")
            req = urllib.request.Request(url, headers={"User-Agent": "auto-google-font/1.0"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read().decode("utf-8")
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as e:
            log(f"  Error: {e}")
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY * attempt)
    log(f"  Failed to fetch {url} after {MAX_RETRIES} attempts")
    return None


def parse_families_csv(text: str) -> dict:
    """Parse families.csv into {font_name: {tag_path: score}}."""
    result: dict[str, dict[str, int]] = {}
    reader = csv.reader(io.StringIO(text))
    for row in reader:
        if len(row) < 4:
            continue
        font_name = row[0].strip()
        tag_path = row[2].strip()
        try:
            score = int(row[3].strip())
        except (ValueError, IndexError):
            continue
        if not font_name or not tag_path:
            continue
        result.setdefault(font_name, {})[tag_path] = score
    return result


def parse_quant_csv(text: str) -> dict:
    """Parse quant.csv into {font_name: {metric_path: value}}."""
    result: dict[str, dict[str, float]] = {}
    reader = csv.reader(io.StringIO(text))
    for row in reader:
        if len(row) < 4:
            continue
        font_name = row[0].strip()
        metric_path = row[2].strip()
        try:
            value = float(row[3].strip())
        except (ValueError, IndexError):
            continue
        if not font_name or not metric_path:
            continue
        result.setdefault(font_name, {})[metric_path] = value
    return result


def get_personality(tags: dict[str, int]) -> str:
    """Extract best personality tag from serif/*, sans/*, slab/* paths."""
    best_tag = ""
    best_score = -1
    for path, score in tags.items():
        parts = path.strip("/").split("/")
        if len(parts) == 2 and parts[0].lower() in ("serif", "sans", "slab"):
            if score > best_score:
                best_score = score
                best_tag = parts[1]
    return best_tag


def get_expressive(tags: dict[str, int]) -> str:
    """Get top 3 expressive tags joined by |."""
    expressive = []
    for path, score in tags.items():
        parts = path.strip("/").split("/")
        if len(parts) == 2 and parts[0].lower() == "expressive":
            expressive.append((score, parts[1]))
    expressive.sort(key=lambda x: -x[0])
    return "|".join(t[1] for t in expressive[:3])


def compute_contrast(quant: dict[str, float] | None) -> str:
    """Derive contrast from stroke width ratio."""
    if not quant:
        return "Medium"
    sw_min = quant.get("/quant/stroke_width_min")
    sw_max = quant.get("/quant/stroke_width_max")
    if sw_min is None or sw_max is None or sw_min == 0:
        return "Medium"
    ratio = sw_max / sw_min
    if ratio > 2.5:
        return "High"
    elif ratio >= 1.5:
        return "Medium"
    else:
        return "Low"


def compute_width(keywords: str) -> str:
    """Derive width from keywords."""
    kw_lower = keywords.lower()
    if "condensed" in kw_lower:
        return "Condensed"
    if "wide" in kw_lower or "expanded" in kw_lower:
        return "Wide"
    return "Normal"


def parse_weight_range(styles: str) -> str:
    """Extract min-max weight from styles like '100 | 200 | 300i | 400 | 700'."""
    weights = set()
    for part in styles.split("|"):
        part = part.strip()
        match = re.match(r"(\d+)", part)
        if match:
            weights.add(int(match.group(1)))
    if not weights:
        return ""
    return f"{min(weights)}-{max(weights)}"


def compute_body_suitable(
    category: str,
    classifications: str,
    weights: set[int],
    personality: str,
) -> bool:
    """Determine if font is suitable for body text."""
    if category not in ("Sans Serif", "Serif"):
        return False
    if 400 not in weights or 700 not in weights:
        return False
    cls_lower = classifications.lower()
    if "display" in cls_lower or "symbols" in cls_lower:
        return False
    display_personalities = {"Display", "Script", "Decorative", "Handwriting"}
    if personality in display_personalities:
        return False
    return True


def compute_quality_tier(pop_rank: int, body_suitable: bool) -> str:
    """Compute quality tier based on popularity and body suitability."""
    if pop_rank <= 50 and body_suitable:
        return "A"
    if pop_rank <= 200:
        return "B"
    return "C"


def get_best_for(category: str, personality: str, body_suitable: bool) -> str:
    """Derive best-for suggestion."""
    key = (category, personality, body_suitable)
    if key in BEST_FOR_MAP:
        return BEST_FOR_MAP[key]
    # Try with None personality
    key_none = (category, None, body_suitable)
    if key_none in BEST_FOR_MAP:
        return BEST_FOR_MAP[key_none]
    # Fallback by category
    key_fallback = (category, None, False)
    if key_fallback in BEST_FOR_MAP:
        return BEST_FOR_MAP[key_fallback]
    return "General purpose"


def generate_css_import(family: str, styles: str) -> str:
    """Generate CSS @import for Google Fonts."""
    encoded = urllib.parse.quote(family, safe="")
    # Extract weights
    weights = set()
    for part in styles.split("|"):
        part = part.strip()
        match = re.match(r"(\d+)", part)
        if match:
            weights.add(int(match.group(1)))
    if not weights:
        weights = {400}
    weight_str = ";".join(str(w) for w in sorted(weights))
    return f"@import url('https://fonts.googleapis.com/css2?family={encoded}:wght@{weight_str}&display=swap');"


def main() -> None:
    log("=== Google Fonts Enrichment Pipeline ===")

    # 1. Read base CSV
    log(f"Reading base data from {BASE_CSV}...")
    if not os.path.exists(BASE_CSV):
        log(f"ERROR: Base CSV not found at {BASE_CSV}")
        sys.exit(1)

    with open(BASE_CSV, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        base_fonts = list(reader)
    log(f"  Loaded {len(base_fonts)} fonts from base CSV")

    # 2. Fetch tags
    log("Fetching typographic tags from google/fonts repo...")
    families_text = fetch_url(FAMILIES_URL)
    quant_text = fetch_url(QUANT_URL)

    families_tags: dict[str, dict[str, int]] = {}
    quant_data: dict[str, dict[str, float]] = {}

    if families_text:
        families_tags = parse_families_csv(families_text)
        log(f"  Parsed tags for {len(families_tags)} fonts")
    else:
        log("  WARNING: No families tags data available")

    if quant_text:
        quant_data = parse_quant_csv(quant_text)
        log(f"  Parsed quant data for {len(quant_data)} fonts")
    else:
        log("  WARNING: No quant data available")

    # 3. Enrich each font
    log("Enriching font data...")
    output_columns = [
        "Family",
        "Category",
        "Stroke",
        "Personality",
        "Expressive",
        "Contrast",
        "Width",
        "Styles",
        "Weight_Range",
        "Variable",
        "Variable_Axes",
        "Body_Suitable",
        "Quality_Tier",
        "Popularity_Rank",
        "Mood",
        "Best_For",
        "Keywords",
        "Subsets",
        "Google_Fonts_URL",
        "CSS_Import",
    ]

    enriched = []
    for font in base_fonts:
        family = font.get("Family", "")
        category = font.get("Category", "")
        stroke = font.get("Stroke", "")
        classifications = font.get("Classifications", "")
        keywords = font.get("Keywords", "")
        styles = font.get("Styles", "")
        variable_axes = font.get("Variable Axes", "")
        subsets = font.get("Subsets", "")
        pop_rank_str = font.get("Popularity Rank", "9999")
        url = font.get("Google Fonts URL", "")

        try:
            pop_rank = int(pop_rank_str)
        except ValueError:
            pop_rank = 9999

        # Parse weights from styles
        weights = set()
        for part in styles.split("|"):
            part = part.strip()
            match = re.match(r"(\d+)", part)
            if match:
                weights.add(int(match.group(1)))

        # Tags-based enrichment
        tags = families_tags.get(family, {})
        quant = quant_data.get(family)

        personality = get_personality(tags)
        expressive = get_expressive(tags)
        contrast = compute_contrast(quant)
        width = compute_width(keywords)
        weight_range = parse_weight_range(styles)
        variable = "Yes" if variable_axes.strip() else "No"
        body_suitable = compute_body_suitable(category, classifications, weights, personality)
        quality_tier = compute_quality_tier(pop_rank, body_suitable)
        mood = expressive  # Mood derived from expressive tags
        best_for = get_best_for(category, personality, body_suitable)
        css_import = generate_css_import(family, styles)

        enriched.append(
            {
                "Family": family,
                "Category": category,
                "Stroke": stroke,
                "Personality": personality,
                "Expressive": expressive,
                "Contrast": contrast,
                "Width": width,
                "Styles": styles,
                "Weight_Range": weight_range,
                "Variable": variable,
                "Variable_Axes": variable_axes,
                "Body_Suitable": "Yes" if body_suitable else "No",
                "Quality_Tier": quality_tier,
                "Popularity_Rank": pop_rank,
                "Mood": mood,
                "Best_For": best_for,
                "Keywords": keywords,
                "Subsets": subsets,
                "Google_Fonts_URL": url,
                "CSS_Import": css_import,
            }
        )

    # 4. Write output
    os.makedirs(os.path.dirname(OUTPUT_CSV), exist_ok=True)
    log(f"Writing enriched data to {OUTPUT_CSV}...")
    with open(OUTPUT_CSV, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=output_columns, quoting=csv.QUOTE_MINIMAL)
        writer.writeheader()
        writer.writerows(enriched)

    log(f"Done! Wrote {len(enriched)} fonts to {OUTPUT_CSV}")

    # Print summary stats
    body_count = sum(1 for r in enriched if r["Body_Suitable"] == "Yes")
    tier_a = sum(1 for r in enriched if r["Quality_Tier"] == "A")
    tier_b = sum(1 for r in enriched if r["Quality_Tier"] == "B")
    with_personality = sum(1 for r in enriched if r["Personality"])
    log(f"  Body-suitable: {body_count}")
    log(f"  Tier A: {tier_a}, Tier B: {tier_b}")
    log(f"  With personality tag: {with_personality}")


if __name__ == "__main__":
    main()
