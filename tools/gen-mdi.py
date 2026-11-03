#!/usr/bin/env python3
"""Builds core/resources/web/mdi-all.json from the Material Design Icons package (Pictogrammers, Apache-2.0).

Usage:  npm pack @mdi/svg@7.4.47 && mkdir mdi && tar xzf mdi-svg-7.4.47.tgz -C mdi
        python3 tools/gen-mdi.py mdi/package core/resources/web/mdi-all.json

Output: {"version": "...", "icons": [[name, svgPath, "search aliases", homeAutomation(0/1)], ...]}
The icon picker loads it on demand; icons that the user chooses are stored with their path in icons.json,
so nothing else needs this file to draw them.
"""
import json, re, sys

src, out = sys.argv[1], sys.argv[2]
meta = json.load(open(f"{src}/meta.json"))
version = json.load(open(f"{src}/package.json"))["version"]
icons = []
for i in sorted(meta, key=lambda x: x["name"]):
    if i.get("deprecated"):
        continue
    svg = open(f"{src}/svg/{i['name']}.svg").read()
    d = re.search(r' d="([^"]+)"', svg).group(1)
    icons.append([i["name"], d, " ".join(i.get("aliases", [])), 1 if "Home Automation" in i.get("tags", []) else 0])
json.dump({"version": version, "icons": icons}, open(out, "w"), separators=(",", ":"), ensure_ascii=False)
print(len(icons), "icons ->", out)
