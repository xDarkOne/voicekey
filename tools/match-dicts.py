#!/usr/bin/env python3
"""Finds which app on the projector each VIDDA Nova translation file is for, by the names of
the strings: the installed package that has the most of a file's names. Prints the specs
build-dict.py takes ("<package>=<file>,<file>"), one per package; weak matches go to stderr.

usage: match-dicts.py <nova overlays dir> [min share of a file's names, default 0.4]
"""
import importlib.util
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_dict", os.path.join(here, "build-dict.py"))
build_dict = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_dict)

CANDIDATE = re.compile(r"^(com\.hisense|com\.jamdeo|com\.vt|com\.keylab|com\.hispeech|com\.ju|com\.android|com\.ms|com\.meetingroom|android$)")


def main():
    nova_dir = sys.argv[1]
    min_share = float(sys.argv[2]) if len(sys.argv) > 2 else 0.4
    listing = subprocess.run(["adb", "shell", "pm", "list", "packages"], capture_output=True, text=True).stdout
    packages = sorted(p.split(":", 1)[1].strip() for p in listing.splitlines() if p.startswith("package:"))
    packages = [p for p in packages if CANDIDATE.match(p)]
    names = {}
    for p in packages:
        try:
            names[p] = build_dict.string_names(p)
        except Exception as e:  # noqa: BLE001 - a package that can't be read is just skipped
            print(f"skip {p}: {e}", file=sys.stderr)
    chosen = {}
    for f in sorted(os.listdir(nova_dir)):
        if not f.endswith(".xml"):
            continue
        want = {el.get("name") for el in ET.parse(os.path.join(nova_dir, f)).getroot().iter("string")}
        if not want:
            continue
        best, hits = max(((p, len(want & n)) for p, n in names.items()), key=lambda x: x[1], default=(None, 0))
        share = hits / len(want)
        line = f"{f}: {best} {hits}/{len(want)} ({share:.0%})"
        if best and share >= min_share:
            chosen.setdefault(best, []).append(f)
            print(line, file=sys.stderr)
        else:
            print(line + " — weak, skipped", file=sys.stderr)
    for p, files in sorted(chosen.items()):
        print(f"{p}={','.join(files)}")


if __name__ == "__main__":
    main()
