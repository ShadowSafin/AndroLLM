"""Tests for google_fonts_mcp.server tools."""

from google_fonts_mcp.server import (
    search_fonts, generate_typography_system, lookup_font,
    list_scales, list_pairings,
)


def test_search_fonts_tool():
    results = search_fonts("modern SaaS dashboard", mode="single", max_results=3)
    assert isinstance(results, list)
    assert len(results) > 0


def test_generate_typography_system_single():
    result = generate_typography_system(heading="Inter", scale="major-third", format="all")
    assert "css" in result
    assert "tailwind" in result
    assert "embed" in result
    assert "--font-body" in result["css"]


def test_generate_typography_system_pair():
    result = generate_typography_system(
        heading="Playfair Display", body="Inter",
        scale="perfect-fourth", format="css"
    )
    assert "css" in result
    assert "--font-heading" in result["css"]


def test_lookup_font_exists():
    result = lookup_font("Inter")
    assert result is not None
    assert result["Family"] == "Inter"


def test_lookup_font_missing():
    result = lookup_font("NonExistentFont12345")
    assert result is None


def test_list_scales():
    scales = list_scales()
    assert len(scales) == 8
    assert scales[0]["Scale_Name"] is not None


def test_list_pairings_all():
    pairings = list_pairings()
    assert len(pairings) == 73


def test_list_pairings_filtered():
    pairings = list_pairings(category="Structure")
    assert len(pairings) > 0
    assert all(p["Contrast_Type"] == "Structure" for p in pairings)
