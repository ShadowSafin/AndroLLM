#!/usr/bin/env python3
"""Generate OG images for showcase pages using Replicate nano-banana-2."""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent
SHOWCASE_DIR = PROJECT_ROOT / "showcase"
OG_DIR = SHOWCASE_DIR / "og"
MANIFEST = SHOWCASE_DIR / "showcase.json"

API_TOKEN = os.environ.get("REPLICATE_API_TOKEN", "")
MODEL = "google/nano-banana-2"
API_URL = "https://api.replicate.com/v1/models/google/nano-banana-2/predictions"

STYLE_PROMPTS = {
    "saas": "minimalist abstract gradient background, tech dashboard aesthetic, clean geometric shapes, blue tones, professional",
    "blog": "warm editorial layout, stacked paper textures, reading glasses, serif typography mood, cream and brown tones",
    "ecommerce": "product photography aesthetic, natural materials, warm lighting, artisanal craft, earth tones",
    "marketing": "bold dynamic abstract, energetic gradient, modern agency vibes, vibrant colors, striking composition",
    "portfolio": "gallery exhibition space, clean white walls, artistic spotlighting, minimalist creative",
    "documentation": "organized knowledge, blueprint aesthetic, structured grid, navy and white, precise",
    "enterprise": "corporate skyline, polished marble, trust and authority, slate and gold tones",
    "luxury": "black velvet texture, gold accents, premium craftsmanship, haute couture, opulent",
    "wellness": "serene natural landscape, soft focus botanicals, calm water, green and sage tones, peaceful",
    "education": "bright classroom, open books, curiosity and discovery, warm welcoming colors",
    "gaming": "neon cyberpunk cityscape, glowing circuits, futuristic HUD elements, dark with bright accents",
    "restaurant": "rustic kitchen, fresh ingredients, warm candlelight, wood textures, appetizing ambiance",
    "creative": "abstract paint splatter, mixed media collage, raw artistic expression, bold experimental",
}


def log(msg):
    print(msg, file=sys.stderr)


def create_prediction(prompt):
    data = json.dumps({"input": {"prompt": prompt, "aspect_ratio": "16:9"}}).encode()
    req = urllib.request.Request(
        API_URL,
        data=data,
        headers={
            "Authorization": f"Bearer {API_TOKEN}",
            "Content-Type": "application/json",
            "Prefer": "wait",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode() if hasattr(e, 'read') else str(e)
        log(f"  HTTP {e.code}: {body}")
        return None
    except Exception as e:
        log(f"  Error: {e}")
        return None


def poll_prediction(prediction_url):
    for _ in range(60):
        req = urllib.request.Request(
            prediction_url,
            headers={"Authorization": f"Bearer {API_TOKEN}"},
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                result = json.loads(resp.read().decode())
            status = result.get("status")
            if status == "succeeded":
                return result.get("output")
            if status in ("failed", "canceled"):
                log(f"  Prediction {status}")
                return None
            time.sleep(2)
        except Exception as e:
            log(f"  Poll error: {e}")
            time.sleep(3)
    return None


def download_image(url, path):
    req = urllib.request.Request(url, headers={"User-Agent": "google-fonts-skill/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        path.write_bytes(resp.read())


def main():
    if not API_TOKEN:
        log("ERROR: REPLICATE_API_TOKEN not set")
        sys.exit(1)

    if not MANIFEST.exists():
        log("ERROR: showcase.json not found. Run generate-showcase.py first.")
        sys.exit(1)

    manifest = json.loads(MANIFEST.read_text())
    projects = manifest["projects"]
    OG_DIR.mkdir(exist_ok=True)

    # Check which images already exist (skip them)
    existing = {f.stem for f in OG_DIR.glob("*.png")}

    # Generate gallery OG image first
    if "gallery" not in existing:
        log("Generating gallery OG image...")
        prompt = "typography specimen sheet, multiple font samples displayed on elegant dark background, modular scale visualization, professional design showcase, 16:9 aspect ratio"
        result = create_prediction(prompt)
        if result:
            output = result.get("output")
            if not output:
                output = poll_prediction(result.get("urls", {}).get("get", ""))
            if output:
                img_url = output if isinstance(output, str) else output[0]
                download_image(img_url, OG_DIR / "gallery.png")
                log("  Saved gallery.png")
    else:
        log("Skipping gallery.png (exists)")

    # Generate per-project OG images
    total = len(projects)
    for i, p in enumerate(projects):
        pid = p["id"]
        if pid in existing:
            log(f"[{i+1}/{total}] Skipping {pid} (exists)")
            continue

        ptype = p.get("type", "creative")
        style = STYLE_PROMPTS.get(ptype, STYLE_PROMPTS["creative"])
        prompt = f"{style}, typography preview for {p['name']}, {p.get('heading_font', '')} font, 16:9 aspect ratio, high quality"

        log(f"[{i+1}/{total}] Generating {pid}...")
        result = create_prediction(prompt)
        if result:
            output = result.get("output")
            if not output:
                output = poll_prediction(result.get("urls", {}).get("get", ""))
            if output:
                img_url = output if isinstance(output, str) else output[0]
                download_image(img_url, OG_DIR / f"{pid}.png")
                log(f"  Saved {pid}.png")
            else:
                log(f"  Failed to get output for {pid}")
        else:
            log(f"  Failed to create prediction for {pid}")

        # Rate limiting
        time.sleep(0.5)

    generated = len(list(OG_DIR.glob("*.png")))
    log(f"\nDone! {generated} OG images in {OG_DIR}")


if __name__ == "__main__":
    main()
