#!/usr/bin/env python3
"""
Phase 2.5 — Qdrant indexer: full RAG pipeline for one job.

Pipeline
--------
  1. method_chunker  → extract method-level chunks from Java sources
  2. embedder        → CodeBERT encode each chunk to 768-dim vector
  3. qdrant_client   → create/replace collection job_{id}, upsert all points

Collection name   : job_{job_id}
Vector dimensions : 768   (CodeBERT CLS)
Distance metric   : Cosine
Point payload     : { class_fqn, method_name, signature, body, file_path }

Usage
-----
  python qdrant_indexer.py \\
    --job-id 1 \\
    --source-dir /path/to/java \\
    [--qdrant-url http://localhost:6333] \\
    [--batch-size 16]

Exit codes
----------
  0 = success (prints JSON summary to stdout)
  1 = failure  (error on stderr)
"""

import argparse
import json
import os
import sys
from pathlib import Path

# Allow sibling imports when called from any working directory
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from method_chunker import chunk_directory
from embedder import load_model, encode_texts

from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct

VECTOR_SIZE = 768


# ---------------------------------------------------------------------------
# Core indexing function (importable by other modules)
# ---------------------------------------------------------------------------

def index_job(job_id: int,
              source_dir: str,
              qdrant_url: str = "http://localhost:6333",
              batch_size: int = 16) -> dict:
    """
    Index all Java methods under source_dir into Qdrant collection job_{job_id}.
    Returns a summary dict with indexed point count.
    """
    print(f"[qdrant_indexer] Job {job_id}: source_dir={source_dir}", file=sys.stderr)

    # 1. Chunk
    chunks = chunk_directory(Path(source_dir))
    if not chunks:
        print("[qdrant_indexer] No chunks extracted — check source directory.", file=sys.stderr)
        return {"status": "ok", "collection": f"job_{job_id}", "points_indexed": 0}

    # 2. Embed
    print(f"[qdrant_indexer] Embedding {len(chunks)} chunks …", file=sys.stderr)
    tokenizer, model = load_model()
    texts      = [c["content"] for c in chunks]
    embeddings = encode_texts(texts, tokenizer, model, batch_size=batch_size)

    # 3. Upsert to Qdrant
    collection_name = f"job_{job_id}"
    client          = QdrantClient(url=qdrant_url, check_compatibility=False)

    print(f"[qdrant_indexer] Creating/replacing collection '{collection_name}' …", file=sys.stderr)
    # check_compatibility=False: suppress client/server version mismatch warning
    if client.collection_exists(collection_name):
        client.delete_collection(collection_name)
    client.create_collection(
        collection_name=collection_name,
        vectors_config=VectorParams(size=VECTOR_SIZE, distance=Distance.COSINE),
    )

    points = [
        PointStruct(
            id=idx,
            vector=embeddings[idx],
            payload={
                "class_fqn":   c["class_fqn"],
                "method_name": c["method_name"],
                "signature":   c["signature"],
                "body":        c["body"],
                "file_path":   c["file"],
            },
        )
        for idx, c in enumerate(chunks)
    ]

    # Upload in batches of 100
    upload_batch = 100
    for i in range(0, len(points), upload_batch):
        client.upsert(collection_name=collection_name, points=points[i : i + upload_batch])
        print(f"[qdrant_indexer] Upserted {min(i + upload_batch, len(points))} / {len(points)}",
              file=sys.stderr)

    summary = {
        "status":         "ok",
        "collection":     collection_name,
        "points_indexed": len(points),
    }
    print(f"[qdrant_indexer] Done — {len(points)} points in '{collection_name}'", file=sys.stderr)
    return summary


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Index a Java codebase into Qdrant")
    parser.add_argument("--job-id",      required=True, type=int)
    parser.add_argument("--source-dir",  required=True)
    parser.add_argument("--qdrant-url",  default="http://localhost:6333")
    parser.add_argument("--batch-size",  default=16, type=int)
    args = parser.parse_args()

    try:
        summary = index_job(
            job_id     = args.job_id,
            source_dir = args.source_dir,
            qdrant_url = args.qdrant_url,
            batch_size = args.batch_size,
        )
        # Emit clean JSON to stdout — Java parses this
        print(json.dumps(summary))
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
