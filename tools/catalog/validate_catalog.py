"""Automated catalog audit (req 14): validate every model entry offline and emit
a PASS/FAIL report. Optionally probe the network (HEAD) with --probe to verify
URLs, Content-Length vs sizeBytes, and LFS sha256 recovery.

Usage:
    python tools/catalog/validate_catalog.py [--probe] [--catalog PATH]
"""
import json
import re
import sys

CATALOG = "core/models/src/main/assets/catalog_v1.json"

KNOWN_ARCHS = {
    "llama", "llama4", "falcon", "gpt2", "gptj", "gptneox", "mpt", "baichuan",
    "starcoder", "bert", "bloom", "stablelm", "qwen", "qwen2", "qwen2moe",
    "qwen2vl", "qwen3", "qwen3moe", "qwen3next", "qwen3vl", "qwen35", "qwen35moe",
    "phi2", "phi3", "phimoe", "gemma", "gemma2", "gemma3", "gemma3n", "gemma4",
    "gemma4-assistant", "gemma-embedding", "deepseek", "deepseek2", "deepseek4",
    "bitnet", "t5", "lfm2", "smollm3", "mistral3", "mistral4",
    "phi4", "gemma-1.5", "functiongemma", "smolvlm2", "fastvlm", "mage-vl",
    "whisper", "moonshine", "parakeet", "qwen3-asr", "qwen3-omni", "codegemma",
    "qwen2.5-coder", "qwen3moe", "minicpm", "minicpm3",
}
KNOWN_CONTAINERS = {
    "generic_model", "qwen3", "qwen2p5", "gemma3", "gemma3n", "gemma4",
    "function_gemma", "fast_vlm", "lfm2", "minicpm5",
}
KNOWN_QUANTS = {
    "Q1_0", "Q2_0", "Q2_K", "Q2_K_S", "Q3_K", "Q3_K_S", "Q3_K_M", "Q3_K_L",
    "Q4_0", "Q4_1", "Q4_K", "Q4_K_S", "Q4", "Q5_0", "Q5_1", "Q5_K", "Q5_K_S",
    "Q5_K_M", "Q6_K", "Q8_0", "Q8", "IQ1_S", "IQ1_M", "IQ2_XXS", "IQ2_XS",
    "IQ2_S", "IQ2_M", "IQ3_XXS", "IQ3_XS", "IQ3_S", "IQ3_M", "IQ4_XS", "IQ4_NL",
    "TQ1_0", "TQ2_0", "MXFP4", "NVFP4", "F16", "FP16", "BF16", "INT4", "INT8",
    "MIXED", "FP32",
}
SHA_RE = re.compile(r"^[0-9a-fA-F]{64}$")


def audit_offline(models):
    results = []
    seen = set()
    for m in models:
        mid = m.get("id", "<no-id>")
        errors, warnings = [], []
        if mid in seen:
            errors.append("duplicate id")
        seen.add(mid)
        for f in ("name", "family", "architecture", "repoId", "fileName",
                  "downloadUrl", "quantization", "version", "fileFormat", "mimeType"):
            if not m.get(f):
                errors.append(f"missing {f}")
        url = m.get("downloadUrl", "")
        if url and not url.startswith("https://"):
            errors.append("downloadUrl must be https")
        fn = m.get("fileName", "")
        ff = (m.get("fileFormat") or "").upper()
        if fn and not (fn.lower().endswith(".litertlm") or fn.lower().endswith(".tflite")):
            errors.append(f"unsupported extension: {fn}")
        if ff and ff not in ("LITERTLM", "TFLITE"):
            errors.append(f"bad fileFormat {ff}")
        if ff == "LITERTLM" and not m.get("containerType"):
            errors.append("missing containerType")
        elif ff == "TFLITE" and m.get("containerType"):
            errors.append("containerType must be empty for .tflite")
        if m.get("containerType") and m["containerType"] not in KNOWN_CONTAINERS:
            errors.append(f"unknown containerType {m['containerType']}")
        if m.get("architecture") and m["architecture"] not in KNOWN_ARCHS:
            errors.append(f"unknown architecture {m['architecture']}")
        if m.get("quantization") and m["quantization"] not in KNOWN_QUANTS:
            warnings.append(f"unclassified quantization {m['quantization']}")
        sha = m.get("sha256")
        if not sha:
            warnings.append("missing sha256 (hash check skipped at runtime)")
        elif not SHA_RE.match(sha):
            errors.append("malformed sha256")
        if (m.get("sizeBytes") or 0) <= 0:
            warnings.append("missing sizeBytes (server size used at runtime)")
        backends = m.get("supportedBackends", [])
        if not backends:
            errors.append("no supportedBackends")
        elif not any(b.upper() in ("CPU", "VULKAN", "GPU") for b in backends):
            errors.append(f"no runnable backend in {backends}")
        if "CHAT" in (m.get("categories") or []) and fn.lower().endswith(".tflite"):
            errors.append("chat model as .tflite (engine requires .litertlm)")
        status = "PASS" if not errors else "FAIL"
        if warnings and not errors:
            status = "PASS(warnings)"
        results.append((mid, status, errors, warnings))
    return results


def probe(models):
    import requests
    s = requests.Session()
    s.headers["User-Agent"] = "AndroLLM-catalog-audit/1.0"
    out = {}
    for m in models:
        mid = m["id"]
        try:
            r = s.head(m["downloadUrl"], allow_redirects=True, timeout=30)
            size = r.headers.get("Content-Length")
            out[mid] = {
                "status": r.status_code,
                "content_length": int(size) if size and size.isdigit() else None,
                "accept_ranges": r.headers.get("Accept-Ranges"),
                "etag": (r.headers.get("X-Linked-ETag") or r.headers.get("ETag") or "").strip('"') or None,
                "mime": (r.headers.get("Content-Type") or "").split(";")[0].strip() or None,
            }
        except Exception as exc:  # noqa: BLE001 - audit must not crash
            out[mid] = {"error": str(exc)}
    return out


def main():
    catalog = CATALOG
    do_probe = "--probe" in sys.argv
    for arg in sys.argv[1:]:
        if not arg.startswith("--"):
            catalog = arg
    with open(catalog, encoding="utf-8") as fh:
        models = json.load(fh)["models"]

    results = audit_offline(models)
    probed = probe(models) if do_probe else {}

    fails = warns = 0
    for mid, status, errors, warnings in results:
        mark = "PASS" if status == "PASS" else ("WARN" if status.startswith("PASS") else "FAIL")
        if mark == "FAIL":
            fails += 1
        if warnings:
            warns += 1
        line = f"[{mark}] {mid}"
        if errors:
            line += " :: " + "; ".join(errors)
        if warnings:
            line += " (warn: " + "; ".join(warnings) + ")"
        if do_probe:
            p = probed.get(mid, {})
            if "error" in p:
                line += f" [probe: {p['error']}]"
            else:
                size_ok = (p.get("content_length") == next(
                    (m.get("sizeBytes") for m in models if m["id"] == mid), None))
                line += (f" [probe: HTTP {p.get('status')} "
                         f"len={p.get('content_length')} ranges={p.get('accept_ranges')} "
                         f"etag={'yes' if p.get('etag') else 'no'} "
                         f"size_match={size_ok}]")
        print(line)

    print(f"\n{len(models)} models: {len(models) - fails} PASS, {fails} FAIL, {warns} with warnings")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
