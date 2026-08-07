"""Core search engine and CSS generation for Google Fonts MCP."""

import csv
import importlib.resources
import re
from functools import lru_cache
from math import log
from collections import defaultdict
from pathlib import Path

MAX_RESULTS = 5


def _data_dir() -> Path:
    return Path(str(importlib.resources.files("google_fonts_mcp") / "data"))


SCALES = {
    "minor-second":     1.067,
    "major-second":     1.125,
    "minor-third":      1.200,
    "major-third":      1.250,
    "perfect-fourth":   1.333,
    "augmented-fourth": 1.414,
    "perfect-fifth":    1.500,
    "golden-ratio":     1.618,
}

TIERS = ["xs", "sm", "base", "lg", "xl", "2xl", "3xl", "4xl"]

LINE_HEIGHTS = {
    "xs": 1.6, "sm": 1.6, "base": 1.5, "lg": 1.45,
    "xl": 1.35, "2xl": 1.25, "3xl": 1.15, "4xl": 1.1,
}

LETTER_SPACINGS = {
    "xs": "0.01em", "sm": "0.01em", "base": "0em", "lg": "-0.005em",
    "xl": "-0.01em", "2xl": "-0.015em", "3xl": "-0.02em", "4xl": "-0.025em",
}

MARGIN_BOTTOMS = {
    "xs": "0.5em", "sm": "0.5em", "base": "0.75em", "lg": "1em",
    "xl": "1.25em", "2xl": "1.5em", "3xl": "1.75em", "4xl": "2em",
}

CSV_CONFIG = {
    "fonts": {
        "file": "fonts.csv",
        "search_cols": ["Family", "Category", "Stroke", "Personality", "Expressive", "Mood", "Best_For", "Keywords", "Subsets"],
        "output_cols": ["Family", "Category", "Stroke", "Personality", "Contrast", "Width", "Styles", "Weight_Range", "Variable", "Variable_Axes", "Body_Suitable", "Quality_Tier", "Popularity_Rank", "Mood", "Best_For", "Google_Fonts_URL", "CSS_Import"]
    },
    "pairings": {
        "file": "pairings.csv",
        "search_cols": ["Pairing_Name", "Category", "Mood_Keywords", "Best_For", "Heading_Font", "Body_Font", "Contrast_Type"],
        "output_cols": ["Pairing_Name", "Category", "Heading_Font", "Body_Font", "Mood_Keywords", "Best_For", "Contrast_Type", "Scale_Recommendation", "Heading_Weights", "Body_Weights", "Google_Fonts_URL", "CSS_Import", "Notes"]
    },
    "scales": {
        "file": "scales.csv",
        "search_cols": ["Scale_Name", "Best_For", "Mood"],
        "output_cols": ["Scale_Name", "Ratio", "Best_For", "Mood", "Sizes_rem", "Line_Heights", "Letter_Spacing_em", "Margin_Below_em"]
    }
}


class BM25:
    def __init__(self, k1=1.5, b=0.75):
        self.k1 = k1
        self.b = b
        self.doc_len = []
        self.doc_freqs = []
        self.idf = {}
        self.avg_dl = 0
        self.corpus_size = 0

    def fit(self, corpus):
        self.corpus_size = len(corpus)
        df = defaultdict(int)
        for doc in corpus:
            tokens = self._tokenize(doc)
            self.doc_len.append(len(tokens))
            freqs = defaultdict(int)
            for t in tokens:
                freqs[t] += 1
            self.doc_freqs.append(freqs)
            for t in set(tokens):
                df[t] += 1
        self.avg_dl = sum(self.doc_len) / self.corpus_size if self.corpus_size else 1
        for term, freq in df.items():
            self.idf[term] = log((self.corpus_size - freq + 0.5) / (freq + 0.5) + 1)

    def score(self, query):
        tokens = self._tokenize(query)
        scores = []
        for i in range(self.corpus_size):
            s = 0
            dl = self.doc_len[i]
            for t in tokens:
                if t not in self.doc_freqs[i]:
                    continue
                tf = self.doc_freqs[i][t]
                idf = self.idf.get(t, 0)
                num = tf * (self.k1 + 1)
                den = tf + self.k1 * (1 - self.b + self.b * dl / self.avg_dl)
                s += idf * num / den
            scores.append(s)
        return scores

    @staticmethod
    def _tokenize(text):
        return re.findall(r'[a-z0-9]+', text.lower())


# Cached data loading and BM25 index
_csv_cache: dict[str, list[dict]] = {}
_bm25_cache: dict[str, tuple[BM25, list[dict]]] = {}


def _load_csv(config_key):
    if config_key in _csv_cache:
        return _csv_cache[config_key]
    cfg = CSV_CONFIG[config_key]
    path = _data_dir() / cfg["file"]
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    _csv_cache[config_key] = rows
    return rows


def _get_bm25(config_key):
    if config_key in _bm25_cache:
        return _bm25_cache[config_key]
    cfg = CSV_CONFIG[config_key]
    rows = _load_csv(config_key)
    corpus = [" ".join(row.get(col, "") for col in cfg["search_cols"]) for row in rows]
    bm25 = BM25()
    bm25.fit(corpus)
    _bm25_cache[config_key] = (bm25, rows)
    return bm25, rows


def _search_csv(query, config_key, max_results=MAX_RESULTS):
    cfg = CSV_CONFIG[config_key]
    bm25, rows = _get_bm25(config_key)
    if not rows:
        return []
    scores = bm25.score(query)
    ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)
    results = []
    for idx, sc in ranked[:max_results]:
        if sc <= 0:
            break
        out = {}
        for col in cfg["output_cols"]:
            if col in rows[idx]:
                out[col] = rows[idx][col]
        out["_score"] = round(sc, 4)
        results.append(out)
    return results


def search_fonts(query, mode="single", tier=None, max_results=5):
    if mode == "lookup":
        rows = _load_csv("fonts")
        cfg = CSV_CONFIG["fonts"]
        q_lower = query.strip().lower()
        for row in rows:
            if row.get("Family", "").strip().lower() == q_lower:
                return [{col: row[col] for col in cfg["output_cols"] if col in row}]
        return []

    if mode == "pair":
        return _search_csv(query, "pairings", max_results)

    if mode == "scale":
        return _search_csv(query, "scales", max_results)

    pool_size = 200 if tier else max_results * 5
    results = _search_csv(query, "fonts", pool_size)
    filtered = []
    for r in results:
        if r.get("Body_Suitable", "").strip().lower() != "yes":
            continue
        if tier and r.get("Quality_Tier", "").strip().upper() != tier.upper():
            continue
        filtered.append(r)
        if len(filtered) >= max_results:
            break
    if not filtered and tier:
        for r in results:
            if r.get("Quality_Tier", "").strip().upper() != tier.upper():
                continue
            filtered.append(r)
            if len(filtered) >= max_results:
                break
    if not filtered and not tier:
        filtered = results[:max_results]
    return filtered


def compute_sizes(base, ratio):
    sizes = {}
    powers = {"xs": -2, "sm": -1, "base": 0, "lg": 1, "xl": 2, "2xl": 3, "3xl": 4, "4xl": 5}
    for tier, power in powers.items():
        sizes[tier] = round((base * (ratio ** power)) / base, 4)
    return sizes


def lookup_category(font_name):
    rows = _load_csv("fonts")
    for row in rows:
        if row.get("Family", "").strip().lower() == font_name.strip().lower():
            return row.get("Category")
    return None


def get_fallback(font_name):
    cat = lookup_category(font_name)
    if cat:
        cat_lower = cat.lower()
        if "serif" in cat_lower and "sans" not in cat_lower:
            return "serif"
        if "sans" in cat_lower:
            return "sans-serif"
        if "mono" in cat_lower:
            return "monospace"
    return "sans-serif"


def encode_font(name):
    return name.strip().replace(" ", "+")


def fmt_rem(val):
    return f"{val:.4f}".rstrip("0").rstrip(".")


def generate_css(heading, body, heading_fb, body_fb, sizes, scale_name, ratio, base):
    lines = ["/* Typography System — Generated by google-fonts-mcp */",
             f"/* Scale: {scale_name} ({ratio}) | Base: {base}px */", "", ":root {",
             "  /* Font Families */"]
    if heading and body and heading != body:
        lines.append(f"  --font-heading: '{heading}', {heading_fb};")
        lines.append(f"  --font-body: '{body}', {body_fb};")
    else:
        font = heading or body
        fb = heading_fb or body_fb
        lines.append(f"  --font-body: '{font}', {fb};")
    lines.append("")
    lines.append("  /* Font Sizes */")
    for tier in TIERS:
        lines.append(f"  --font-size-{tier}: {fmt_rem(sizes[tier])}rem;")
    lines.append("")
    lines.append("  /* Line Heights */")
    for tier in TIERS:
        lines.append(f"  --line-height-{tier}: {LINE_HEIGHTS[tier]};")
    lines.append("")
    lines.append("  /* Letter Spacing */")
    for tier in TIERS:
        lines.append(f"  --letter-spacing-{tier}: {LETTER_SPACINGS[tier]};")
    lines.append("")
    lines += ["  /* Measure */", "  --measure-narrow: 45ch;",
              "  --measure-base: 65ch;", "  --measure-wide: 75ch;", "}"]
    return "\n".join(lines)


def generate_tailwind(heading, body, heading_fb, body_fb, sizes, scale_name, ratio, base):
    lines = ["// tailwind.config.js extension",
             f"// Scale: {scale_name} ({ratio}) | Base: {base}px",
             "module.exports = {", "  theme: {", "    extend: {", "      fontFamily: {"]
    if heading and body and heading != body:
        lines.append(f"        heading: ['{heading}', '{heading_fb}'],")
        lines.append(f"        body: ['{body}', '{body_fb}'],")
    else:
        font = heading or body
        fb = heading_fb or body_fb
        lines.append(f"        body: ['{font}', '{fb}'],")
    lines.append("      },")
    lines.append("      fontSize: {")
    for tier in TIERS:
        ls = LETTER_SPACINGS[tier]
        lh = str(LINE_HEIGHTS[tier])
        lines.append(f"        '{tier}': ['{fmt_rem(sizes[tier])}rem', {{ lineHeight: '{lh}', letterSpacing: '{ls}' }}],")
    lines += ["      },", "    },", "  },", "}"]
    return "\n".join(lines)


def generate_embed(heading, body, heading_weights, body_weights):
    lines = ['<link rel="preconnect" href="https://fonts.googleapis.com">',
             '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>']
    families = []
    if heading:
        families.append(f"family={encode_font(heading)}:wght@{heading_weights}")
    if body and body != heading:
        families.append(f"family={encode_font(body)}:wght@{body_weights}")
    url = "https://fonts.googleapis.com/css2?" + "&".join(families) + "&display=swap"
    lines.append(f'<link href="{url}" rel="stylesheet">')
    return "\n".join(lines)
