#!/usr/bin/env python3
"""Lists what the projector's apps still show untranslated: strings with no Russian of their own
and none from VIDDA Nova's dictionaries. These are the ones to translate some other way
(qwen-translate.py). Prints {"<package>": ["<source text>", ...]} as JSON.

usage: untranslated.py <dictionaries dir> <package> [<package> ...]
"""
import importlib.util
import json
import os
import re
import sys

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_dict", os.path.join(here, "build-dict.py"))
build_dict = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_dict)

# Not shown to people: resource keys, package and class names, paths, addresses.
IDENTIFIER = re.compile(r"^[a-z0-9_.:/#@-]+$")
ADDRESS = re.compile(r"(https?://|www\.|@[a-z0-9-]+\.[a-z]|\.(com|cn|net|org)\b)", re.I)
LETTER = re.compile("[A-Za-z" + build_dict.CJK + "]")


def worth_translating(s):
    s = s.strip()
    if not s or len(s) > 400 or not LETTER.search(s):
        return False
    if IDENTIFIER.match(s) or ADDRESS.search(s):
        return False
    if build_dict.CYRILLIC.search(s):
        return False
    return True


def covered(s, exact, patterns):
    t = s.strip()
    if t in exact:
        return True
    return any(re.match(p, t, re.S) for p, _ in patterns)


def main():
    dicts_dir, packages = sys.argv[1], sys.argv[2:]
    result = {}
    for package in packages:
        path = os.path.join(dicts_dir, package + ".json")
        exact, patterns = {}, []
        if os.path.exists(path):
            d = json.load(open(path, encoding="utf-8"))
            exact, patterns = d["exact"], d["patterns"]
        shown = build_dict.device_strings(package)
        todo = sorted({s.strip() for s in shown.values() if worth_translating(s) and not covered(s, exact, patterns)})
        result[package] = todo
        print(f"{package}: {len(todo)} untranslated", file=sys.stderr)
    json.dump(result, sys.stdout, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
