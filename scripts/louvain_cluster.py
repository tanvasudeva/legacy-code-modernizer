#!/usr/bin/env python3
"""
Phase 1.3 — Louvain community detection for legacy Java codebase modernisation.

Usage
-----
  python louvain_cluster.py --input adjacency.json --output cluster_map.json
  python louvain_cluster.py --neo4j bolt://localhost:7687 --output cluster_map.json
  python louvain_cluster.py --input adjacency.json --no-llm
"""

import argparse
import json
import sys
from collections import defaultdict

import community as community_louvain
import networkx as nx
import numpy as np
import requests


# ---------------------------------------------------------------------------
# Graph loading
# ---------------------------------------------------------------------------

def load_edges_from_file(path: str) -> list[dict]:
    with open(path) as f:
        return json.load(f)


def load_edges_from_neo4j(uri: str, user: str, password: str) -> list[dict]:
    try:
        from neo4j import GraphDatabase
    except ImportError:
        print("ERROR: 'neo4j' package not installed — run: pip install neo4j", file=sys.stderr)
        sys.exit(1)
    driver = GraphDatabase.driver(uri, auth=(user, password))
    edges = []
    with driver.session() as s:
        for rec in s.run(
            "MATCH (src:Class)-[r]->(tgt:Class) "
            "RETURN src.fqn AS source, type(r) AS type, tgt.fqn AS target"
        ):
            edges.append({"source": rec["source"], "target": rec["target"], "type": rec["type"]})
    driver.close()
    return edges


# ---------------------------------------------------------------------------
# Graph construction
# ---------------------------------------------------------------------------

_REL_WEIGHT = {"EXTENDS": 4, "IMPLEMENTS": 3, "IMPORTS": 2, "CALLS": 1}


def build_graph(edges: list[dict]) -> nx.Graph:
    G = nx.Graph()
    for e in edges:
        src, tgt = e.get("source"), e.get("target")
        if not src or not tgt or src == tgt:
            continue
        weight = _REL_WEIGHT.get(e.get("type", ""), 1)
        if G.has_edge(src, tgt):
            G[src][tgt]["weight"] += weight
        else:
            G.add_edge(src, tgt, weight=weight)
    return G


# ---------------------------------------------------------------------------
# Louvain clustering
# ---------------------------------------------------------------------------

def run_louvain(G: nx.Graph, resolution: float = 1.0) -> dict[str, int]:
    np.random.seed(42)
    return community_louvain.best_partition(G, weight="weight", resolution=resolution)


def auto_tune_resolution(
    G: nx.Graph, target_min: int = 4, target_max: int = 7, iterations: int = 12
) -> tuple[dict[str, int], float]:
    lo, hi = 0.1, 5.0
    best_partition = run_louvain(G, resolution=1.0)
    best_res = 1.0
    for _ in range(iterations):
        mid = (lo + hi) / 2.0
        partition = run_louvain(G, resolution=mid)
        n = len(set(partition.values()))
        if target_min <= n <= target_max:
            return partition, mid
        lo, hi = (mid, hi) if n < target_min else (lo, mid)
        best_partition, best_res = partition, mid
    return best_partition, best_res


# ---------------------------------------------------------------------------
# Cluster labelling
# ---------------------------------------------------------------------------

_HEURISTIC_KEYWORDS: dict[str, str] = {
    "vet":       "vet-service",
    "specialty": "vet-service",
    "owner":     "owner-service",
    "pet":       "pet-service",
    "visit":     "visit-service",
    "system":    "system-service",
    "cache":     "system-service",
    "web":       "system-service",
    "welcome":   "system-service",
    "crash":     "system-service",
    "model":     "model-service",
    "base":      "model-service",
    "person":    "model-service",
    "named":     "model-service",
}


def label_cluster_heuristic(cluster_id: int, class_fqns: list[str]) -> str:
    names_lower = " ".join(fqn.split(".")[-1].lower() for fqn in class_fqns)
    for kw, label in _HEURISTIC_KEYWORDS.items():
        if kw in names_lower:
            return label
    return f"service-{cluster_id}"


def label_cluster_llm(cluster_id: int, class_fqns: list[str],
                      ollama_url: str = "http://localhost:11434") -> str:
    simple_names = [fqn.split(".")[-1] for fqn in class_fqns][:15]
    prompt = (
        "You are a software architect decomposing a monolithic Spring application into microservices.\n"
        f"These Java classes form a cohesive cluster: {', '.join(simple_names)}\n"
        "Respond with ONLY a single lowercase kebab-case microservice name "
        "(e.g. 'owner-service', 'vet-service'). No explanation."
    )
    try:
        resp = requests.post(f"{ollama_url}/api/generate",
                             json={"model": "codellama:13b", "prompt": prompt, "stream": False},
                             timeout=60)
        resp.raise_for_status()
        raw = resp.json().get("response", "").strip().lower()
        token = raw.split()[0].strip(".,;:'\"") if raw.split() else ""
        return token if token and token.replace("-", "").isalpha() else label_cluster_heuristic(cluster_id, class_fqns)
    except Exception as exc:
        print(f"  [LLM] cluster {cluster_id} — {exc}; falling back to heuristic", file=sys.stderr)
        return label_cluster_heuristic(cluster_id, class_fqns)


def build_cluster_map(partition: dict[str, int], use_llm: bool = True,
                      ollama_url: str = "http://localhost:11434") -> tuple[dict[str, str], dict[int, list[str]]]:
    clusters: dict[int, list[str]] = defaultdict(list)
    for fqn, cid in partition.items():
        clusters[cid].append(fqn)

    id_to_name: dict[int, str] = {}
    used: set[str] = set()

    for cid, members in sorted(clusters.items(), key=lambda kv: -len(kv[1])):
        name = label_cluster_llm(cid, members, ollama_url) if use_llm else label_cluster_heuristic(cid, members)
        base, suffix = name, 2
        while name in used:
            name = f"{base}-{suffix}"
            suffix += 1
        used.add(name)
        id_to_name[cid] = name

    return {fqn: id_to_name[cid] for fqn, cid in partition.items()}, dict(clusters)


# ---------------------------------------------------------------------------
# Validation & reporting
# ---------------------------------------------------------------------------

def print_report(cluster_map: dict[str, str]):
    print("\n── Cluster summary ─────────────────────────────────────")
    by_service: dict[str, list[str]] = defaultdict(list)
    for fqn, svc in cluster_map.items():
        by_service[svc].append(fqn.split(".")[-1])
    for svc, members in sorted(by_service.items(), key=lambda kv: -len(kv[1])):
        print(f"  {svc:30s} ({len(members):2d} classes): {', '.join(sorted(members)[:6])}"
              + (" …" if len(members) > 6 else ""))


def validate(partition: dict[str, int], target_min: int = 4, target_max: int = 7) -> bool:
    n = len(set(partition.values()))
    ok = target_min <= n <= target_max
    print(f"\n{'✓' if ok else '✗'} Cluster count: {n} (expected {target_min}–{target_max})")
    return ok


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Louvain community detection")
    parser.add_argument("--input",      default="adjacency.json")
    parser.add_argument("--neo4j",      default=None)
    parser.add_argument("--user",       default="neo4j")
    parser.add_argument("--password",   default="neo4j_password")
    parser.add_argument("--output",     default="cluster_map.json")
    parser.add_argument("--resolution", type=float, default=None)
    parser.add_argument("--no-llm",     action="store_true")
    parser.add_argument("--ollama",     default="http://localhost:11434")
    args = parser.parse_args()

    if args.neo4j:
        print(f"Loading graph from Neo4j {args.neo4j} …")
        edges = load_edges_from_neo4j(args.neo4j, args.user, args.password)
    else:
        print(f"Loading graph from {args.input} …")
        edges = load_edges_from_file(args.input)
    print(f"  {len(edges)} edges loaded")

    G = build_graph(edges)
    print(f"  Graph: {G.number_of_nodes()} nodes, {G.number_of_edges()} edges")
    if G.number_of_nodes() == 0:
        print("ERROR: empty graph", file=sys.stderr)
        return 1

    print("\nRunning Louvain community detection …")
    if args.resolution is not None:
        partition = run_louvain(G, resolution=args.resolution)
        print(f"  resolution={args.resolution:.2f} → {len(set(partition.values()))} clusters")
    else:
        partition, res = auto_tune_resolution(G)
        print(f"  auto-tuned resolution={res:.3f} → {len(set(partition.values()))} clusters")

    print(f"\nLabelling clusters ({'codellama via Ollama' if not args.no_llm else 'heuristics'}) …")
    cluster_map, _ = build_cluster_map(partition, use_llm=not args.no_llm, ollama_url=args.ollama)

    print_report(cluster_map)
    valid = validate(partition)

    with open(args.output, "w") as f:
        json.dump(cluster_map, f, indent=2, sort_keys=True)
    print(f"\nCluster map written → {args.output}  ({len(cluster_map)} classes)")

    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
