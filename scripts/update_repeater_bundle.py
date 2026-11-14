#!/usr/bin/env python3
# Offset, an Android radio programming client.
# Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
"""
Refresh the repeater snapshot bundled with the app, from Hearham's public API.

    python3 scripts/update_repeater_bundle.py

Writes app/src/main/assets/repeaters/hearham.json.gz (Hearham's own JSON schema, trimmed to the
records and fields Offset uses) and hearham.meta.json. Output is deterministic for the same API
response: records sorted by id, compact JSON, gzip with a zero timestamp. The app re-applies its
own filters when it reads the file, so this script only needs to be loose and safe.

Standard library only. Run it before tagging a release and commit both files.
"""
import gzip
import io
import json
import os
import sys
import time
import urllib.request

URL = "https://hearham.com/api/repeaters/v1"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "repeaters")
FIELDS = ["id", "callsign", "latitude", "longitude", "city", "mode", "encode", "decode",
          "frequency", "offset", "operational", "description"]


def version():
    with open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("## ["):
                return line.split("[", 1)[1].split("]", 1)[0]
    return "0.0.0"


def keep(r):
    f = r.get("frequency") or 0
    in_band = 144e6 <= f <= 148e6 or 420e6 <= f <= 450e6 or 462.5e6 <= f <= 467.8e6
    tokens = (r.get("mode") or "").upper().replace("/", " ").replace(",", " ").replace("+", " ").split()
    analog = "FM" in tokens or "NFM" in tokens
    return in_band and analog and r.get("operational") != 0 and (r.get("callsign") or "").strip()


def main():
    ua = "Offset/%s (github.com/vyntric-hq/offset)" % version()
    req = urllib.request.Request(URL, headers={"User-Agent": ua, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.load(resp)
    if not isinstance(data, list) or len(data) < 1000:
        sys.exit("Unexpected Hearham response (%s records); not overwriting the bundle." % len(data))
    kept = sorted((r for r in data if keep(r)), key=lambda r: r["id"])
    trimmed = []
    for r in kept:
        t = {k: r.get(k) for k in FIELDS}
        if t["description"]:
            t["description"] = t["description"][:300]
        trimmed.append(t)
    payload = json.dumps(trimmed, separators=(",", ":"), ensure_ascii=False, sort_keys=True).encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
        gz.write(payload)
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "hearham.json.gz"), "wb") as f:
        f.write(buf.getvalue())
    meta = {
        "source": "Hearham",
        "url": URL,
        "attribution": "Repeater data from Hearham.com",
        "fetchedAt": int(time.time() * 1000),
        "records": len(trimmed),
    }
    with open(os.path.join(OUT_DIR, "hearham.meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, sort_keys=True)
        f.write("\n")
    print("Kept %d of %d records, %d bytes compressed." % (len(trimmed), len(data), len(buf.getvalue())))


if __name__ == "__main__":
    main()
