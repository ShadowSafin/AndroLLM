#!/usr/bin/env python3
"""CLI wrapper for typography CSS generation."""

import argparse
import os
import sys
from core import (
    SCALES, compute_sizes, generate_css, generate_tailwind,
    generate_embed, get_fallback,
)


def main():
    parser = argparse.ArgumentParser(
        description="Generate typography CSS, Tailwind config, and Google Fonts embed links."
    )

    font_group = parser.add_mutually_exclusive_group(required=True)
    font_group.add_argument("--font", help="Single font family (mutually exclusive with --heading/--body)")
    font_group.add_argument("--heading", help="Heading font family name")

    parser.add_argument("--body", help="Body font family name")
    parser.add_argument("--heading-weights", default="400,700", help="Weights for heading (default: 400,700)")
    parser.add_argument("--body-weights", default="300,400,500,600,700", help="Weights for body (default: 300,400,500,600,700)")
    parser.add_argument("--scale", default="major-third", choices=SCALES.keys(), help="Typographic scale (default: major-third)")
    parser.add_argument("--base", type=int, default=16, help="Base font size in px (default: 16)")
    parser.add_argument("--format", default="all", choices=["css", "tailwind", "embed", "all"], help="Output format (default: all)")

    args = parser.parse_args()

    if args.font:
        heading = args.font
        body = args.font
        heading_weights = args.heading_weights
        body_weights = args.body_weights
    else:
        heading = args.heading
        body = args.body
        if not body:
            parser.error("--body is required when using --heading")
        heading_weights = args.heading_weights
        body_weights = args.body_weights

    ratio = SCALES[args.scale]
    sizes = compute_sizes(args.base, ratio)

    script_dir = os.path.dirname(os.path.abspath(__file__))
    data_dir = os.path.dirname(script_dir)

    heading_fb = get_fallback(heading, data_dir)
    body_fb = get_fallback(body, data_dir) if body != heading else heading_fb

    outputs = []

    if args.format in ("css", "all"):
        outputs.append(generate_css(heading, body, heading_fb, body_fb, sizes, args.scale, ratio, args.base))

    if args.format in ("tailwind", "all"):
        outputs.append(generate_tailwind(heading, body, heading_fb, body_fb, sizes, args.scale, ratio, args.base))

    if args.format in ("embed", "all"):
        outputs.append(generate_embed(heading, body, heading_weights, body_weights))

    print("\n\n".join(outputs))


if __name__ == "__main__":
    main()
