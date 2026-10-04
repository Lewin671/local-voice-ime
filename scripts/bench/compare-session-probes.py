#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
"""Compare exact production-session audio/text, with early capture teardown required."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("baseline", type=Path)
parser.add_argument("optimized", type=Path)
args = parser.parse_args()
before = {p.name: p for p in args.baseline.glob("*.json")}
after = {p.name: p for p in args.optimized.glob("*.json")}
if not before or before.keys() != after.keys():
    raise SystemExit("FAIL: missing or different recordings")
fields = ("finals", "sentenceStops", "capturedSamples", "capturedSha256")
for name in sorted(before):
    a = json.loads(before[name].read_text())
    b = json.loads(after[name].read_text())
    if "error" in a or "error" in b:
        raise SystemExit(f"FAIL: {name}: session error")
    for field in fields:
        if a[field] != b[field]:
            raise SystemExit(f"FAIL: {name}: {field} differs")
    if a["sourceStopCalls"] != 1 or b["sourceStopCalls"] != 1:
        raise SystemExit(f"FAIL: {name}: source teardown count differs")
    if not b["captureStoppedBeforeFinishing"]:
        raise SystemExit(f"FAIL: {name}: capture still open during finishing")
    print(f"PASS: {name}: exact audio/text; capture closed before finishing")
