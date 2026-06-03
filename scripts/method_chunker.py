#!/usr/bin/env python3
"""
Phase 2.5 — Method-level chunking for RAG indexing.

Walks a Java source tree, extracts every method as a chunk:
  - class FQN, method name, signature, body (~50–200 tokens)
  - content field: what gets embedded ("<fqn>.<method>\n<signature>\n<body>")

Uses javalang for AST parsing; falls back to regex on parse failures.

Usage
-----
  python method_chunker.py --source-dir /path/to/java --output chunks.json
  python method_chunker.py --source-dir /path/to/java   # prints to stdout
"""

import argparse
import json
import sys
from pathlib import Path
from typing import Optional

try:
    import javalang
    _HAS_JAVALANG = True
except ImportError:
    _HAS_JAVALANG = False
    print("[WARN] javalang not installed — pip install javalang", file=sys.stderr)


# ---------------------------------------------------------------------------
# Source-text helpers (work on raw lines, no AST needed)
# ---------------------------------------------------------------------------

def _extract_from_line(source_lines: list[str], start_line: int) -> tuple[str, str]:
    """
    Given a 1-based start line, return (signature, full_body) by scanning
    forward and counting braces.  signature is everything up to the first '{'.
    full_body includes the signature and the matched '{…}' block.
    """
    sig_parts: list[str] = []
    body_lines: list[str] = []
    depth = 0
    found_open = False

    for line in source_lines[start_line - 1:]:
        body_lines.append(line)
        if not found_open:
            sig_parts.append(line.strip())
        for ch in line:
            if ch == '{':
                depth += 1
                found_open = True
            elif ch == '}':
                depth -= 1
        if found_open and depth == 0:
            break

    signature = ' '.join(sig_parts).split('{')[0].strip()
    full_body  = '\n'.join(body_lines).strip()
    return signature, full_body


def _token_estimate(text: str) -> int:
    return int(len(text.split()) * 1.3)


# ---------------------------------------------------------------------------
# javalang-based extraction
# ---------------------------------------------------------------------------

def _chunk_file_javalang(filepath: Path) -> list[dict]:
    source = filepath.read_text(encoding='utf-8', errors='ignore')
    source_lines = source.splitlines()

    try:
        tree = javalang.parse.parse(source)
    except Exception as exc:
        print(f"  [WARN] javalang parse error {filepath.name}: {exc}", file=sys.stderr)
        return _chunk_file_regex(filepath)

    package = tree.package.name if tree.package else ''
    chunks: list[dict] = []

    for path, node in tree:
        if not isinstance(node, javalang.tree.ClassDeclaration):
            continue
        class_name = node.name
        class_fqn  = f"{package}.{class_name}" if package else class_name

        for method in (node.methods or []):
            if method.position is None:
                continue
            start_line = method.position.line
            signature, body = _extract_from_line(source_lines, start_line)
            tok_est = _token_estimate(body)

            chunks.append({
                "id":             f"{class_fqn}#{method.name}",
                "file":           str(filepath),
                "class_fqn":      class_fqn,
                "method_name":    method.name,
                "signature":      signature,
                "body":           body,
                "content":        f"// {class_fqn}\n{body}",
                "token_estimate": tok_est,
            })

    return chunks


# ---------------------------------------------------------------------------
# Regex-based fallback
# ---------------------------------------------------------------------------

import re

_METHOD_RE = re.compile(
    r'(?:(?:public|protected|private|static|final|abstract|synchronized)\s+)*'
    r'(?:[\w<>\[\]]+\s+)+(\w+)\s*\([^)]*\)',
    re.MULTILINE
)


def _chunk_file_regex(filepath: Path) -> list[dict]:
    source = filepath.read_text(encoding='utf-8', errors='ignore')
    source_lines = source.splitlines()

    # Guess package and class from file path / content
    pkg_m   = re.search(r'^package\s+([\w.]+);', source, re.MULTILINE)
    cls_m   = re.search(r'(?:class|interface|enum)\s+(\w+)', source)
    package = pkg_m.group(1) if pkg_m else ''
    class_n = cls_m.group(1) if cls_m else filepath.stem
    class_fqn = f"{package}.{class_n}" if package else class_n

    chunks: list[dict] = []
    for m in _METHOD_RE.finditer(source):
        method_name = m.group(1)
        line_no = source[:m.start()].count('\n') + 1
        signature, body = _extract_from_line(source_lines, line_no)
        tok_est = _token_estimate(body)
        chunks.append({
            "id":             f"{class_fqn}#{method_name}",
            "file":           str(filepath),
            "class_fqn":      class_fqn,
            "method_name":    method_name,
            "signature":      signature,
            "body":           body,
            "content":        f"// {class_fqn}\n{body}",
            "token_estimate": tok_est,
        })
    return chunks


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def chunk_file(filepath: Path) -> list[dict]:
    if _HAS_JAVALANG:
        return _chunk_file_javalang(filepath)
    return _chunk_file_regex(filepath)


def chunk_directory(source_dir: Path) -> list[dict]:
    java_files = sorted(source_dir.rglob("*.java"))
    print(f"  Found {len(java_files)} .java files in {source_dir}", file=sys.stderr)
    all_chunks: list[dict] = []
    for f in java_files:
        chunks = chunk_file(f)
        all_chunks.extend(chunks)
    # Deduplicate by id, keeping last occurrence
    seen: dict[str, dict] = {}
    for c in all_chunks:
        seen[c["id"]] = c
    deduped = list(seen.values())
    print(f"  Extracted {len(deduped)} method chunks", file=sys.stderr)
    return deduped


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Method-level Java chunker")
    parser.add_argument("--source-dir", required=True, help="Root Java source directory")
    parser.add_argument("--output",     default=None,  help="Output JSON file (default: stdout)")
    args = parser.parse_args()

    chunks = chunk_directory(Path(args.source_dir))
    data   = json.dumps(chunks, indent=2)

    if args.output:
        Path(args.output).write_text(data)
        print(f"Wrote {len(chunks)} chunks → {args.output}", file=sys.stderr)
    else:
        print(data)

    return 0


if __name__ == "__main__":
    sys.exit(main())
