#!/usr/bin/env python3
"""Compare Java and Python JSON fixtures for 1:1 behavior parity.

Usage:
  python tools/parity/compare_json.py --java fixtures/java --python fixtures/python
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def compare(a: Any, b: Any, path: str, float_epsilon: float) -> list[str]:
    diffs: list[str] = []

    if isinstance(a, float) and isinstance(b, float):
        if not math.isclose(a, b, rel_tol=float_epsilon, abs_tol=float_epsilon):
            diffs.append(f"{path}: float mismatch java={a!r} python={b!r}")
        return diffs

    if type(a) is not type(b):
        diffs.append(f"{path}: type mismatch java={type(a).__name__} python={type(b).__name__}")
        return diffs

    if isinstance(a, dict):
        a_keys = set(a.keys())
        b_keys = set(b.keys())
        for missing in sorted(a_keys - b_keys):
            diffs.append(f"{path}: missing key in python: {missing}")
        for extra in sorted(b_keys - a_keys):
            diffs.append(f"{path}: extra key in python: {extra}")
        for key in sorted(a_keys & b_keys):
            diffs.extend(compare(a[key], b[key], f"{path}.{key}", float_epsilon))
        return diffs

    if isinstance(a, list):
        if len(a) != len(b):
            diffs.append(f"{path}: list length mismatch java={len(a)} python={len(b)}")
            return diffs
        for i, (item_a, item_b) in enumerate(zip(a, b)):
            diffs.extend(compare(item_a, item_b, f"{path}[{i}]", float_epsilon))
        return diffs

    if a != b:
        diffs.append(f"{path}: value mismatch java={a!r} python={b!r}")

    return diffs


def collect_json_files(directory: Path) -> dict[str, Path]:
    return {
        str(path.relative_to(directory)): path
        for path in sorted(directory.rglob("*.json"))
        if path.is_file()
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare Java and Python JSON fixtures")
    parser.add_argument("--java", required=True, type=Path, help="Directory containing Java fixtures")
    parser.add_argument("--python", required=True, type=Path, help="Directory containing Python fixtures")
    parser.add_argument(
        "--float-epsilon",
        type=float,
        default=0.0,
        help="Optional float tolerance (default: 0.0 exact)",
    )
    args = parser.parse_args()

    if not args.java.is_dir():
        print(f"error: Java fixtures directory not found: {args.java}", file=sys.stderr)
        return 2
    if not args.python.is_dir():
        print(f"error: Python fixtures directory not found: {args.python}", file=sys.stderr)
        return 2

    java_files = collect_json_files(args.java)
    python_files = collect_json_files(args.python)

    java_names = set(java_files.keys())
    python_names = set(python_files.keys())

    missing_in_python = sorted(java_names - python_names)
    extra_in_python = sorted(python_names - java_names)

    has_failure = False

    if missing_in_python:
        has_failure = True
        print("Missing fixture files in python:")
        for name in missing_in_python:
            print(f"  - {name}")

    if extra_in_python:
        has_failure = True
        print("Extra fixture files in python:")
        for name in extra_in_python:
            print(f"  - {name}")

    for name in sorted(java_names & python_names):
        java_obj = load_json(java_files[name])
        python_obj = load_json(python_files[name])
        diffs = compare(java_obj, python_obj, path="$", float_epsilon=args.float_epsilon)
        if diffs:
            has_failure = True
            print(f"\nMismatch: {name}")
            for diff in diffs:
                print(f"  - {diff}")

    if has_failure:
        print("\nParity check failed.")
        return 1

    print("Parity check passed: Java and Python fixtures are 1:1.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
