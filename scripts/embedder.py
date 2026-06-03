#!/usr/bin/env python3
"""
Phase 2.5 — CodeBERT batch encoding for RAG indexing.

Model : microsoft/codebert-base  (768-dim, downloads ~500 MB on first run)
Output: CLS-token vector per input text, float32.

Usage
-----
  # Batch mode (for indexing): reads chunks JSON, writes embeddings JSON
  python embedder.py --input chunks.json --output embeddings.json

  # Single-query mode (for retrieval, called by Java RagRetriever):
  python embedder.py --text "find owner by last name"
  → prints a JSON array of 768 floats to stdout
"""

import argparse
import json
import sys
from pathlib import Path
from typing import Optional

import torch
from transformers import AutoTokenizer, AutoModel

MODEL_NAME  = "microsoft/codebert-base"
MAX_TOKENS  = 512
BATCH_SIZE  = 16

# ---------------------------------------------------------------------------
# Model loading (lazy singleton)
# ---------------------------------------------------------------------------

_tokenizer = None
_model     = None


def load_model():
    global _tokenizer, _model
    if _tokenizer is None:
        print(f"Loading {MODEL_NAME} …", file=sys.stderr)
        _tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
        _model     = AutoModel.from_pretrained(MODEL_NAME)
        _model.eval()
        print("Model loaded.", file=sys.stderr)
    return _tokenizer, _model


# ---------------------------------------------------------------------------
# Encoding
# ---------------------------------------------------------------------------

def encode_texts(texts: list[str],
                 tokenizer=None,
                 model=None,
                 batch_size: int = BATCH_SIZE) -> list[list[float]]:
    """
    Encode a list of text strings into 768-dim CLS vectors.
    Loads the model if tokenizer/model are not provided.
    """
    if tokenizer is None or model is None:
        tokenizer, model = load_model()

    all_embeddings: list[list[float]] = []

    for i in range(0, len(texts), batch_size):
        batch = texts[i : i + batch_size]
        inputs = tokenizer(
            batch,
            padding=True,
            truncation=True,
            max_length=MAX_TOKENS,
            return_tensors="pt",
        )
        with torch.no_grad():
            outputs = model(**inputs)
        # CLS token: last_hidden_state[:, 0, :]
        cls_vectors = outputs.last_hidden_state[:, 0, :].numpy()
        all_embeddings.extend(cls_vectors.tolist())
        print(f"  Embedded batch {i // batch_size + 1} / {-(-len(texts) // batch_size)}",
              file=sys.stderr)

    return all_embeddings


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="CodeBERT embedder")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--input",  help="Input chunks.json (batch mode)")
    group.add_argument("--text",   help="Single text to embed (query mode)")
    parser.add_argument("--output", default=None, help="Output file (batch mode, default: stdout)")
    args = parser.parse_args()

    tokenizer, model = load_model()

    if args.text:
        # Single-query mode: output one 768-dim JSON array to stdout
        vectors = encode_texts([args.text], tokenizer, model)
        print(json.dumps(vectors[0]))
        return 0

    # Batch mode
    chunks = json.loads(Path(args.input).read_text())
    texts  = [c["content"] for c in chunks]
    print(f"Encoding {len(texts)} chunks …", file=sys.stderr)
    vectors = encode_texts(texts, tokenizer, model)

    result = [
        {"id": c.get("id", i), "vector": vectors[i]}
        for i, c in enumerate(chunks)
    ]
    data = json.dumps(result, indent=2)

    if args.output:
        Path(args.output).write_text(data)
        print(f"Wrote {len(result)} embeddings → {args.output}", file=sys.stderr)
    else:
        print(data)

    return 0


if __name__ == "__main__":
    sys.exit(main())
