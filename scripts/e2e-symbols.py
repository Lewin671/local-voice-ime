#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
"""Capture and exercise all four symbol pages using real keys and editor readback.

After installation: e2e-symbols.py <package> <output-dir> <theme> [--screenshots-only].
ui-shots.sh uses --screenshots-only; omit it for editor/dictation regression checks.
Does not install/reset the app; selects its keyboard for the run and enables Pinyin next to
English if a fresh install left it out.
"""
import atexit
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import wave
import time
import xml.etree.ElementTree as ET

pkg, directory, theme = sys.argv[1:4]
capture_only = "--screenshots-only" in sys.argv[4:]
out = Path(directory)
out.mkdir(parents=True, exist_ok=True)
activity = f"{pkg}/org.fcitx.fcitx5.android.debug.TestInputActivity"
# Independent expected rows transcribed from the Apple references in DESIGN.md.
layouts = {
    "en-123": [list("1234567890"), list('-/:;()$&@"'), list(".,?!'")],
    "en-more": [list("[]{}#%^*+="), list("_\\|~<>€£¥•"), list(".,?!'")],
    "zh-123": [list("1234567890"), list("-/：；（）¥@“”"), list("。，、？！.")],
    "zh-more": [list("【】｛｝#%^*+="), list("_—\\｜～《》$&·"), ["…", "，", "^_^", "？", "！", "'"]],
}


def adb(*args):
    return subprocess.check_output(["adb", *args])


# Make the debug keyboard current (other device scripts restore the previous one when they
# finish) and restore whatever was current before; a no-op when ui-shots.sh already chose it.
ime = f"{pkg}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
# Read before force-stop below: stopping the current keyboard makes Android fall back to another.
previous_ime = adb("shell", "settings", "get", "secure", "default_input_method").decode().strip()
# A fresh install (e2e-scenarios.sh reinstalls) enables only English; add Pinyin.
profile = f"/sdcard/Android/data/{pkg}/files/config/profile"
if b"Name=pinyin" not in subprocess.run(["adb", "shell", "cat", profile], capture_output=True).stdout:
    with tempfile.NamedTemporaryFile("w", suffix=".profile") as f:
        f.write("[Groups/0]\nName=Default\nDefault Layout=us\nDefaultIM=keyboard-us\n\n"
                "[Groups/0/Items/0]\nName=keyboard-us\n\n[Groups/0/Items/1]\nName=pinyin\n\n"
                "[GroupOrder]\n0=Default\n")
        f.flush()
        adb("shell", "am", "force-stop", pkg)
        adb("push", f.name, profile)
adb("shell", "ime", "enable", ime)
adb("shell", "ime", "set", ime)
if previous_ime != ime:
    atexit.register(lambda: subprocess.run(["adb", "shell", "ime", "set", previous_ime],
                                           stdout=subprocess.DEVNULL))


def dump():
    for _ in range(4):
        try:
            adb("shell", "uiautomator", "dump", "--windows", "/sdcard/ime-symbols.xml")
            return ET.fromstring(adb("shell", "cat", "/sdcard/ime-symbols.xml"))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(0.5)
    raise RuntimeError("Could not dump the UI")


def find(root, name, keyboard=False):
    if keyboard:
        root = next((n for n in root.iter("node") if n.get("resource-id", "").endswith(":id/keyboard_view")), None)
        if root is None:
            raise RuntimeError("Keyboard window missing; check for concurrent device use")
    nodes = list(root.iter("node"))
    for n in nodes:
        if n.get("content-desc") == name or n.get("resource-id", "").endswith(":id/" + name):
            return n
    return next(n for n in nodes if n.get("text") == name)


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(name, root=None, keyboard=True):
    focus = "\n".join(line for line in adb("shell", "dumpsys", "window").decode().splitlines()
                      if "mCurrentFocus=" in line)
    if pkg not in focus:
        raise RuntimeError(f"Test app lost focus (concurrent device use): {focus.strip()}")
    x, y = center(find(root if root is not None else dump(), name, keyboard=keyboard))
    adb("shell", "input", "tap", str(x), str(y))


def field(root=None):
    return find(root if root is not None else dump(), "test-input").get("text", "")


def fresh():
    adb("shell", "am", "start", "-W", "--activity-clear-task", "-n", activity)
    for _ in range(15):
        time.sleep(0.3)
        root = dump()
        try:
            find(root, "?123", keyboard=True)
            assert field(root) == "", repr(field(root))
            return root
        except (StopIteration, AssertionError):
            pass
    raise RuntimeError("Fresh letter keyboard did not appear")


def choose(language):
    root = fresh()
    x, y = center(find(root, "button_lang", keyboard=True))
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "650")
    root = dump()
    labels = ("Pinyin", "拼音") if language == "zh" else ("English",)
    node = next((n for n in root.iter("node") if any(label in n.get("text", "") for label in labels)), None)
    if node is None:
        raise RuntimeError(f"Enable {labels[0]} in the debug app Input Methods list first")
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.4)


results = []
for language in ("zh", "en"):
    choose(language)
    for page in ("123", "more"):
        fresh()
        tap("?123")
        if page == "more":
            tap("#+=")
        root = dump()
        name = f"{language}-{page}"
        path = out / f"{theme}-symbols-{name}.png"
        path.write_bytes(adb("exec-out", "screencap", "-p"))
        (out / f"{theme}-symbols-{name}.xml").write_bytes(ET.tostring(root))
        if capture_only:
            print(path, flush=True)
            tap("ABC", root)
            continue
        rows = layouts[name]
        expected = ""
        for row in rows:
            for label in row:
                if label == "^_^":  # A panel action, not a literal symbol.
                    continue
                tap(label, root)
                expected += label
        committed = dump()
        (out / f"{theme}-symbols-{name}-committed.xml").write_bytes(ET.tostring(committed))
        actual = field(committed)
        assert actual == expected, f"{name}: expected {expected!r}, got {actual!r}"
        # Backspace keeps the layout; use a single-code-point final symbol.
        tap("button_backspace")
        expected = expected[:-1]
        assert field() == expected
        # Several symbols must not leave the page; switch pages and carry the decision along.
        tap("123" if page == "more" else "#+=")
        tap("button_space")
        root = dump()
        assert field(root) == expected + " ", (name, field(root))
        find(root, "?123", keyboard=True)
        # A fresh symbol visit must always open 123, even after leaving the second page.
        tap("?123", root)
        root = dump()
        find(root, "1", keyboard=True)
        tap("button_space", root)  # No symbol typed: stay on symbols, commit a literal space.
        root = dump()
        assert field(root) == expected + "  "
        find(root, "#+=", keyboard=True)
        tap("ABC", root)
        find(dump(), "?123", keyboard=True)
        results.append({"layout": name, "committed": actual, "space_returns": True,
                        "empty_space_stays": True, "backspace": True, "screenshot": str(path)})
        (out / f"{theme}-symbol-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
        print(f"PASS {theme} {name}: exact labels, backspace, page changes, space/ABC; {path}", flush=True)

if capture_only:
    fresh()
    sys.exit(0)

# Numeric/phone editors must still get the original number pad.
for input_type in ("number", "phone"):
    adb("shell", "am", "start", "-W", "--activity-clear-task", "-n", activity,
        "--es", "input_type", input_type)
    time.sleep(0.5)
    root = dump()
    find(root, "button_mini_space", keyboard=True)
    tap("1", root)
    tap("2", root)
    assert field() == "12", f"{input_type}: number pad did not commit digits"
    print(f"PASS {theme}: {input_type} editor retains number pad", flush=True)

if theme == "light":
    # Exercise real dispatch while recognition is active. A long local WAV gives UI dumps
    # time to finish; both the recognizer and editor path are the production ones.
    remote = f"/sdcard/Android/data/{pkg}/files/voice-test.wav"
    with tempfile.TemporaryDirectory() as work:
        previous = Path(work) / "previous.wav"
        had_previous = subprocess.run(["adb", "pull", remote, str(previous)],
                                      capture_output=True).returncode == 0
        long_wav = Path(work) / "long.wav"
        with wave.open("voice/test-wavs/zh.wav", "rb") as source:
            params, audio = source.getparams(), source.readframes(source.getnframes())
        with wave.open(str(long_wav), "wb") as target:
            target.setparams(params)
            target.writeframes(audio * 12)
        try:
            adb("push", str(long_wav), remote)
            for language in ("zh", "en"):
                choose(language)
                fresh()
                tap("Voice input", keyboard=False)
                root = dump()
                find(root, "Stop listening")
                tap("?123", root)
                root = dump()
                find(root, "Stop listening")
                tap("1", root)
                tap("#+=", root)
                root = dump()
                find(root, "Stop listening")
                tap("#", root)
                # Holding space while hands-free must neither insert nor leave symbols.
                x, y = center(find(root, "button_space", keyboard=True))
                adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "650")
                root = dump()
                find(root, "123", keyboard=True)
                find(root, "Stop listening")
                tap("button_space", root)
                root = dump()
                find(root, "?123", keyboard=True)
                find(root, "Stop listening")
                tap("button_return", root)
                tap("button_backspace", root)
                root = dump()
                find(root, "Stop listening")
                tap("Stop listening", root, keyboard=False)
                time.sleep(4)
                committed = dump()
                actual = field(committed)
                (out / f"{theme}-symbols-{language}-dictation-committed.xml").write_bytes(ET.tostring(committed))
                assert re.search(r"1.*#.* ", actual, re.S), f"{language}: symbols missing/out of order: {actual!r}"
                print(f"PASS {language}: pages, digits, symbol, hold, space, return/backspace keep listening; {actual!r}", flush=True)
        finally:
            if had_previous:
                adb("push", str(previous), remote)
            else:
                adb("shell", "rm", "-f", remote)

# Return/ABC are shared with the letter keyboard; check the new direct picker entry too.
fresh()
tap("?123")
root = dump()
x, y = center(find(root, "ABC", keyboard=True))
adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "650")
root = dump()
assert not any(n.get("resource-id", "").endswith(":id/keyboard_view") for n in root.iter("node")), "ABC hold did not open picker"
find(root, "button_space")
print(f"PASS {theme}: hold ABC opens the symbol picker", flush=True)
(out / f"{theme}-symbol-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
# Leave a fresh letter field for ui-shots.sh's dictation states.
fresh()
