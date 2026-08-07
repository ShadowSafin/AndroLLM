#!/usr/bin/env python3
"""CLI wrapper for auto-google-font search engine."""

import argparse
import sys
from core import search_fonts

FIELD_ORDER = {
    "single": ["Family", "Category", "Stroke", "Personality", "Mood", "Best_For", "Quality_Tier", "Popularity_Rank", "Weight_Range", "Variable", "Google_Fonts_URL"],
    "pair": ["Pairing_Name", "Heading_Font", "Body_Font", "Category", "Mood_Keywords", "Best_For", "Contrast_Type", "Scale_Recommendation", "Heading_Weights", "Body_Weights", "Google_Fonts_URL", "CSS_Import"],
    "lookup": ["Family", "Category", "Stroke", "Personality", "Contrast", "Width", "Styles", "Weight_Range", "Variable", "Variable_Axes", "Body_Suitable", "Quality_Tier", "Popularity_Rank", "Mood", "Best_For", "Google_Fonts_URL", "CSS_Import"],
    "scale": ["Scale_Name", "Ratio", "Best_For", "Mood", "Sizes_rem", "Line_Heights", "Letter_Spacing_em", "Margin_Below_em"],
}


def format_result(result, mode):
    fields = FIELD_ORDER.get(mode, list(result.keys()))
    lines = []
    for f in fields:
        if f in result and result[f]:
            lines.append(f"  {f}: {result[f]}")
    score = result.get("_score")
    if score is not None:
        lines.append(f"  (score: {score})")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Search Google Fonts database")
    parser.add_argument("query", help="Search query string")
    parser.add_argument("--mode", choices=["single", "pair", "lookup", "scale"], default="single", help="Search mode (default: single)")
    parser.add_argument("--tier", choices=["A", "B", "C"], default=None, help="Quality tier filter")
    parser.add_argument("--max", type=int, default=5, dest="max_results", help="Max results (default: 5)")
    args = parser.parse_args()

    results = search_fonts(args.query, mode=args.mode, tier=args.tier, max_results=args.max_results)

    if not results:
        print(f"No results found for '{args.query}' (mode={args.mode})")
        sys.exit(0)

    mode_labels = {"single": "Fonts", "pair": "Pairings", "lookup": "Font Details", "scale": "Type Scales"}
    print(f"\n{mode_labels[args.mode]} matching '{args.query}'")
    if args.tier:
        print(f"  [filtered to tier {args.tier}]")
    print(f"  ({len(results)} result{'s' if len(results) != 1 else ''})\n")

    for i, r in enumerate(results, 1):
        print(f"--- {i} ---")
        print(format_result(r, args.mode))
        print()


if __name__ == "__main__":
    main()
