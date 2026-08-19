"""Update catalog_v1.json: fill real sha256 checksums, swap broken .tflite chat
entries for .litertlm containers, drop the model with no native container.

Usage: python tools/catalog/update_catalog.py
"""
import json
import re
import sys
import time
import urllib.parse

import requests

API = "https://huggingface.co"
CATALOG = "core/models/src/main/assets/catalog_v1.json"
SESSION = requests.Session()
SESSION.headers["User-Agent"] = "AndroLLM-catalog-updater/1.0"


def head_sha256(url: str):
    """Return (sha256, status) from the HF resolve endpoint.

    The redirect response carries X-Linked-ETag (LFS sha256). Gated repos
    answer 401/403 without a token.
    """
    try:
        resp = SESSION.head(url, allow_redirects=False, timeout=30)
        etag = (resp.headers.get("X-Linked-ETag") or "").strip('"')
        if not etag:
            resp2 = SESSION.head(url, allow_redirects=True, timeout=30)
            etag = (resp2.headers.get("ETag") or resp2.headers.get("X-Linked-ETag") or "").strip('"')
        sha = etag if re.fullmatch(r"[a-f0-9]{64}", etag) else None
        return sha, resp.status_code
    except requests.RequestException as exc:
        print(f"  ! request error {url}: {exc}", file=sys.stderr)
        return None, 0


def head_size(url: str) -> int | None:
    """Return the real Content-Length from the resolved (final) response.

    The HF resolve endpoint redirects to a signed CDN; following redirects
    yields the exact byte count the app's downloader will receive. Returns
    None when the request fails or the server sends no Content-Length.
    """
    try:
        resp = SESSION.head(url, allow_redirects=True, timeout=45)
        if resp.status_code == 200:
            try:
                return int(resp.headers.get("Content-Length", ""))
            except ValueError:
                return None
        return None
    except requests.RequestException as exc:
        print(f"  ! request error {url}: {exc}", file=sys.stderr)
        return None


def repo_stats(repo: str):
    info = SESSION.get(API + f"/api/models/{repo}", timeout=30).json()
    return info.get("downloads", 0), info.get("likes", 0)


def main():
    with open(CATALOG, encoding="utf-8") as fh:
        catalog = json.load(fh)

    models = catalog["models"]
    by_id = {m["id"]: m for m in models}

    # ---- 1. Replace the three chat models whose repos ship only .tflite ----
    # LiteRT-LM Engine loads .litertlm only; raw .tflite fails native init
    # with "INVALID_ARGUMENT: Unsupported file format".
    qwen2 = by_id.pop("litertlm-qwen2.5-0.5b-instruct", None) or by_id.pop("litertlm-qwen2-0.5b-instruct", None)
    if qwen2 is None:
        print("! qwen2 entry missing, aborting", file=sys.stderr)
        return 1
    qwen2.update({
        "id": "litertlm-qwen2-0.5b-instruct",
        "name": "Qwen2 0.5B Instruct LiteRT",
        "description": ("Qwen2 0.5B instruct as an official LiteRT-LM container - "
                        "the lightest entry point into the Qwen family."),
        "repoId": "litert-community/Qwen2-0.5B-Instruct",
        "fileName": "Qwen2_0.5B_Instruct.litertlm",
        "downloadUrl": (API + "/litert-community/Qwen2-0.5B-Instruct/resolve/main/"
                        "Qwen2_0.5B_Instruct.litertlm"),
        "sizeBytes": 647377840,
        "runtimeFormat": "LITERTLM",
        "modelSource": "litert-community/Qwen2-0.5B-Instruct",
    })
    qwen2["downloads"], qwen2["likes"] = repo_stats("litert-community/Qwen2-0.5B-Instruct")
    by_id[qwen2["id"]] = qwen2

    llama32 = by_id.pop("litertlm-tinyllama-1.1b-chat", None) or by_id.pop("litertlm-llama3.2-1b-instruct", None) or by_id.pop("litertlm-lfm2.5-1.2b-instruct", None)
    if llama32 is None:
        print("! llama entry missing, aborting", file=sys.stderr)
        return 1
    llama32.update({
        "id": "litertlm-lfm2.5-1.2b-instruct",
        "name": "LFM2.5 1.2B Instruct LiteRT",
        "description": ("LFM2.5 1.2B instruct as an official LiteRT-LM container - "
                        "a compact, battle-tested general chat model with an Llama "
                        "3-compatible tokenizer and template."),
        "family": "Llama",
        "architecture": "lfm2",
        "repoId": "litert-community/LFM2.5-1.2B-Instruct",
        "fileName": "LFM2.5-1.2B-Instruct_int8.litertlm",
        "downloadUrl": (API + "/litert-community/LFM2.5-1.2B-Instruct/resolve/main/"
                        "LFM2.5-1.2B-Instruct_int8.litertlm"),
        "sizeBytes": 1247091440,
        "parameters": "1.2B",
        "quantization": "INT8",
        "contextLength": 4096,
        "minRamGb": 2.0,
        "recommendedRamGb": 4.0,
        "expectedTokSec": "20-40 tok/s",
        "stopSequences": ["<|eot_id|>", "<|start_header_id|>"],
        "runtimeFormat": "LITERTLM",
        "modelSource": "litert-community/LFM2.5-1.2B-Instruct",
    })
    llama32["downloads"], llama32["likes"] = repo_stats("litert-community/LFM2.5-1.2B-Instruct")
    by_id[llama32["id"]] = llama32

    smollm = by_id.pop("litertlm-smollm-135m", None)
    if smollm is not None:
        print(f"Removed {smollm['id']} (repo ships only .tflite; no .litertlm variant)")

    # ---- 1b. Remove gated models ----
    # Gated repos cannot be downloaded or size/sha-verified without a token
    # (401), so they are removed from the catalog entirely.
    gated = [mid for mid, m in by_id.items() if m.get("isGated")]
    for mid in gated:
        print(f"Removed {mid} (gated; not downloadable)")
        del by_id[mid]

    models = list(by_id.values())
    catalog["models"] = models

    # ---- 2. Fill sha256 for every model (skipped for gated repos) ----
    filled = 0
    skipped = []
    for model in models:
        if model.get("sha256"):
            continue
        if model.get("isGated"):
            skipped.append((model["id"], "gated"))
            continue
        url = model["downloadUrl"]
        sha, status = head_sha256(url)
        if sha:
            model["sha256"] = sha
            filled += 1
            print(f"sha256  {model['id']}")
        else:
            skipped.append((model["id"], f"status={status}"))
        time.sleep(0.1)

    # ---- 3. Correct sizeBytes from the real Content-Length ----
    # The app's downloader verifies the finished file against this size and
    # deletes it on mismatch — a stale/rounded size made every download fail
    # with "File size mismatch". Gated repos cannot be probed (401), so their
    # sizes are left as-is.
    fixed = 0
    for model in models:
        if model.get("isGated"):
            continue
        url = model["downloadUrl"]
        size = head_size(url)
        if size and size != model.get("sizeBytes"):
            print(f"size    {model['id']}: {model.get('sizeBytes')} -> {size}")
            model["sizeBytes"] = size
            fixed += 1
        time.sleep(0.1)

    # ---- 4. Fill the metadata-registry fields ----
    # version / fileFormat / mimeType / containerType are required by
    # CatalogValidator (registry-driven). containerType is the expected
    # LlmModelType identifier — the identifier embedded in the actual
    # container remains authoritative at load time.
    CONTAINER_TYPE_BY_ID = {
        "litertlm-gemma-4-e2b": "gemma4",
        "litertlm-gemma-4-e4b": "gemma4",
        "litertlm-gemma-4-12b": "gemma4",
        "litertlm-qwen3-0.6b-int4": "qwen3",
        "litertlm-qwen3-1.7b": "qwen3",
        "litertlm-qwen3-4b": "qwen3",
        "litertlm-qwen3-8b": "qwen3",
        "litertlm-qwen3-14b": "qwen3",
        "litertlm-deepseek-r1-distill-qwen-1.5b-q8": "qwen2p5",
        "litertlm-deepseek-r1-distill-qwen-7b": "qwen2p5",
        "litertlm-gemma3-270m-it": "gemma3",
        "litertlm-gemma3-1b-it-q4": "gemma3",
        "litertlm-qwen2.5-1.5b-q8": "qwen2p5",
        "litertlm-qwen2.5-coder-3b": "qwen2p5",
        "litertlm-qwen2.5-coder-1.5b": "qwen2p5",
        "litertlm-qwen2-0.5b-instruct": "generic_model",
        "litertlm-phi-4-mini-instruct": "generic_model",
        "litertlm-smollm2-135m": "generic_model",
        "litertlm-smollm2-360m": "generic_model",
        "litertlm-smollm3-3b": "generic_model",
        "litertlm-medgemma-1.5-4b-it": "generic_model",
        "litertlm-codegemma-7b-it": "generic_model",
        "litertlm-functiongemma-mobile-actions": "function_gemma",
        "litertlm-functiongemma-tiny-garden": "function_gemma",
        "litertlm-smolvlm2-500m": "generic_model",
        "litertlm-fastvlm-0.5b": "fast_vlm",
        "litertlm-mage-vl": "generic_model",
        "litertlm-minicpm5-1b": "minicpm5",
        "litertlm-tinyswallow-1.5b": "qwen2p5",
        "litertlm-vibethinker-1.5b": "qwen2p5",
        "litertlm-lfm2.5-1.2b-instruct": "lfm2",
    }
    filled_fields = 0
    for model in models:
        if model.get("runtimeFormat") == "LITERTLM":
            model.setdefault("containerType", CONTAINER_TYPE_BY_ID.get(model["id"], "generic_model"))
        else:
            model.setdefault("containerType", None)
        if model.get("fileFormat") is None or model.get("fileFormat") == "":
            model["fileFormat"] = model["runtimeFormat"]
            filled_fields += 1
        if model.get("mimeType") is None or model.get("mimeType") == "":
            model["mimeType"] = "application/x-litertlm" if model["runtimeFormat"] == "LITERTLM" else "application/x-tflite"
            filled_fields += 1
        if model.get("version") is None or model.get("version") == "":
            model["version"] = "1.0.0"
            filled_fields += 1

    with open(CATALOG, "w", encoding="utf-8") as fh:
        json.dump(catalog, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"\nWrote {len(models)} models to {CATALOG}")
    print(f"sha256 filled: {filled}; skipped: {len(skipped)}; sizes corrected: {fixed}; metadata fields filled: {filled_fields}")
    for mid, reason in skipped:
        print(f"  {mid}: {reason}")


if __name__ == "__main__":
    main()