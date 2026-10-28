#!/usr/bin/env python3
"""Translates what VIDDA Nova's dictionaries leave out, with the local Qwen (llama-swap,
OpenAI API on 127.0.0.1:9292). Input: untranslated.py's JSON. Output: a cache
{"<package>": {"<source>": "<Russian>"}} that is extended run after run, never redone.

usage: qwen-translate.py <untranslated.json> <cache.json> [batch size, default 60]
"""
import json
import os
import re
import sys
import time
import urllib.request

API = os.environ.get("QWEN_API", "http://127.0.0.1:9292/v1/chat/completions")
MODEL = os.environ.get("QWEN_MODEL", "qwen3.8-27b-pro")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc]")

# What each app is, so short words get the right sense ("Source" of a signal, not of code).
CONTEXT = {
    "com.hisense.tv.inputs": "the input source picker (HDMI, AV, antenna inputs)",
    "com.android.tv.settings": "the projector's system settings",
    "com.android.settings": "Android system settings",
    "com.android.systemui": "Android system UI: volume, notifications, dialogs",
    "com.android.packageinstaller": "the app installer",
    "com.jamdeo.tv.mediacenter": "the media center: USB and network files, photos, videos, music",
    "com.hisense.tv.familycloud": "the family cloud photo and video album",
    "com.jamdeo.tv.livetv": "the live TV player (channels, EPG)",
    "com.jamdeo.screensaver": "the screensaver",
    "com.jamdeo.tv.fte": "the first-time setup wizard",
    "com.ms.ucast.ucastapp": "screen casting from phones (Miracast, DLNA, AirPlay)",
    "com.hisense.multiscreen.screenhelper": "the phone-to-projector screen sharing helper",
    "com.vt.ambiencelife": "the ambient mode (art, clock, photos on screen while idle)",
    "com.hisense.bluetoothpair": "Bluetooth remote and device pairing",
    "com.hisense.screen": "projector screen and picture adjustment",
}

SYSTEM = """You translate user-interface strings of a Hisense/Vidda laser projector (Android TV) into Russian.
Rules:
- Write natural, concise Russian as in Russian-language Android TV menus. Buttons and menu items short.
- Source strings are Chinese or English; translate both.
- Keep every placeholder exactly as is and keep their count: %s, %d, %1$s, %2$d, %.1f.
- Keep line breaks (\\n) where they are.
- Do not translate brand names, standards, codecs and abbreviations: HDMI, ARC, eARC, MHL, AV, ATV, DTV, DTMB, DVB-C, DVB-T, CATV, USB, Wi-Fi, Bluetooth, DLNA, Miracast, AirPlay, Dolby, HDR, 4K, AI, IP, MAC, Vidda, Hisense, VIDAA.
- Upper-case source items (OK, CANCEL, DONE) become normal-case Russian (ОК, Отмена, Готово).
Answer with one JSON object only: each input number (as a string key) mapped to its Russian translation. No comments."""


def ask(package, items):
    numbered = "\n".join(f"{i}. {json.dumps(s, ensure_ascii=False)}" for i, s in enumerate(items, 1))
    where = CONTEXT.get(package, "a system app of the projector")
    body = {
        "model": MODEL,
        "temperature": 0.2,
        "max_tokens": 8000,
        "chat_template_kwargs": {"enable_thinking": False},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"App: {where}.\nStrings (JSON-quoted):\n{numbered}"},
        ],
    }
    req = urllib.request.Request(API, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        text = json.load(r)["choices"][0]["message"]["content"]
    text = re.sub(r"(?s)^.*?</think>", "", text).strip()
    m = re.search(r"\{.*\}", text, re.S)
    return json.loads(m.group(0)) if m else {}


def valid(src, ru):
    if not isinstance(ru, str) or not ru.strip():
        return False
    if sorted(PLACEHOLDER.findall(src)) != sorted(PLACEHOLDER.findall(ru)):
        return False
    return True


def main():
    todo_path, cache_path = sys.argv[1], sys.argv[2]
    size = int(sys.argv[3]) if len(sys.argv) > 3 else 60
    todo = json.load(open(todo_path, encoding="utf-8"))
    cache = json.load(open(cache_path, encoding="utf-8")) if os.path.exists(cache_path) else {}
    started = time.time()
    for package, strings in todo.items():
        done = cache.setdefault(package, {})
        left = [s for s in strings if s not in done]
        for i in range(0, len(left), size):
            batch = left[i:i + size]
            try:
                answer = ask(package, batch)
            except Exception as e:  # noqa: BLE001 - one bad batch must not lose the rest
                print(f"{package}: batch {i}: {e}", file=sys.stderr)
                continue
            good = 0
            for n, src in enumerate(batch, 1):
                ru = answer.get(str(n))
                if valid(src, ru):
                    done[src] = ru.strip()
                    good += 1
            with open(cache_path + ".tmp", "w", encoding="utf-8") as f:
                json.dump(cache, f, ensure_ascii=False, indent=1)
            os.replace(cache_path + ".tmp", cache_path)
            print(f"{package}: {i + len(batch)}/{len(left)} (+{good}) {time.time() - started:.0f}s", file=sys.stderr)


if __name__ == "__main__":
    main()
