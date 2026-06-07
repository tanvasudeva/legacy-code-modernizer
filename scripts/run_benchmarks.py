#!/usr/bin/env python3
"""
Phase 4.1 — Automated benchmark runner.

Loops over all 10 benchmark repositories, calls the LCM REST API to run
the full multi-agent pipeline for each, and persists results to the
results/{repo_name}/multi-agent/ directory.

Usage:
    python3 scripts/run_benchmarks.py [--base-url http://localhost:8080] [--repos repo1,repo2]

The LCM Spring Boot application must already be running with ANTHROPIC_API_KEY set.
"""

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path

import requests

# ---------------------------------------------------------------------------
# Repo registry — mirrors BenchmarkSpec.ALL in Java
# ---------------------------------------------------------------------------

ALL_REPOS = [
    {"name": "spring-petclinic",    "approx_loc":   5_000},
    {"name": "HikariCP",            "approx_loc":  15_000},
    {"name": "jhipster-sample-app", "approx_loc":  20_000},
    {"name": "jforum3",             "approx_loc":  40_000},
    {"name": "zxing",               "approx_loc":  60_000},
    {"name": "BroadleafCommerce",   "approx_loc":  80_000},
    {"name": "openl-tablets",       "approx_loc": 100_000},
    {"name": "Activiti",            "approx_loc": 150_000},
    {"name": "openmrs-core",        "approx_loc": 200_000},
    {"name": "dbeaver",             "approx_loc": 500_000},
]


def run_repo(base_url: str, repo_name: str, timeout_s: int = 3600) -> dict:
    """
    Calls POST /api/benchmark/{repo_name} and returns the parsed JSON result.
    Raises on HTTP error or timeout.
    """
    url = f"{base_url}/api/benchmark/{repo_name}"
    print(f"  → POST {url}", flush=True)
    t0 = time.time()
    resp = requests.post(url, timeout=timeout_s)
    elapsed = time.time() - t0

    if resp.status_code not in (200, 500):
        resp.raise_for_status()

    result = resp.json()
    result["_http_status"]   = resp.status_code
    result["_elapsed_http_s"] = round(elapsed, 1)
    return result


def save_run_summary(results_root: Path, summary: dict) -> None:
    out = results_root / "benchmark_summary.json"
    with open(out, "w") as f:
        json.dump(summary, f, indent=2, default=str)
    print(f"\n  Summary written → {out}")


def main() -> None:
    parser = argparse.ArgumentParser(description="LCM benchmark runner")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="LCM Spring Boot app base URL")
    parser.add_argument("--repos", default=None,
                        help="Comma-separated list of repo names to run (default: all 10)")
    parser.add_argument("--timeout", type=int, default=3600,
                        help="Per-repo HTTP timeout in seconds (default: 3600)")
    parser.add_argument("--results-dir", default="results",
                        help="Root directory for result output (default: results/)")
    args = parser.parse_args()

    base_url     = args.base_url.rstrip("/")
    results_root = Path(args.results_dir)
    results_root.mkdir(parents=True, exist_ok=True)

    repos = ALL_REPOS
    if args.repos:
        names = {r.strip() for r in args.repos.split(",")}
        repos = [r for r in ALL_REPOS if r["name"] in names]
        if not repos:
            print(f"No matching repos found for: {names}", file=sys.stderr)
            sys.exit(1)

    # Verify server is reachable
    try:
        resp = requests.get(f"{base_url}/api/benchmark", timeout=10)
        resp.raise_for_status()
        print(f"✓ LCM server reachable at {base_url}")
    except Exception as e:
        print(f"✗ Cannot reach LCM server at {base_url}: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"\nRunning benchmark on {len(repos)} repo(s). Results → {results_root.absolute()}\n")

    run_results = []
    total_start = time.time()

    for i, repo in enumerate(repos, 1):
        name      = repo["name"]
        approx_loc = repo["approx_loc"]
        print(f"[{i}/{len(repos)}] {name} (~{approx_loc:,} LOC) …", flush=True)
        t0 = time.time()

        try:
            result  = run_repo(base_url, name, timeout_s=args.timeout)
            success = result.get("_http_status") == 200 and not result.get("errorMessage")
            elapsed = round(time.time() - t0, 1)

            status_icon = "✓" if success else "✗"
            print(f"  {status_icon} done in {elapsed}s — "
                  f"services={result.get('serviceCount', '?')}, "
                  f"files={result.get('totalFilesGenerated', '?')}, "
                  f"tokens={result.get('totalTokensUsed', '?')}")

            run_results.append({
                "repo":     name,
                "success":  success,
                "elapsed_s": elapsed,
                "result":   result,
            })

        except Exception as e:
            elapsed = round(time.time() - t0, 1)
            print(f"  ✗ FAILED after {elapsed}s: {e}", file=sys.stderr)
            run_results.append({
                "repo":     name,
                "success":  False,
                "elapsed_s": elapsed,
                "error":    str(e),
            })

    total_elapsed = round(time.time() - total_start, 1)
    successes     = sum(1 for r in run_results if r["success"])

    summary = {
        "run_at":       datetime.now().isoformat(),
        "base_url":     base_url,
        "total_repos":  len(run_results),
        "succeeded":    successes,
        "failed":       len(run_results) - successes,
        "total_elapsed_s": total_elapsed,
        "results":      run_results,
    }

    save_run_summary(results_root, summary)

    print(f"\n{'='*60}")
    print(f"Benchmark complete: {successes}/{len(run_results)} succeeded in {total_elapsed}s")
    print(f"{'='*60}")

    if successes < len(run_results):
        sys.exit(1)


if __name__ == "__main__":
    main()
