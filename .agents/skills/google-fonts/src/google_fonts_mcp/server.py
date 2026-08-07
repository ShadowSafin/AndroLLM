"""Google Fonts MCP Server — Typography system generator for agents."""

import sys

from fastmcp import FastMCP

from google_fonts_mcp.core import (
    SCALES,
    search_fonts as _search_fonts,
    compute_sizes,
    get_fallback,
    generate_css,
    generate_tailwind,
    generate_embed,
    _load_csv,
)

mcp = FastMCP("google-fonts")


def _print_banner():
    try:
        from rich.console import Console, Group
        from rich.panel import Panel
        from rich.table import Table
        from rich.text import Text

        console = Console(stderr=True)

        fonts = _load_csv("fonts")
        pairings = _load_csv("pairings")
        body_suitable = sum(1 for f in fonts if f.get("Body_Suitable") == "Yes")
        tier_a = sum(1 for f in fonts if f.get("Quality_Tier") == "A")

        # Header
        header = Text()
        header.append("  google-fonts-mcp", style="bold cyan")
        from google_fonts_mcp import __version__; header.append(f"  v{__version__}", style="dim")

        # What this is
        desc = Text("  Typography system generator for AI agents", style="italic dim")

        # Stats bar
        stats = Text()
        stats.append(f"  {len(fonts):,}", style="bold white")
        stats.append(" fonts  ", style="dim")
        stats.append(f"{len(pairings)}", style="bold white")
        stats.append(" pairings  ", style="dim")
        stats.append(f"{len(SCALES)}", style="bold white")
        stats.append(" scales  ", style="dim")
        stats.append(f"{tier_a}", style="bold white")
        stats.append(" tier-A  ", style="dim")
        stats.append(f"{body_suitable}", style="bold white")
        stats.append(" body-suitable", style="dim")

        # Tools table
        tools_header = Text("  Available tools:", style="bold white")
        tools = Table(show_header=False, box=None, padding=(0, 2, 0, 2))
        tools.add_column(style="cyan bold", min_width=32)
        tools.add_column(style="dim")
        tools.add_row("search_fonts", "Search by mood, use case, or style")
        tools.add_row("generate_typography_system", "CSS + Tailwind + embed link")
        tools.add_row("lookup_font", "Full metadata for any font")
        tools.add_row("list_scales", "8 modular type scales")
        tools.add_row("list_pairings", "73 proven font pairs")

        # Next steps
        next_steps = Text()
        next_steps.append("\n  How to connect:\n", style="bold white")
        next_steps.append("  Claude Code  ", style="dim")
        next_steps.append("claude mcp add google-fonts -- uvx google-fonts-mcp\n", style="green")
        next_steps.append("  Cursor       ", style="dim")
        next_steps.append("Add to .cursor/mcp.json\n", style="green")
        next_steps.append("  Any client   ", style="dim")
        next_steps.append("Connect via stdio to this process\n", style="green")

        links = Text()
        links.append("\n  Docs   ", style="dim")
        links.append("https://github.com/sliday/google-fonts-skill", style="blue underline")
        links.append("\n  Gallery ", style="dim")
        links.append("https://sliday.github.io/google-fonts-skill/", style="blue underline")

        content = Group(header, desc, Text(), stats, Text(), tools_header, tools, next_steps, links)

        panel = Panel(
            content,
            border_style="blue",
            padding=(1, 1),
        )
        console.print(panel)
        console.print("[dim]  Server ready. Waiting for MCP client connection (stdio)...[/dim]\n")
    except ImportError:
        print("google-fonts-mcp v1.1.0 | 1,923 fonts | 73 pairings | 5 tools", file=sys.stderr)
        print("Connect: claude mcp add google-fonts -- uvx google-fonts-mcp", file=sys.stderr)
        print("Waiting for MCP client connection (stdio)...", file=sys.stderr)


@mcp.tool
def search_fonts(
    query: str,
    mode: str = "single",
    tier: str | None = None,
    max_results: int = 5,
) -> list[dict]:
    """Search Google Fonts by description, mood, or use case.

    Modes:
    - single: Body-suitable fonts for heading + body (default)
    - pair: Proven font pairings with contrast type
    - scale: Typographic scales by use case

    Tier filter (A/B/C) applies to single mode only.
    """
    return _search_fonts(query, mode=mode, tier=tier, max_results=max_results)


@mcp.tool
def generate_typography_system(
    heading: str,
    body: str | None = None,
    scale: str = "major-third",
    base: int = 16,
    heading_weights: str = "400,700",
    body_weights: str = "300,400,500,600,700",
    format: str = "all",
) -> dict:
    """Generate a complete typography system from font selection + scale.

    Returns CSS custom properties, Tailwind config, and/or Google Fonts embed HTML.
    Format: css, tailwind, embed, or all.
    """
    if body is None:
        body = heading
    ratio = SCALES.get(scale, 1.25)
    sizes = compute_sizes(base, ratio)
    heading_fb = get_fallback(heading)
    body_fb = get_fallback(body) if body != heading else heading_fb

    result = {}
    if format in ("css", "all"):
        result["css"] = generate_css(heading, body, heading_fb, body_fb, sizes, scale, ratio, base)
    if format in ("tailwind", "all"):
        result["tailwind"] = generate_tailwind(heading, body, heading_fb, body_fb, sizes, scale, ratio, base)
    if format in ("embed", "all"):
        result["embed"] = generate_embed(heading, body, heading_weights, body_weights)
    return result


@mcp.tool
def lookup_font(name: str) -> dict | None:
    """Look up a specific Google Font by exact name. Returns full metadata."""
    results = _search_fonts(name, mode="lookup")
    return results[0] if results else None


@mcp.tool
def list_scales() -> list[dict]:
    """Return all 8 typographic scales with ratios and use-case recommendations."""
    return _load_csv("scales")


@mcp.tool
def list_pairings(category: str | None = None) -> list[dict]:
    """Return all 73 proven font pairings. Optionally filter by contrast type (Structure, Proportion, Era, Weight)."""
    rows = _load_csv("pairings")
    if category:
        cat_lower = category.strip().lower()
        rows = [r for r in rows if r.get("Contrast_Type", "").strip().lower() == cat_lower]
    return rows


def main():
    _print_banner()
    mcp.run(show_banner=False)


if __name__ == "__main__":
    main()
