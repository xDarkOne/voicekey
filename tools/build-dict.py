#!/usr/bin/env python3
"""Builds VoiceKey's on-screen translation dictionaries.

For each app: the strings the app shows by default (Chinese on this firmware), taken from its
APK on the projector, joined by resource name with VIDDA Nova's Russian translation of the same
strings. Out comes assets/translate/<package>.json:
  {"exact": {"声音模式": "Режим звука", ...},
   "patterns": [["^结果「(.+?)」：(.+?)$", "Результаты для «\\1»: \\2"], ...]}

usage: build-dict.py <nova overlays dir> <out dir> <package>=<nova xml>[,<nova xml>...] ...
Needs adb connected to the projector and aapt2 (ANDROID_HOME build-tools 35).
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

AAPT2 = os.path.expanduser(os.environ.get(
    "AAPT2", "~/.local/opt/android-sdk/build-tools/35.0.0/aapt2"))
# printf-style placeholders: %s, %d, %1$s, %2$d, %.1f ...
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdfxXc])")
CJK = "\u3400-\u9fff\uf900-\ufaff"
UNTRANSLATED = re.compile("[A-Za-z" + CJK + "]")
CYRILLIC = re.compile("[\u0400-\u04ff]")


def android_unescape(s):
    """Android string resource escapes: \\' \\" \\n \\t \\@ \\?, and surrounding quotes."""
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t"}.get(n, n))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def nova_strings(path):
    """name -> Russian text, from a VIDDA Nova overlay XML."""
    result = {}
    for el in ET.parse(path).getroot().iter("string"):
        text = "".join(el.itertext())
        result[el.get("name")] = android_unescape(text)
    return result


CACHE = os.path.expanduser("~/.cache/voicekey-dicts")


def resource_dump(package):
    """aapt2's resource dump of [package]'s APK on the projector, cached by version: APKs are
    big, so they go to disk under ~/.cache, not to a RAM-backed /tmp, and only once."""
    info = subprocess.run(["adb", "shell", "dumpsys", "package", package], capture_output=True, text=True).stdout
    m = re.search(r"versionCode=(\d+)", info)
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, f"{package}-{m.group(1) if m else 0}.res")
    if os.path.exists(cached):
        return open(cached, encoding="utf-8").read()
    path = subprocess.run(["adb", "shell", "pm", "path", package], capture_output=True,
                          text=True, check=True).stdout.split("\n")[0].removeprefix("package:").strip()
    with tempfile.TemporaryDirectory(dir=CACHE) as tmp:
        apk = os.path.join(tmp, "base.apk")
        subprocess.run(["adb", "pull", "-q", path, apk], check=True)
        dump = subprocess.run([AAPT2, "dump", "resources", apk], capture_output=True,
                              text=True).stdout
    with open(cached, "w", encoding="utf-8") as f:
        f.write(dump)
    return dump


def string_names(package):
    return set(re.findall(r"resource 0x[0-9a-f]+ string/(\S+)", resource_dump(package)))


def device_strings(package):
    """name -> default-config text of [package]'s strings that have no Russian of their own,
    from its APK on the projector: what the app shows untranslated on a Russian system."""
    dump = resource_dump(package)
    result, has_ru, name = {}, set(), None
    for line in dump.splitlines():
        m = re.match(r"\s+resource 0x[0-9a-f]+ (\w+)/(\S+)", line)
        if m:
            name = m.group(2) if m.group(1) == "string" else None
            continue
        if not name:
            continue
        m = re.match(r'\s+\(\) "(.*)"\s*$', line)
        if m:
            # aapt2 prints the value escaped the way it is stored: \" \\ \n
            result[name] = m.group(1).encode("utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8")
        elif re.match(r"\s+\(ru(-r\w+)?\) ", line):
            has_ru.add(name)
    return {k: v for k, v in result.items() if k not in has_ru}


def placeholders(s):
    """The placeholders of [s] in order, each as its 1-based argument number."""
    found, seq = [], 0
    for m in PLACEHOLDER.finditer(s):
        seq += 1
        found.append(int(m.group(1)) if m.group(1) else seq)
    return found


def to_pattern(source, target):
    """A regex for the shown source text, and a replacement template for the Russian one."""
    args = placeholders(source)
    if sorted(args) != sorted(placeholders(target)):
        return None  # Arguments don't match: such a translation would show the wrong values.
    parts, last, group_of = [], 0, {}
    for i, m in enumerate(PLACEHOLDER.finditer(source)):
        parts.append(re.escape(source[last:m.start()]))
        parts.append("(.+?)")
        group_of[args[i]] = i + 1
        last = m.end()
    parts.append(re.escape(source[last:]))
    seq = [0]

    def repl(m):
        seq[0] += 1
        n = int(m.group(1)) if m.group(1) else seq[0]
        return "\\" + str(group_of[n])

    return "^" + "".join(parts) + "$", PLACEHOLDER.sub(repl, target.replace("\\", "\\\\"))


def main():
    nova_dir, out_dir, specs = sys.argv[1], sys.argv[2], sys.argv[3:]
    # --extra=<file>: {"<package>": {"<source>": "<Russian>"}} for what VIDDA Nova leaves out
    # (qwen-translate.py's cache). VIDDA Nova's own translation wins where both have one.
    extra = {}
    if specs and specs[0].startswith("--extra="):
        with open(specs.pop(0).split("=", 1)[1], encoding="utf-8") as f:
            extra = json.load(f)
    os.makedirs(out_dir, exist_ok=True)
    for spec in specs:
        package, files = spec.split("=")
        ru = {}
        for f in filter(None, files.split(",")):
            ru.update(nova_strings(os.path.join(nova_dir, f)))
        more = extra.get(package, {})
        shown = device_strings(package)
        exact, patterns, skipped = {}, [], 0
        for name, source in shown.items():
            target = ru.get(name) or more.get(source.strip())
            # Chinese or English shown as is; already-Russian ones need nothing.
            if not target or source == target or CYRILLIC.search(source) or not UNTRANSLATED.search(source):
                continue
            if PLACEHOLDER.search(source):
                p = to_pattern(source, target)
                if p:
                    patterns.append(list(p))
                else:
                    skipped += 1
            else:
                exact.setdefault(source.strip(), target.strip())
        # Hand-made short forms and words the app cuts off itself, over everything else.
        manual = os.path.join(os.path.dirname(os.path.abspath(__file__)), "overrides", package + ".json")
        if os.path.exists(manual):
            with open(manual, encoding="utf-8") as f:
                exact.update({k: v for k, v in json.load(f).items() if not k.startswith("_")})
        with open(os.path.join(out_dir, package + ".json"), "w", encoding="utf-8") as out:
            json.dump({"exact": exact, "patterns": patterns}, out, ensure_ascii=False, indent=0)
        print(f"{package}: {len(exact)} phrases, {len(patterns)} patterns, {skipped} skipped "
              f"(device strings {len(shown)}, VIDDA Nova {len(ru)}, extra {len(more)})")


if __name__ == "__main__":
    main()
