#!/usr/bin/env python3
"""Helpers for scripts/e2e-voice.sh, working on `uiautomator dump --windows` output.

  ui.py center <ui.xml> <name>     print "x y" of the node's center
  ui.py bounds <ui.xml> <name>     print "left top right bottom" of the node
  ui.py text <ui.xml> <name>       print the node's text
                                   <name> is a content description or a resource id name
  ui.py score <expected> <actual>          print the character error rate (0..1), ignoring
                                           punctuation, whitespace and case
"""
import re
import sys
import xml.etree.ElementTree as ET


def find(path, name):
    """Find a node by content description, or by resource id (the part after ':id/')."""
    for node in ET.parse(path).iter("node"):
        if node.get("content-desc") == name or node.get("resource-id", "").endswith(":id/" + name):
            return node
    sys.exit(f"node '{name}' not found")


def normalize(s):
    return re.sub(r"[\W_]+", "", s).lower()


def edit_distance(a, b):
    row = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        prev, row[0] = row[0], i
        for j, cb in enumerate(b, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (ca != cb))
    return row[-1]


cmd = sys.argv[1]
if cmd == "center":
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", find(sys.argv[2], sys.argv[3]).get("bounds")))
    print((x1 + x2) // 2, (y1 + y2) // 2)
elif cmd == "bounds":
    print(*re.findall(r"\d+", find(sys.argv[2], sys.argv[3]).get("bounds")))
elif cmd == "text":
    print(find(sys.argv[2], sys.argv[3]).get("text"))
elif cmd == "score":
    expected, actual = normalize(sys.argv[2]), normalize(sys.argv[3])
    print(f"{edit_distance(expected, actual) / max(len(expected), 1):.3f}")
else:
    sys.exit(__doc__)
