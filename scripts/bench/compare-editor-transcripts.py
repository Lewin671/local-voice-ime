#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
"""Strictly compare paired e2e-voice editor text, independent of its error threshold."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("baseline", type=Path)
parser.add_argument("optimized", type=Path)
args = parser.parse_args()


def transcripts(path):
    prefix = "    actual:   "
    return [line[len(prefix):] for line in path.read_text().splitlines()
            if line.startswith(prefix)]


a = transcripts(args.baseline)
b = transcripts(args.optimized)
if not a or len(a) != len(b):
    raise SystemExit("FAIL: missing or different numbers of recordings")
for index, (before, after) in enumerate(zip(a, b), 1):
    if before != after:
        raise SystemExit(f"FAIL: recording {index}: editor text differs")
    print(f"PASS: recording {index}: identical editor text")
