#!/usr/bin/env python3
"""Generate showcase gallery: 100 fictional projects with complete typography systems."""

import csv
import hashlib
import json
import random
import re
import sys
from html import escape
from pathlib import Path

from core import (
    DATA_DIR, SCALES, TIERS, LINE_HEIGHTS, LETTER_SPACINGS, MARGIN_BOTTOMS,
    compute_sizes, generate_css, generate_embed, get_fallback, encode_font, fmt_rem,
)

PROJECT_ROOT = Path(__file__).parent.parent
SHOWCASE_DIR = PROJECT_ROOT / "showcase"
PAGES_DIR = SHOWCASE_DIR / "pages"

# 12-color accent palette (good contrast on both light/dark)
ACCENT_COLORS = [
    "#3B82F6", "#8B5CF6", "#EC4899", "#EF4444", "#F59E0B", "#10B981",
    "#06B6D4", "#6366F1", "#F97316", "#14B8A6", "#A855F7", "#E11D48",
]

DARK_MOODS = {"dark", "cinematic", "cyberpunk", "brutalist", "hud", "terminal", "mono", "raw", "neon", "pixel", "retro"}

PROJECT_NAMES = {
    "saas": [
        "Aether Analytics", "Nimbus CRM", "Pylon Project Hub", "Gradient Metrics",
        "Clearpath Dashboard", "Synapse Ops", "Meridian Cloud", "Beacon Data",
        "Cascade Flow", "Vertex Platform", "Onyx Workspace", "Helios Admin",
        "Stratus Monitor", "Prism Insights", "Lattice Systems", "Orbit Control",
        "Canopy Cloud", "Trellis Board", "Basecamp Metrics", "Conduit AI",
        "Spire Analytics", "Quarry Data", "Ridgeline Ops", "Keyframe Studio",
        "Helix Pipeline", "Cobalt Scheduler", "Drift Insights", "Archway Platform",
        "Timber Stack", "Campfire Workspace", "Mosaic Hub", "Circuit Logic",
        "Harness Deploy", "Relay Sync", "Pivot Table", "Fulcrum Dashboard",
    ],
    "blog": [
        "The Quiet Reader", "Margin Notes", "Half Light Journal", "Verso Magazine",
        "Longform Weekly", "The Serif Post", "Daybreak Stories", "Ink & Paper",
        "The Morning Dispatch", "Penumbra Review", "Broadsheet", "The Underscore",
    ],
    "ecommerce": [
        "Willow & Thread", "Copper Market", "Salt & Stone Goods", "The Botanist Shop",
        "Linen & Oak", "Forge Supply Co", "Ember & Ash", "Tidewater Trading",
        "Honeycomb Collective", "Birch & Bloom", "Kindling Store", "Thatch & Co",
    ],
    "marketing": [
        "Launchpad Agency", "Signal Creative", "Parallax Studios", "Anthem Brands",
        "Pulse Digital", "Catalyst Group", "Horizon Marketing", "Apex Ventures",
        "Forge & Flame", "Wavelength Co", "Benchmark Creative", "Torque Agency",
    ],
    "portfolio": [
        "Studio Kōen", "Atelier Blanc", "Folio Works", "Offset Gallery",
        "Darkroom Portfolio", "Grain & Grit", "Spectrum Studio", "Lightbox Creative",
        "Aperture Design", "Contrast Works", "Depth of Field", "Silhouette Studio",
    ],
    "documentation": [
        "DevDocs Hub", "API Reference", "Codebase Guide", "Schema Docs",
        "Platform Wiki", "Knowledge Base", "Runbook Central", "Syntax Guide",
    ],
    "enterprise": [
        "Cornerstone Partners", "Atlas Consulting", "Keystone Financial", "Summit Advisory",
        "Blackstone Digital", "Pinnacle Group", "Meridian Capital", "Ironclad Trust",
    ],
    "luxury": [
        "Maison Aurélie", "Velvet & Gold", "Château Blanc", "Noir Atelier",
        "L'Essence Studio", "Opulent Living", "Gilt & Grace", "Atheneum Collection",
        "The Ivory Room", "Soleil Maison", "Marquis Estate", "Bel Canto",
    ],
    "wellness": [
        "Bloom Wellness", "Still Water Spa", "Breathe Studio", "Sage & Soul",
        "Luminous Health", "Zenith Retreat", "Verdant Life", "Morning Dew",
    ],
    "education": [
        "Bright Minds Academy", "Scholar Hub", "Curiosity Lab", "Open Lectures",
        "Learning Arc", "Primer Academy", "Chalk & Board", "Eureka Learning",
    ],
    "gaming": [
        "Neon Rift", "Phantom Arcade", "Voxel Storm", "Dark Circuit",
        "Pixel Forge", "Zero Gravity", "Rust & Ruin", "Abyss Engine",
    ],
    "restaurant": [
        "Ember & Vine", "The Olive Press", "Brine & Barrel", "Saffron Table",
        "Hearth & Grain", "Thistle & Thyme", "The Copper Pot", "Root & Branch",
    ],
    "creative": [
        "Chromatic Studio", "Noise Machine", "Waveform Audio", "Fractal Design Lab",
        "Static Gallery", "The Neon Collective", "Glitch Art Space", "Monolith Press",
        "Echo Chamber", "Brutalist Zine", "Sine Wave", "Parallax Zine",
    ],
}

TYPE_KEYWORDS = {
    "saas": ["saas", "dashboard", "admin", "b2b", "startup", "app", "platform", "tool", "crm", "enterprise saas", "data"],
    "blog": ["blog", "editorial", "content", "magazine", "reading", "journal", "news", "publishing", "literary"],
    "ecommerce": ["e-commerce", "store", "shop", "retail", "product", "shopping", "marketplace"],
    "marketing": ["marketing", "landing", "agency", "campaign", "brand", "launch", "creative agency"],
    "portfolio": ["portfolio", "creative", "design", "photography", "gallery", "art", "studio"],
    "documentation": ["documentation", "docs", "api", "developer", "wiki", "knowledge", "reference"],
    "enterprise": ["corporate", "enterprise", "consulting", "financial", "legal", "trust", "insurance", "banking"],
    "luxury": ["luxury", "fashion", "premium", "elegant", "beauty", "spa", "high-end", "jewel"],
    "wellness": ["wellness", "health", "meditation", "yoga", "spa", "calm", "mindful"],
    "education": ["education", "academic", "school", "learning", "course", "university", "research"],
    "gaming": ["gaming", "game", "esports", "arcade", "pixel", "cyberpunk", "entertainment"],
    "restaurant": ["restaurant", "food", "culinary", "cafe", "hospitality", "menu", "dining"],
    "creative": ["art", "music", "event", "experimental", "brutalist", "zine", "audio", "motion"],
}

SAMPLE_TEXT = {
    "saas": "Monitor your key performance indicators in real-time with actionable insights. Our dashboard brings clarity to complex data, helping teams make informed decisions faster. Track growth metrics, user engagement, and revenue all in one unified view.",
    "blog": "In the golden hour of late afternoon, the city reveals its quieter self. The streets empty of their hurried commuters, and for a brief moment, the architecture speaks louder than the traffic. This is when the best stories find you, not the other way around.",
    "ecommerce": "Crafted with intention and built to last. Every piece in our collection is thoughtfully sourced from artisans who share our commitment to quality materials and sustainable practices. Free shipping on orders over $75.",
    "marketing": "Transform your digital presence with strategies that actually convert. We combine data-driven insights with creative excellence to build brands that resonate with your audience and drive measurable growth.",
    "portfolio": "Selected works spanning identity design, editorial layout, and digital experiences. Each project represents a collaborative journey from concept to completion, guided by a commitment to clarity and craft.",
    "documentation": "This guide covers the core concepts, API endpoints, and integration patterns you need to get started. Follow the quick-start tutorial or dive into the reference docs for detailed specifications.",
    "enterprise": "Delivering trusted advisory services to Fortune 500 companies for over two decades. Our team of experts brings deep industry knowledge and proven methodologies to every engagement.",
    "luxury": "Where craftsmanship meets contemporary design. Each piece is meticulously handcrafted using the finest materials, reflecting generations of artisanal expertise and an unwavering dedication to perfection.",
    "wellness": "Find your center in a space designed for restoration. Our holistic approach combines ancient healing traditions with modern wellness science to create personalized journeys toward balance and vitality.",
    "education": "Unlock your potential with courses designed by leading experts. Our adaptive learning platform meets you where you are and guides you toward mastery through interactive lessons and real-world projects.",
    "gaming": "Enter a world where every decision shapes the narrative. Immersive environments, dynamic combat systems, and a story that adapts to your choices. The next generation of interactive entertainment starts here.",
    "restaurant": "Farm-to-table dining reimagined for the modern palate. Our seasonal menu celebrates the finest local ingredients, prepared with techniques that honor tradition while embracing innovation.",
    "creative": "Pushing boundaries at the intersection of art and technology. Our work explores the tension between digital and analog, creating experiences that challenge perception and invite participation.",
}

TAGLINES = {
    "saas": "Data clarity for modern teams.",
    "blog": "Stories worth your time.",
    "ecommerce": "Crafted with care, built to last.",
    "marketing": "Brands that move people.",
    "portfolio": "Work that speaks for itself.",
    "documentation": "Clear docs, faster builds.",
    "enterprise": "Trusted by industry leaders.",
    "luxury": "The art of the extraordinary.",
    "wellness": "Balance begins here.",
    "education": "Learn without limits.",
    "gaming": "Your story. Your rules.",
    "restaurant": "Seasonal. Local. Exceptional.",
    "creative": "Where art meets code.",
}


def slugify(name):
    return re.sub(r'[^a-z0-9]+', '-', name.lower()).strip('-')


def accent_for(name):
    h = int(hashlib.md5(name.encode()).hexdigest()[:8], 16)
    return ACCENT_COLORS[h % len(ACCENT_COLORS)]


def is_dark(mood_keywords):
    if not mood_keywords:
        return False
    words = {w.strip().lower() for w in mood_keywords.replace("|", ",").split(",")}
    return bool(words & DARK_MOODS)


def classify_pairing(best_for, mood_keywords):
    combined = (best_for + " " + mood_keywords).lower()
    scores = {}
    for ptype, keywords in TYPE_KEYWORDS.items():
        scores[ptype] = sum(1 for kw in keywords if kw in combined)
    best = max(scores, key=scores.get)
    if scores[best] > 0:
        return best
    return "creative"


def load_pairings():
    rows = []
    with open(DATA_DIR / "pairings.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            rows.append(row)
    return rows


def load_tier_a_body_fonts():
    fonts = []
    with open(DATA_DIR / "fonts.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row.get("Quality_Tier", "").strip() == "A" and row.get("Body_Suitable", "").strip() == "Yes":
                fonts.append(row)
    return fonts


def build_project_list():
    random.seed(42)
    pairings = load_pairings()
    tier_a = load_tier_a_body_fonts()
    projects = []
    name_pools = {k: list(v) for k, v in PROJECT_NAMES.items()}

    # 1. All 73 pairings
    for p in pairings:
        ptype = classify_pairing(p.get("Best_For", ""), p.get("Mood_Keywords", ""))
        pool = name_pools.get(ptype, name_pools["creative"])
        if not pool:
            pool = name_pools["creative"]
            if not pool:
                pool = [f"Project {len(projects)+1}"]
        name = pool.pop(0)
        scale = p.get("Scale_Recommendation", "major-third")
        if scale not in SCALES:
            scale = "major-third"
        projects.append({
            "id": slugify(name),
            "name": name,
            "type": ptype,
            "heading_font": p["Heading_Font"],
            "body_font": p["Body_Font"],
            "pairing_name": p["Pairing_Name"],
            "scale": scale,
            "mode": "pair",
            "heading_weights": p.get("Heading_Weights", "400;700").replace(";", ","),
            "body_weights": p.get("Body_Weights", "400;700").replace(";", ","),
            "contrast_type": p.get("Contrast_Type", ""),
            "mood": p.get("Mood_Keywords", ""),
        })

    # 2. 15 single-font entries from Tier A
    single_fonts = tier_a[:15]
    single_scales = ["minor-third", "major-second", "major-third", "minor-second", "perfect-fourth",
                     "major-third", "minor-third", "major-second", "minor-third", "major-third",
                     "major-second", "minor-third", "major-third", "minor-second", "major-second"]
    single_types = ["saas", "documentation", "blog", "saas", "marketing",
                    "blog", "ecommerce", "enterprise", "portfolio", "blog",
                    "saas", "wellness", "education", "saas", "enterprise"]
    for i, font in enumerate(single_fonts):
        ptype = single_types[i] if i < len(single_types) else "saas"
        pool = name_pools.get(ptype, name_pools["creative"])
        if not pool:
            for fallback_type in ["creative", "saas", "blog", "marketing"]:
                if name_pools.get(fallback_type):
                    pool = name_pools[fallback_type]
                    break
            if not pool:
                pool = [f"Single Font Project {i+1}"]
        name = pool.pop(0)
        projects.append({
            "id": slugify(name),
            "name": name,
            "type": ptype,
            "heading_font": font["Family"],
            "body_font": font["Family"],
            "pairing_name": None,
            "scale": single_scales[i] if i < len(single_scales) else "minor-third",
            "mode": "single",
            "heading_weights": font.get("Weight_Range", "400-700").replace("-", ","),
            "body_weights": font.get("Weight_Range", "400-700").replace("-", ","),
            "contrast_type": "Weight",
            "mood": font.get("Mood", ""),
        })

    # 3. 12 alternate-scale variants of popular pairings
    popular_pairings = pairings[:12]
    alt_scales = ["augmented-fourth", "perfect-fifth", "golden-ratio", "minor-second",
                  "perfect-fourth", "augmented-fourth", "perfect-fifth", "golden-ratio",
                  "perfect-fourth", "augmented-fourth", "perfect-fifth", "minor-second"]
    for i, p in enumerate(popular_pairings):
        ptype = classify_pairing(p.get("Best_For", ""), p.get("Mood_Keywords", ""))
        pool = name_pools.get(ptype)
        if not pool:
            for fallback_type in ["creative", "marketing", "blog", "saas"]:
                if name_pools.get(fallback_type):
                    pool = name_pools[fallback_type]
                    break
            if not pool:
                pool = [f"Scale Variant {i+1}"]
        name = pool.pop(0)
        projects.append({
            "id": slugify(name),
            "name": name,
            "type": ptype,
            "heading_font": p["Heading_Font"],
            "body_font": p["Body_Font"],
            "pairing_name": p["Pairing_Name"] + " (alt scale)",
            "scale": alt_scales[i],
            "mode": "pair",
            "heading_weights": p.get("Heading_Weights", "400;700").replace(";", ","),
            "body_weights": p.get("Body_Weights", "400;700").replace(";", ","),
            "contrast_type": p.get("Contrast_Type", ""),
            "mood": p.get("Mood_Keywords", ""),
        })

    return projects


def generate_html_page(project):
    p = project
    heading = p["heading_font"]
    body = p["body_font"]
    is_single = heading == body
    scale_name = p["scale"]
    ratio = SCALES[scale_name]
    sizes = compute_sizes(16, ratio)
    data_dir = str(PROJECT_ROOT)
    heading_fb = get_fallback(heading, data_dir)
    body_fb = get_fallback(body, data_dir) if not is_single else heading_fb

    css_vars = generate_css(heading, body, heading_fb, body_fb, sizes, scale_name, ratio, 16)
    embed = generate_embed(heading, body, p["heading_weights"], p["body_weights"])

    dark = is_dark(p["mood"])
    accent = accent_for(p["name"])
    ptype = p["type"]
    tagline = TAGLINES.get(ptype, "Typography in action.")
    sample = SAMPLE_TEXT.get(ptype, SAMPLE_TEXT["creative"])
    font_label = heading if is_single else f"{heading} + {body}"

    bg = "#0f0f0f" if dark else "#fafafa"
    fg = "#e5e5e5" if dark else "#1a1a1a"
    muted = "#888" if dark else "#666"
    card_bg = "#1a1a1a" if dark else "#fff"
    border = "#333" if dark else "#e5e5e5"

    tiers_html = ""
    for tier in reversed(TIERS):
        tiers_html += f'    <div style="font-size:var(--font-size-{tier});line-height:var(--line-height-{tier});letter-spacing:var(--letter-spacing-{tier});margin-bottom:0.5em;font-family:var(--font-heading, var(--font-body))"><span style="color:{muted};font-size:0.75rem;display:inline-block;width:3rem">{tier}</span> The quick brown fox</div>\n'

    BASE_URL = "https://sliday.github.io/google-fonts-skill"
    og_desc = f"{font_label} | {scale_name} scale | {ptype}"
    og_img = f"{BASE_URL}/og/{p['id']}.png"

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{escape(p['name'])} — Typography Preview</title>
<meta name="description" content="{escape(og_desc)} — Pre-made typography system using Google Fonts">
<link rel="canonical" href="{BASE_URL}/pages/{p['id']}.html">
<meta property="og:title" content="{escape(p['name'])} — Typography Preview">
<meta property="og:description" content="{escape(og_desc)}">
<meta property="og:type" content="website">
<meta property="og:url" content="{BASE_URL}/pages/{p['id']}.html">
<meta property="og:image" content="{og_img}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{escape(p['name'])} — Typography Preview">
<meta name="twitter:description" content="{escape(og_desc)}">
<meta name="twitter:image" content="{og_img}">
<script type="application/ld+json">
{{
  "@context": "https://schema.org",
  "@type": "WebPage",
  "name": "{escape(p['name'])} — Typography Preview",
  "description": "{escape(og_desc)}",
  "url": "{BASE_URL}/pages/{p['id']}.html",
  "isPartOf": {{ "@type": "WebSite", "name": "Google Fonts Skill Showcase", "url": "{BASE_URL}/" }}
}}
</script>
{embed}
<style>
{css_vars}
body {{ margin:0; padding:2rem 2rem 4rem; font-family:var(--font-body); background:{bg}; color:{fg}; }}
.hero {{ max-width:var(--measure-wide); margin:0 auto 3rem; }}
.hero .label {{ font-size:var(--font-size-sm); color:{accent}; letter-spacing:0.05em; text-transform:uppercase; margin-bottom:0.5rem; }}
.hero h1 {{ font-family:var(--font-heading, var(--font-body)); font-size:var(--font-size-4xl); line-height:var(--line-height-4xl); letter-spacing:var(--letter-spacing-4xl); margin:0 0 0.5rem; }}
.hero .tagline {{ font-size:var(--font-size-lg); color:{muted}; line-height:var(--line-height-lg); }}
section {{ max-width:var(--measure-wide); margin:0 auto 3rem; }}
section h2 {{ font-family:var(--font-heading, var(--font-body)); font-size:var(--font-size-xl); line-height:var(--line-height-xl); letter-spacing:var(--letter-spacing-xl); margin-bottom:1rem; color:{accent}; }}
.scale {{ border-left:2px solid {border}; padding-left:1.5rem; }}
.body-sample {{ max-width:var(--measure-base); }}
.body-sample p {{ font-size:var(--font-size-base); line-height:var(--line-height-base); margin-bottom:1em; }}
.meta {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(200px,1fr)); gap:1rem; }}
.meta-card {{ background:{card_bg}; border:1px solid {border}; border-radius:8px; padding:1rem; }}
.meta-card dt {{ font-size:var(--font-size-sm); color:{muted}; margin-bottom:0.25rem; }}
.meta-card dd {{ margin:0; font-weight:600; }}
footer {{ max-width:var(--measure-wide); margin:3rem auto 0; padding-top:1rem; border-top:1px solid {border}; font-size:var(--font-size-sm); color:{muted}; }}
</style>
</head>
<body>
<header class="hero">
  <p class="label">{escape(ptype)} &middot; {escape(scale_name)} &middot; {escape(p['contrast_type'])}</p>
  <h1>{escape(p['name'])}</h1>
  <p class="tagline">{escape(tagline)}</p>
</header>
<section>
  <h2>Type Scale</h2>
  <div class="scale">
{tiers_html}  </div>
</section>
<section>
  <h2>Body Text</h2>
  <div class="body-sample">
    <p>{escape(sample)}</p>
    <p>Good typography is invisible. Great typography is unforgettable. The right typeface sets the tone before a single word is read, guiding the eye and shaping perception with every curve and counter.</p>
  </div>
</section>
<section>
  <h2>Details</h2>
  <div class="meta">
    <div class="meta-card"><dt>Fonts</dt><dd>{escape(font_label)}</dd></div>
    <div class="meta-card"><dt>Scale</dt><dd>{escape(scale_name)} ({ratio})</dd></div>
    <div class="meta-card"><dt>Contrast</dt><dd>{escape(p['contrast_type'])}</dd></div>
    <div class="meta-card"><dt>Mode</dt><dd>{escape(p['mode'])}</dd></div>
  </div>
</section>
<footer>Generated by google-fonts-skill &middot; <a href="https://github.com/sliday/google-fonts-skill" style="color:{accent}">github.com/sliday/google-fonts-skill</a></footer>
</body>
</html>"""
    return html


def generate_index(projects):
    # Collect unique heading fonts for the index page (weight 700 only)
    unique_fonts = set()
    for p in projects:
        unique_fonts.add(p["heading_font"])
    font_families = "&".join(f"family={encode_font(f)}:wght@700" for f in sorted(unique_fonts))
    fonts_url = f"https://fonts.googleapis.com/css2?{font_families}&display=swap"

    # Collect unique project types for filter
    types = sorted(set(p["type"] for p in projects))

    TYPE_COLORS = {
        "blog": "#8B5CF6", "creative": "#A855F7", "documentation": "#06B6D4",
        "ecommerce": "#10B981", "education": "#F59E0B", "enterprise": "#6366F1",
        "gaming": "#EF4444", "luxury": "#E11D48", "marketing": "#F97316",
        "portfolio": "#EC4899", "restaurant": "#14B8A6", "saas": "#3B82F6",
        "wellness": "#10B981",
    }

    cards_html = ""
    for p in projects:
        dark = is_dark(p["mood"])
        card_bg = "#1a1a1a" if dark else "#fff"
        card_fg = "#e5e5e5" if dark else "#1a1a1a"
        muted = "#888" if dark else "#666"
        badge_color = TYPE_COLORS.get(p["type"], "#3B82F6")
        font_label = p["heading_font"] if p["heading_font"] == p["body_font"] else f'{p["heading_font"]} + {p["body_font"]}'
        cards_html += f"""    <a href="pages/{p['id']}.html" class="card" data-type="{p['type']}" style="background:{card_bg};color:{card_fg}">
      <span class="card-badge" style="background:{badge_color}">{escape(p['type'])}</span>
      <h3 style="font-family:'{escape(p['heading_font'])}',sans-serif">{escape(p['name'])}</h3>
      <p class="card-fonts" style="color:{muted}">{escape(font_label)}</p>
      <p class="card-scale" style="color:{muted}">{escape(p['scale'])}</p>
    </a>
"""

    short_labels = {
        "documentation": "docs",
        "ecommerce": "shop",
        "education": "edu",
        "enterprise": "corp",
        "marketing": "mktg",
        "portfolio": "folio",
        "restaurant": "food",
        "wellness": "health",
    }
    filter_buttons = '<button class="filter-btn active" data-filter="all">All</button>\n'
    for t in types:
        label = short_labels.get(t, t)
        filter_buttons += f'      <button class="filter-btn" data-filter="{t}">{label}</button>\n'

    BASE_URL = "https://sliday.github.io/google-fonts-skill"

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Google Fonts Skill — 100 Typography Systems</title>
<meta name="description" content="Browse 100 pre-made typography systems using Google Fonts. Font pairings, modular scales, CSS custom properties, and Tailwind configs — ready to use.">
<link rel="canonical" href="{BASE_URL}/">
<meta property="og:title" content="Google Fonts Skill — 100 Typography Systems">
<meta property="og:description" content="Browse 100 pre-made typography systems with live Google Fonts previews. Font pairings, type scales, CSS + Tailwind output.">
<meta property="og:type" content="website">
<meta property="og:url" content="{BASE_URL}/">
<meta property="og:image" content="{BASE_URL}/og/gallery.png">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="Google Fonts Skill — 100 Typography Systems">
<meta name="twitter:description" content="100 pre-made typography systems with live Google Fonts previews">
<meta name="twitter:image" content="{BASE_URL}/og/gallery.png">
<script type="application/ld+json">
{{
  "@context": "https://schema.org",
  "@type": "CollectionPage",
  "name": "Google Fonts Skill — Showcase Gallery",
  "description": "100 pre-made typography systems using Google Fonts",
  "url": "{BASE_URL}/",
  "numberOfItems": {len(projects)},
  "provider": {{ "@type": "Organization", "name": "sliday", "url": "https://github.com/sliday" }}
}}
</script>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="{fonts_url}" rel="stylesheet">
<style>
* {{ margin:0; padding:0; box-sizing:border-box; }}
body {{ font-family:system-ui,-apple-system,sans-serif; background:#0a0a0a; color:#e5e5e5; padding:2rem; }}
.header {{ max-width:1400px; margin:0 auto 2rem; }}
.header h1 {{ font-size:2rem; margin-bottom:0.5rem; }}
.header p {{ color:#888; }}
.filters {{ max-width:1400px; margin:0 auto 1.5rem; display:flex; flex-wrap:wrap; gap:0.5rem; }}
.filter-btn {{ background:#1a1a1a; border:1px solid #333; color:#888; padding:0.4rem 0.8rem; border-radius:4px; cursor:pointer; font-size:0.8rem; text-transform:uppercase; letter-spacing:0.05em; }}
.filter-btn:hover {{ color:#e5e5e5; border-color:#555; }}
.filter-btn.active {{ background:#3B82F6; color:#fff; border-color:#3B82F6; }}
.grid {{ max-width:1400px; margin:0 auto; display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:1rem; }}
.card {{ display:block; border:1px solid #333; border-radius:8px; padding:1.25rem; text-decoration:none; transition:border-color 0.2s; }}
.card:hover {{ border-color:#555; }}
.card-badge {{ display:inline-block; font-size:0.65rem; color:#fff; padding:0.15rem 0.5rem; border-radius:3px; text-transform:uppercase; letter-spacing:0.05em; margin-bottom:0.75rem; }}
.card h3 {{ font-size:1.25rem; font-weight:700; margin-bottom:0.5rem; color:inherit; }}
.card-fonts {{ font-size:0.8rem; margin-bottom:0.25rem; }}
.card-scale {{ font-size:0.75rem; }}
.count {{ max-width:1400px; margin:0 auto; padding:1.5rem 0 0; color:#555; font-size:0.8rem; }}
</style>
</head>
<body>
<div class="header">
  <h1>Showcase Gallery</h1>
  <p>{len(projects)} pre-made typography systems for fictional projects</p>
</div>
<div class="filters">
  <div id="filters">
    {filter_buttons}  </div>
</div>
<div class="grid" id="grid">
{cards_html}</div>
<p class="count" id="count">{len(projects)} projects</p>
<script>
document.getElementById('filters').addEventListener('click', e => {{
  if (!e.target.classList.contains('filter-btn')) return;
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
  e.target.classList.add('active');
  const f = e.target.dataset.filter;
  let n = 0;
  document.querySelectorAll('.card').forEach(c => {{
    const show = f === 'all' || c.dataset.type === f;
    c.style.display = show ? '' : 'none';
    if (show) n++;
  }});
  document.getElementById('count').textContent = n + ' projects';
}});
</script>
</body>
</html>"""
    return html


def main():
    projects = build_project_list()
    print(f"Built {len(projects)} projects", file=sys.stderr)

    SHOWCASE_DIR.mkdir(exist_ok=True)
    PAGES_DIR.mkdir(exist_ok=True)

    # Generate individual pages
    for p in projects:
        html = generate_html_page(p)
        path = PAGES_DIR / f"{p['id']}.html"
        path.write_text(html, encoding="utf-8")

    # Generate index
    index_html = generate_index(projects)
    (SHOWCASE_DIR / "index.html").write_text(index_html, encoding="utf-8")

    # Generate manifest
    manifest = {
        "version": "1.0",
        "generated": "2026-03-21",
        "count": len(projects),
        "projects": [
            {
                "id": p["id"],
                "name": p["name"],
                "type": p["type"],
                "heading_font": p["heading_font"],
                "body_font": p["body_font"],
                "pairing_name": p["pairing_name"],
                "scale": p["scale"],
                "mode": p["mode"],
                "contrast_type": p["contrast_type"],
                "html_file": f"pages/{p['id']}.html",
            }
            for p in projects
        ],
    }
    (SHOWCASE_DIR / "showcase.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    # Generate sitemap.xml
    BASE_URL = "https://sliday.github.io/google-fonts-skill"
    sitemap_lines = ['<?xml version="1.0" encoding="UTF-8"?>']
    sitemap_lines.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')
    sitemap_lines.append(f"  <url><loc>{BASE_URL}/</loc><priority>1.0</priority></url>")
    for p in projects:
        sitemap_lines.append(f"  <url><loc>{BASE_URL}/pages/{p['id']}.html</loc><priority>0.7</priority></url>")
    sitemap_lines.append("</urlset>")
    (SHOWCASE_DIR / "sitemap.xml").write_text("\n".join(sitemap_lines), encoding="utf-8")

    # Generate robots.txt
    robots = f"User-agent: *\nAllow: /\nSitemap: {BASE_URL}/sitemap.xml\n"
    (SHOWCASE_DIR / "robots.txt").write_text(robots, encoding="utf-8")

    # Generate llms.txt (LLM-friendly discovery)
    llms_lines = [
        "# Google Fonts Skill — Showcase Gallery",
        "",
        "100 pre-made typography systems using Google Fonts.",
        "Each project includes CSS custom properties, Tailwind config, and Google Fonts embed links.",
        "",
        "## Gallery Index",
        f"- {BASE_URL}/",
        "",
        "## Machine-Readable Data",
        f"- {BASE_URL}/showcase.json",
        "",
        "## Project Types",
        "saas, blog, ecommerce, marketing, portfolio, documentation, enterprise, luxury, wellness, education, gaming, restaurant, creative",
        "",
        "## Individual Projects",
    ]
    for p in projects:
        font_label = p["heading_font"] if p["heading_font"] == p["body_font"] else f'{p["heading_font"]} + {p["body_font"]}'
        llms_lines.append(f"- {p['name']}: {font_label} | {p['scale']} | {BASE_URL}/pages/{p['id']}.html")
    (SHOWCASE_DIR / "llms.txt").write_text("\n".join(llms_lines), encoding="utf-8")

    print(f"Generated {len(projects)} pages in {PAGES_DIR}", file=sys.stderr)
    print(f"Gallery index: {SHOWCASE_DIR / 'index.html'}", file=sys.stderr)
    print(f"Manifest: {SHOWCASE_DIR / 'showcase.json'}", file=sys.stderr)
    print(f"SEO: sitemap.xml, robots.txt, llms.txt", file=sys.stderr)


if __name__ == "__main__":
    main()
