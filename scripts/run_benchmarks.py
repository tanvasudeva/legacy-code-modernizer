#!/usr/bin/env python3
"""
Phase 4.1/4.5 — Automated benchmark runner.

Loops over all 20 benchmark repositories, calls the LCM REST API to run
the full multi-agent pipeline for each, and persists results to the
results/{repo_name}/multi-agent/ directory.

Usage:
    # Run full multi-agent pipeline on all repos
    python3 scripts/run_benchmarks.py

    # Run full pipeline on specific repos only
    python3 scripts/run_benchmarks.py --repos spring-petclinic,HikariCP

    # Re-run only the baseline systems (GPT-4o + Claude single-prompt)
    # Requires baselines already stored in results/{repo}/{system}/response_raw.txt
    python3 scripts/run_benchmarks.py --baselines-only

    # Re-run baselines for specific repos
    python3 scripts/run_benchmarks.py --baselines-only --repos spring-petclinic

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
    # Original 10
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
    # Extended 10
    {"name": "retrofit",            "approx_loc":  15_000},
    {"name": "gson",                "approx_loc":  30_000},
    {"name": "caffeine",            "approx_loc":  20_000},
    {"name": "resilience4j",        "approx_loc":  30_000},
    {"name": "mybatis-3",           "approx_loc":  80_000},
    {"name": "RxJava",              "approx_loc":  50_000},
    {"name": "okhttp",              "approx_loc":  50_000},
    {"name": "flyway",              "approx_loc": 100_000},
    {"name": "micrometer",          "approx_loc":  60_000},
    {"name": "guava",               "approx_loc": 200_000},
]


def run_baseline(base_url: str, repo_name: str, system: str,
                 timeout_s: int = 600) -> dict:
    """
    Calls POST /api/baseline/{repo_name}/{system} and returns the parsed result.
    system is 'gpt4o' or 'claude'.
    """
    url = f"{base_url}/api/baseline/{repo_name}/{system}"
    print(f"  → POST {url}", flush=True)
    t0 = time.time()
    resp = requests.post(url, timeout=timeout_s)
    elapsed = time.time() - t0
    if resp.status_code not in (200, 500):
        resp.raise_for_status()
    result = resp.json() if resp.content else {}
    result["_http_status"]    = resp.status_code
    result["_elapsed_http_s"] = round(elapsed, 1)
    return result


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
                        help="Comma-separated repo names (default: all 10)")
    parser.add_argument("--timeout", type=int, default=3600,
                        help="Per-repo HTTP timeout in seconds (default: 3600)")
    parser.add_argument("--results-dir", default="results",
                        help="Root directory for result output (default: results/)")
    parser.add_argument("--baselines-only", action="store_true",
                        help="Re-run only the single-prompt baselines (GPT-4o + Claude) "
                             "using the new BaselineCodeExtractor. Skips the multi-agent "
                             "pipeline entirely. Useful after updating metric computation.")
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
        probe_path = "/api/baseline" if args.baselines_only else "/api/benchmark"
        resp = requests.get(f"{base_url}{probe_path}", timeout=10)
        # 405 METHOD_NOT_ALLOWED is fine — endpoint exists
        if resp.status_code not in (200, 405):
            resp.raise_for_status()
        print(f"✓ LCM server reachable at {base_url}")
    except Exception as e:
        print(f"✗ Cannot reach LCM server at {base_url}: {e}", file=sys.stderr)
        sys.exit(1)

    if args.baselines_only:
        _run_baselines(base_url, repos, results_root, args.timeout)
    else:
        _run_multi_agent(base_url, repos, results_root, args.timeout)


def _run_multi_agent(base_url, repos, results_root, timeout):
    """Run the full multi-agent pipeline for each repo."""
    print(f"\nRunning multi-agent pipeline on {len(repos)} repo(s).\n")
    run_results = []
    total_start = time.time()

    for i, repo in enumerate(repos, 1):
        name       = repo["name"]
        approx_loc = repo["approx_loc"]
        print(f"[{i}/{len(repos)}] {name} (~{approx_loc:,} LOC) …", flush=True)
        t0 = time.time()
        try:
            result  = run_repo(base_url, name, timeout_s=timeout)
            success = result.get("_http_status") == 200 and not result.get("errorMessage")
            elapsed = round(time.time() - t0, 1)
            icon    = "✓" if success else "✗"
            print(f"  {icon} done in {elapsed}s — "
                  f"services={result.get('serviceCount','?')}, "
                  f"files={result.get('totalFilesGenerated','?')}, "
                  f"tokens={result.get('totalTokensUsed','?')}")
            run_results.append({"repo": name, "success": success,
                                 "elapsed_s": elapsed, "result": result})
        except Exception as e:
            elapsed = round(time.time() - t0, 1)
            print(f"  ✗ FAILED after {elapsed}s: {e}", file=sys.stderr)
            run_results.append({"repo": name, "success": False,
                                 "elapsed_s": elapsed, "error": str(e)})

    total_elapsed = round(time.time() - total_start, 1)
    successes     = sum(1 for r in run_results if r["success"])
    save_run_summary(results_root, {
        "run_at": datetime.now().isoformat(), "base_url": base_url,
        "mode": "multi-agent", "total_repos": len(run_results),
        "succeeded": successes, "failed": len(run_results) - successes,
        "total_elapsed_s": total_elapsed, "results": run_results,
    })
    print(f"\n{'='*60}")
    print(f"Multi-agent benchmark: {successes}/{len(run_results)} in {total_elapsed}s")
    print(f"{'='*60}")
    if successes < len(run_results):
        sys.exit(1)


def _run_baselines(base_url, repos, results_root, timeout):
    """Re-run both single-prompt baselines for each repo using the new extractor."""
    print(f"\nRe-running baselines (GPT-4o + Claude) on {len(repos)} repo(s).")
    print("This calls POST /api/baseline/{repo}/gpt4o and /claude,")
    print("which invokes BaselineCodeExtractor for real compilation rates.\n")

    run_results = []
    total_start = time.time()

    for i, repo in enumerate(repos, 1):
        name = repo["name"]
        print(f"[{i}/{len(repos)}] {name} …", flush=True)
        for system in ("gpt4o", "claude"):
            t0 = time.time()
            try:
                result  = run_baseline(base_url, name, system, timeout_s=timeout)
                success = result.get("_http_status") == 200
                elapsed = round(time.time() - t0, 1)
                icon    = "✓" if success else "✗"
                print(f"  {icon} {system}: done in {elapsed}s")
                run_results.append({"repo": name, "system": system,
                                     "success": success, "elapsed_s": elapsed,
                                     "result": result})
            except Exception as e:
                elapsed = round(time.time() - t0, 1)
                print(f"  ✗ {system}: FAILED after {elapsed}s: {e}", file=sys.stderr)
                run_results.append({"repo": name, "system": system,
                                     "success": False, "elapsed_s": elapsed,
                                     "error": str(e)})

    total_elapsed = round(time.time() - total_start, 1)
    successes     = sum(1 for r in run_results if r["success"])
    summary_path  = results_root / "baseline_rerun_summary.json"
    with open(summary_path, "w") as f:
        json.dump({
            "run_at": datetime.now().isoformat(), "base_url": base_url,
            "mode": "baselines-only", "total_runs": len(run_results),
            "succeeded": successes, "total_elapsed_s": total_elapsed,
            "results": run_results,
        }, f, indent=2, default=str)

    print(f"\n{'='*60}")
    print(f"Baseline re-run complete: {successes}/{len(run_results)} in {total_elapsed}s")
    print(f"Summary → {summary_path}")
    print(f"{'='*60}")
    print("\nNext: run python3 scripts/analyze_results.py --db-url <url> to update report.")


if __name__ == "__main__":
    main()
