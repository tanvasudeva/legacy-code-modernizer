"""
Unit + integration tests for louvain_cluster.py.
Run: python -m pytest scripts/test_louvain.py -v
"""

import json
import os
import tempfile

import pytest
import networkx as nx

from louvain_cluster import (
    build_graph, run_louvain, auto_tune_resolution,
    label_cluster_heuristic, build_cluster_map, validate, load_edges_from_file,
)

PETCLINIC_EDGES = [
    {"source": "p.Vet",            "target": "p.Specialty",       "type": "IMPORTS"},
    {"source": "p.VetController",  "target": "p.Vet",             "type": "IMPORTS"},
    {"source": "p.VetController",  "target": "p.VetRepository",   "type": "IMPORTS"},
    {"source": "p.VetController",  "target": "p.Vet",             "type": "CALLS"},
    {"source": "p.Vets",           "target": "p.Vet",             "type": "IMPORTS"},
    {"source": "p.Vet",            "target": "p.NamedEntity",     "type": "EXTENDS"},
    {"source": "p.Owner",          "target": "p.Person",          "type": "EXTENDS"},
    {"source": "p.Owner",          "target": "p.Pet",             "type": "IMPORTS"},
    {"source": "p.OwnerController","target": "p.Owner",           "type": "IMPORTS"},
    {"source": "p.OwnerController","target": "p.OwnerRepository", "type": "IMPORTS"},
    {"source": "p.OwnerController","target": "p.Owner",           "type": "CALLS"},
    {"source": "p.PetController",  "target": "p.Pet",             "type": "IMPORTS"},
    {"source": "p.PetController",  "target": "p.Owner",           "type": "IMPORTS"},
    {"source": "p.PetValidator",   "target": "p.Pet",             "type": "IMPORTS"},
    {"source": "p.Pet",            "target": "p.NamedEntity",     "type": "EXTENDS"},
    {"source": "p.Visit",          "target": "p.BaseEntity",      "type": "EXTENDS"},
    {"source": "p.VisitController","target": "p.Visit",           "type": "IMPORTS"},
    {"source": "p.VisitController","target": "p.OwnerRepository", "type": "IMPORTS"},
    {"source": "p.NamedEntity",    "target": "p.BaseEntity",      "type": "EXTENDS"},
    {"source": "p.Person",         "target": "p.NamedEntity",     "type": "EXTENDS"},
    {"source": "p.CacheConfiguration","target": "p.WelcomeController","type": "IMPORTS"},
    {"source": "p.WelcomeController","target": "p.WebConfiguration","type": "IMPORTS"},
    {"source": "p.CrashController","target": "p.WelcomeController","type": "IMPORTS"},
]


class TestBuildGraph:
    def test_node_count(self):
        G = build_graph(PETCLINIC_EDGES)
        classes = {e["source"] for e in PETCLINIC_EDGES} | {e["target"] for e in PETCLINIC_EDGES}
        assert G.number_of_nodes() == len(classes)

    def test_self_loops_excluded(self):
        assert build_graph([{"source": "A", "target": "A", "type": "CALLS"}]).number_of_edges() == 0

    def test_weight_accumulates(self):
        G = build_graph([{"source": "A", "target": "B", "type": "IMPORTS"},
                         {"source": "A", "target": "B", "type": "CALLS"}])
        assert G["A"]["B"]["weight"] == 3

    def test_empty_edges(self):
        assert build_graph([]).number_of_nodes() == 0

    def test_missing_source_skipped(self):
        assert build_graph([{"source": None, "target": "B", "type": "CALLS"}]).number_of_edges() == 0


class TestLouvain:
    def test_returns_partition_for_all_nodes(self):
        G = build_graph(PETCLINIC_EDGES)
        assert set(run_louvain(G).keys()) == set(G.nodes())

    def test_partition_values_are_integers(self):
        assert all(isinstance(v, int) for v in run_louvain(build_graph(PETCLINIC_EDGES)).values())

    def test_synthetic_petclinic_gives_4_to_7_clusters(self):
        G = build_graph(PETCLINIC_EDGES)
        partition, _ = auto_tune_resolution(G, target_min=4, target_max=7)
        n = len(set(partition.values()))
        assert 4 <= n <= 7, f"Expected 4–7 clusters, got {n}"

    def test_resolution_higher_gives_more_clusters(self):
        G = build_graph(PETCLINIC_EDGES)
        assert len(set(run_louvain(G, resolution=3.0).values())) >= len(set(run_louvain(G, resolution=0.3).values()))


class TestHeuristicLabels:
    def test_vet_cluster(self):
        assert label_cluster_heuristic(0, ["p.Vet", "p.VetController", "p.Specialty"]) == "vet-service"

    def test_owner_cluster(self):
        assert label_cluster_heuristic(1, ["p.Owner", "p.OwnerController"]) == "owner-service"

    def test_pet_cluster(self):
        assert label_cluster_heuristic(2, ["p.Pet", "p.PetController"]) == "pet-service"

    def test_unknown_falls_back(self):
        assert label_cluster_heuristic(5, ["com.example.Foo"]) == "service-5"


class TestBuildClusterMap:
    def test_all_nodes_in_output(self):
        G = build_graph(PETCLINIC_EDGES)
        partition, _ = auto_tune_resolution(G)
        cluster_map, _ = build_cluster_map(partition, use_llm=False)
        assert set(cluster_map.keys()) == set(partition.keys())

    def test_no_duplicate_service_names(self):
        G = build_graph(PETCLINIC_EDGES)
        partition, _ = auto_tune_resolution(G)
        cluster_map, _ = build_cluster_map(partition, use_llm=False)
        assert len(set(cluster_map.values())) == len(set(partition.values()))

    def test_service_names_are_strings(self):
        G = build_graph(PETCLINIC_EDGES)
        partition, _ = auto_tune_resolution(G)
        cluster_map, _ = build_cluster_map(partition, use_llm=False)
        assert all(isinstance(v, str) for v in cluster_map.values())


class TestValidate:
    def test_passes_within_range(self, capsys):
        assert validate({f"c{i}": i % 5 for i in range(20)}, 4, 7) is True

    def test_fails_below_range(self, capsys):
        assert validate({f"c{i}": 0 for i in range(20)}, 4, 7) is False

    def test_fails_above_range(self, capsys):
        assert validate({f"c{i}": i for i in range(20)}, 4, 7) is False


class TestFileIO:
    def test_load_and_cluster_roundtrip(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
            json.dump(PETCLINIC_EDGES, f)
            path = f.name
        try:
            edges = load_edges_from_file(path)
            G = build_graph(edges)
            partition, _ = auto_tune_resolution(G)
            cluster_map, _ = build_cluster_map(partition, use_llm=False)
            with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as out:
                json.dump(cluster_map, out)
                out_path = out.name
            with open(out_path) as f:
                loaded = json.load(f)
            assert isinstance(loaded, dict) and len(loaded) == len(cluster_map)
        finally:
            os.unlink(path)
            os.unlink(out_path)
