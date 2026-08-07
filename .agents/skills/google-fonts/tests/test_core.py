"""Tests for google_fonts_mcp.core."""

from google_fonts_mcp.core import (
    search_fonts, compute_sizes, get_fallback, generate_css,
    generate_embed, SCALES, _load_csv,
)


def test_search_single():
    results = search_fonts("modern clean", mode="single", max_results=3)
    assert len(results) > 0
    assert "Family" in results[0]


def test_search_pair():
    results = search_fonts("elegant luxury", mode="pair", max_results=3)
    assert len(results) > 0
    assert "Heading_Font" in results[0]


def test_search_scale():
    results = search_fonts("dense compact", mode="scale", max_results=2)
    assert len(results) > 0
    assert "Scale_Name" in results[0]


def test_lookup():
    results = search_fonts("Inter", mode="lookup")
    assert len(results) == 1
    assert results[0]["Family"] == "Inter"


def test_compute_sizes():
    sizes = compute_sizes(16, 1.25)
    assert sizes["base"] == 1.0
    assert sizes["lg"] > 1.0
    assert sizes["sm"] < 1.0


def test_get_fallback():
    fb = get_fallback("Inter")
    assert fb == "sans-serif"


def test_generate_css():
    sizes = compute_sizes(16, SCALES["major-third"])
    css = generate_css("Inter", "Inter", "sans-serif", "sans-serif", sizes, "major-third", 1.25, 16)
    assert ":root {" in css
    assert "--font-body" in css
    assert "--font-size-base: 1rem" in css


def test_generate_embed():
    embed = generate_embed("Playfair Display", "Inter", "400,700", "300,400,700")
    assert "fonts.googleapis.com" in embed
    assert "Playfair+Display" in embed
    assert "Inter" in embed


def test_load_scales():
    scales = _load_csv("scales")
    assert len(scales) == 8


def test_load_pairings():
    pairings = _load_csv("pairings")
    assert len(pairings) == 73


def test_load_fonts():
    fonts = _load_csv("fonts")
    assert len(fonts) > 1900
