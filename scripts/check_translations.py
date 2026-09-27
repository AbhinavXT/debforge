#!/usr/bin/env python3
"""
Checks every app/src/main/res/values-*/strings.xml against the English
values/strings.xml, catching what would break the Android build or crash at
runtime:

  - XML that doesn't parse
  - missing or extra string/plural names (app_name is not translatable)
  - format placeholders (%1$s, %2$d, ...) that differ from English
  - unescaped apostrophes, or a value starting with an unescaped @ or ?
  - plurals missing a quantity the language needs (CLDR, as Android lint uses)

Usage: scripts/check_translations.py [values-xx ...]   (default: all)
Exit code 1 if anything is wrong.
"""
import re, sys, xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"

# Plural categories Android (CLDR) requires per language.
PLURALS = {
    "hi": {"one", "other"}, "bn": {"one", "other"}, "ta": {"one", "other"},
    "te": {"one", "other"}, "mr": {"one", "other"}, "de": {"one", "other"},
    "tr": {"one", "other"}, "es": {"one", "many", "other"}, "pt": {"one", "many", "other"},
    "fr": {"one", "many", "other"}, "ru": {"one", "few", "many", "other"},
    "in": {"other"}, "id": {"other"}, "zh": {"other"},
}
PH = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc%]")

def inner(e):
    return (e.text or "") + "".join(ET.tostring(c, encoding="unicode") for c in e)

def placeholders(text):
    return sorted(m.group(0) for m in PH.finditer(text) if m.group(0) != "%%")

def load(path):
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for e in root:
        if e.tag == "string":
            if e.get("translatable") == "false":
                continue
            strings[e.get("name")] = inner(e)
        elif e.tag == "plurals":
            plurals[e.get("name")] = {i.get("quantity"): inner(i) for i in e}
    return strings, plurals

def escaping(name, text):
    errs = []
    if re.search(r"(?<!\\)'", text):
        errs.append(f"{name}: unescaped apostrophe (use \\')")
    if text[:1] in "@?":
        errs.append(f"{name}: starts with {text[0]} (escape it as \\{text[0]})")
    return errs

def check(folder, en_s, en_p):
    path = RES / folder / "strings.xml"
    lang = folder.removeprefix("values-").split("-")[0]
    errs = []
    try:
        s, p = load(path)
    except ET.ParseError as e:
        return [f"XML error: {e}"]
    for n in sorted(set(en_s) - set(s)): errs.append(f"missing string {n}")
    for n in sorted(set(s) - set(en_s)): errs.append(f"extra string {n}")
    for n in sorted(set(en_p) - set(p)): errs.append(f"missing plurals {n}")
    for n in sorted(set(p) - set(en_p)): errs.append(f"extra plurals {n}")
    for n, v in s.items():
        if n in en_s:
            if placeholders(v) != placeholders(en_s[n]):
                errs.append(f"{n}: placeholders {placeholders(v)} != English {placeholders(en_s[n])}")
            errs += escaping(n, v)
            if not v.strip():
                errs.append(f"{n}: empty")
    need = PLURALS.get(lang, {"one", "other"})
    for n, items in p.items():
        if n not in en_p:
            continue
        missing = need - set(items)
        if missing:
            errs.append(f"plurals {n}: missing quantities {sorted(missing)} (needs {sorted(need)})")
        # 'other' must carry every placeholder English uses; others may omit the count.
        en_all = sorted(set(sum((placeholders(t) for t in en_p[n].values()), [])))
        if sorted(set(placeholders(items.get("other", "")))) != en_all:
            errs.append(f"plurals {n} other: placeholders {placeholders(items.get('other',''))} != English {en_all}")
        for q, t in items.items():
            if not set(placeholders(t)) <= set(en_all):
                errs.append(f"plurals {n} {q}: unknown placeholder in {placeholders(t)}")
            errs += escaping(f"{n}[{q}]", t)
    return errs

def main():
    en_s, en_p = load(RES / "values/strings.xml")
    folders = sys.argv[1:] or sorted(d.name for d in RES.iterdir()
                                     if d.name.startswith("values-") and (d / "strings.xml").exists())
    bad = False
    for f in folders:
        errs = check(f, en_s, en_p)
        print(f"{f}: {'OK' if not errs else str(len(errs)) + ' problem(s)'} "
              f"({len(en_s)} strings, {len(en_p)} plurals)")
        for e in errs:
            print("   ", e)
        bad |= bool(errs)
    sys.exit(1 if bad else 0)

if __name__ == "__main__":
    main()
