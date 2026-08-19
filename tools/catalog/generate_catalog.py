"""Generate catalog_v1.json from the curated model list + live Hugging Face metadata.

Usage: python tools/catalog/generate_catalog.py
Output: core/models/src/main/assets/catalog_v1.json

Data per repo comes from the Hugging Face Hub API:
  - GET /api/models/{repo}            -> downloads, likes, trendingScore, tags, gated, createdAt
  - GET /api/models/{repo}/tree/{rev} -> file sizes + LFS oid (sha256)

Human-curated fields (family, architecture, categories, context length, etc.)
come from curated_models.json; popularity and file facts come from the API.
"""
import argparse
import json
import re
import sys
import time
import urllib.parse
from datetime import datetime
import requests

API = "https://huggingface.co"
QUANT_PREFERENCE = [
    "Q4_K_M", "Q5_K_M", "Q4_0", "Q8_0", "Q6_K", "Q4_K_S", "IQ4_XS", "Q3_K_M",
    "Q5_0", "Q4_1", "F16", "BF16", "Q5_K_S", "Q3_K_L", "Q3_K_S", "IQ3_M",
    "IQ3_XS", "IQ2_M", "IQ2_XS", "IQ2_XXS", "Q2_K", "Q2_K_S", "IQ1_M", "IQ1_S",
    "TQ1_0", "TQ2_0",
]
SESSION = requests.Session()
SESSION.headers["User-Agent"] = "AndroLLM-catalog-generator/1.0"


def norm_quant(value: str) -> str:
    return value.strip().upper().replace("-", "_")


def get_json(path: str, params=None, retries: int = 2):
    url = API + path
    for attempt in range(retries + 1):
        try:
            resp = SESSION.get(url, params=params, timeout=30)
            if resp.status_code == 200:
                return resp.json()
            if resp.status_code == 404:
                return None
            print(f"  ! {resp.status_code} for {path}", file=sys.stderr)
        except requests.RequestException as exc:
            print(f"  ! request error {path}: {exc}", file=sys.stderr)
        time.sleep(1.0 * (attempt + 1))
    return None


SHARD_PATTERN = re.compile(r"-\d{5}-of-\d{5}$")


def iso_to_epoch(value) -> int:
    if isinstance(value, int):
        return value
    if not isinstance(value, str):
        return 0
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return int(dt.timestamp() * 1000)
    except ValueError:
        return 0


def norm_stem(name: str) -> str:
    stem = name.rsplit(".", 1)[0].lower().replace("-", "_").replace(" ", "_").replace(".", "_")
    return re.sub(r"-\d{5}-of-\d{5}$", "", stem)


def matches_quant(stem: str, quant: str) -> bool:
    return bool(re.search(r"_" + norm_quant(quant).lower() + r"($|_)", stem))


def find_file(tree, wanted_quant: str, exact_file: str | None):
    if not tree:
        return None, None
    files = [e for e in tree if e.get("type") == "file"]
    if exact_file:
        for e in files:
            if e.get("path") == exact_file:
                return e, e["path"]
        return None, None
    eligible = []
    for e in files:
        name = e.get("path", "")
        if not name.lower().endswith(".gguf"):
            continue
        if SHARD_PATTERN.search(name) or any(skip in name.lower() for skip in ("imatrix", "lora", "vocab", "ggml_model")):
            continue
        eligible.append((e, name))
    for e, name in eligible:
        if matches_quant(norm_stem(name), wanted_quant):
            return e, name
    for q in QUANT_PREFERENCE:
        for e, name in eligible:
            if matches_quant(norm_stem(name), q):
                return e, name
    return None, None


def extract_license(tags):
    for tag in tags or []:
        if tag.startswith("license:"):
            return tag.split(":", 1)[1]
    return None


def slugify(repo: str) -> str:
    return re.sub(r"[^a-z0-9._-]", "", repo.lower())


def ram_hints(size_bytes: int):
    gb = size_bytes / 1_000_000_000.0
    min_ram = max(1.0, round(gb * 1.5 + 0.5, 1))
    recommended = max(min_ram + 1.0, round(min_ram * 1.6, 1))
    return min_ram, recommended


def tok_estimate(parameters: str) -> str:
    match = re.search(r"(\d+(?:\.\d+)?)\s*([BM])", parameters.upper())
    if not match:
        return "20-40 tok/s"
    value = float(match.group(1))
    unit = match.group(2)
    billions = value if unit == "B" else value / 1000.0
    if billions < 0.3:
        return "120-200 tok/s"
    if billions < 1:
        return "80-120 tok/s"
    if billions < 3:
        return "45-80 tok/s"
    if billions < 8:
        return "20-45 tok/s"
    if billions < 15:
        return "10-20 tok/s"
    return "5-15 tok/s"


def generate(curated_path: str, out_path: str, offline: bool):
    with open(curated_path, encoding="utf-8") as fh:
        curated = json.load(fh)

    models = []
    missing_files = []
    missing_repos = []

    for idx, entry in enumerate(curated):
        repo = entry["repo"]
        print(f"[{idx + 1}/{len(curated)}] {repo}")
        rev = entry.get("revision", "main")

        if offline:
            info = {
                "downloads": 0, "likes": 0, "trendingScore": 0,
                "tags": [f"license:{entry.get('license', 'Apache-2.0')}"],
                "gated": False, "createdAt": 0,
            }
        else:
            info = get_json(f"/api/models/{repo}")
            if info is None:
                missing_repos.append(repo)
                print(f"  ! repo not found, skipped", file=sys.stderr)
                continue
            tree = get_json(f"/api/models/{repo}/tree/{rev}", params={"recursive": "true"})
            if tree is None and rev == "main":
                tree = get_json(f"/api/models/{repo}/tree/master", params={"recursive": "true"})
                if tree is not None:
                    rev = "master"
        if not offline:
            tree = get_json(f"/api/models/{repo}/tree/{rev}", params={"recursive": "true"})

        wanted_quant = entry.get("quant", "Q4_K_M")
        if not offline:
            file_entry, filename = find_file(tree, wanted_quant, entry.get("file"))
            if not filename:
                missing_files.append((repo, wanted_quant))
                print(f"  ! no file for quant {wanted_quant}, skipped", file=sys.stderr)
                continue
            size = file_entry.get("size") or 0
            sha256 = ((file_entry.get("lfs") or {}).get("oid")) if file_entry.get("lfs") else None
            if sha256 and not re.fullmatch(r"[a-f0-9]{64}", sha256):
                sha256 = None
        else:
            filename = entry.get("file") or f"{repo.split('/')[-1].lower()}-{wanted_quant.lower()}.gguf"
            size = entry.get("sizeBytes", 0)
            sha256 = entry.get("sha256")

        if entry.get("file") is not None:
            quant = entry["quant"]
        else:
            stem = filename.rsplit(".", 1)[0].replace("-", "_").replace(".", "_")
            for q in QUANT_PREFERENCE:
                if stem.upper().endswith("_" + norm_quant(q)):
                    quant = q
                    break
            else:
                quant = wanted_quant
        license_val = None
        if not offline:
            license_val = extract_license(info.get("tags"))
        license_val = license_val or entry.get("license") or "Apache-2.0"

        min_ram = entry.get("minRamGb")
        recommended_ram = entry.get("recommendedRamGb")
        if min_ram is None and size > 0:
            min_ram, recommended_ram = ram_hints(size)

        model = {
            "id": f"{slugify(repo)}-{norm_quant(quant).lower()}",
            "name": entry.get("name") or f"{repo.split('/')[-1].replace('-GGUF', '').replace('_GGUF', '')} {quant}",
            "description": entry.get("description", ""),
            "family": entry["family"],
            "architecture": entry["architecture"],
            "categories": entry.get("categories", ["CHAT"]),
            "tags": entry.get("tags", []),
            "license": license_val,
            "author": repo.split("/")[0],
            "repoId": repo,
            "revision": rev,
            "fileName": filename,
            "downloadUrl": f"{API}/{repo}/resolve/{urllib.parse.quote(rev)}/{urllib.parse.quote(filename)}",
            "sizeBytes": size,
            "parameters": entry["parameters"],
            "quantization": quant,
            "contextLength": entry["contextLength"],
            "chatTemplate": entry.get("chatTemplate"),
            "minRamGb": min_ram or 4.0,
            "recommendedRamGb": recommended_ram or 8.0,
            "expectedTokSec": entry.get("expectedTokSec") or tok_estimate(entry["parameters"]),
            "downloads": info.get("downloads", 0) if info else 0,
            "likes": info.get("likes", 0) if info else 0,
            "trendingScore": info.get("trendingScore", 0) if info else 0,
            "sha256": sha256,
            "publishedAt": iso_to_epoch(info.get("createdAt")) if info else 0,
            "isGated": bool(info.get("gated")) if info else False,
            "modality": entry.get("modality", "TEXT"),
            "modelType": entry.get("modelType", "decoder-only"),
            "status": entry.get("status", "STABLE"),
            "badges": entry.get("badges", []),
            "strengths": entry.get("strengths", []),
            "weaknesses": entry.get("weaknesses", []),
            "notes": entry.get("notes"),
            "recommended": bool(entry.get("recommended", False)),
            "hidden": bool(entry.get("hidden", False)),
        }
        models.append(model)
        if not offline:
            time.sleep(0.15)

    catalog = {
        "schemaVersion": 2,
        "source": "https://huggingface.co",
        "generatedAt": int(time.time() * 1000),
        "models": models,
    }
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(catalog, fh, indent=2, ensure_ascii=False)

    print(f"\nWrote {len(models)} models to {out_path}")
    if missing_repos:
        print(f"Repos not found ({len(missing_repos)}): {', '.join(missing_repos)}")
    if missing_files:
        print(f"Files not found ({len(missing_files)}):")
        for repo, quant in missing_files:
            print(f"  {repo} (wanted {quant})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--curated", default="tools/catalog/curated_models.json")
    parser.add_argument("--out", default="core/models/src/main/assets/catalog_v1.json")
    parser.add_argument("--offline", action="store_true",
                        help="skip API calls (uses curated sizeBytes/sha256 only)")
    args = parser.parse_args()
    generate(args.curated, args.out, args.offline)
