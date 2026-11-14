#!/usr/bin/env python3
# Offset, a UV-5R programming client.
# Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
"""
Refresh the secondary repeater listings bundled with the app: IRLP nodes and OpenStreetMap.

    python3 scripts/update_listings_bundle.py [--irlp nohtmlstatus.txt] [--osm overpass.json]

Writes app/src/main/assets/repeaters/listings.json.gz and listings.meta.json. The app merges
these into the default (Hearham) data: a listing that matches a Hearham repeater corroborates it,
one that does not is shown as a repeater of its own, credited to its directory.

Sources:
  IRLP           https://status.irlp.net/nohtmlstatus.txt.zip, the public node status list.
                 Nodes whose status is DOWN or that have not reported for 30 days are dropped.
                 Offset 0 means a simplex link node and is kept as simplex.
  OpenStreetMap  Overpass query for communication:amateur_radio:repeater=yes in North America.
                 ODbL: the bundled extract is a derived database, published under the ODbL in
                 this repository (see NOTICE), credited "(c) OpenStreetMap contributors".

Output is deterministic for the same inputs: records sorted, compact JSON, gzip with a zero
timestamp. Standard library only. The --irlp and --osm options read saved responses instead of
fetching. Run before tagging a release and commit both files.
"""
import argparse
import csv
import gzip
import io
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "repeaters")
IRLP_URL = "https://status.irlp.net/nohtmlstatus.txt.zip"
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
OSM_QUERY = (
    '[out:json][timeout:300];('
    'nwr["communication:amateur_radio:repeater"="yes"](17,-180,72,-50);'
    'nwr["amateur_radio:repeater"="yes"](17,-180,72,-50););out tags center;'
)
STALE_SECONDS = 30 * 86400
COUNTIES = os.path.join(ROOT, "app", "src", "main", "assets", "places", "counties.tsv")
STATE_MATCH_KM = 80
MIN_IRLP, MIN_OSM = 200, 20
CALL = re.compile(r"\b([AKNWV][A-Z]?[0-9][A-Z]{1,3}|[AKNW][0-9][A-Z]{1,3})\b")


def version():
    with open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("## ["):
                return line.split("[", 1)[1].split("]", 1)[0]
    return "0.0.0"


UA = "Offset/%s (github.com/vyntric-hq/offset)" % version()


def in_band(hz):
    return 144e6 <= hz <= 148e6 or 420e6 <= hz <= 450e6 or 462.5e6 <= hz <= 467.8e6


def load_county_points():
    """(state, lat, lon) for every US county's Census internal point, from the bundled gazetteer."""
    points = []
    with open(COUNTIES, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            state, _name, lat, lon = line.rstrip("\n").split("\t")
            points.append((state, float(lat), float(lon)))
    return points


def nearest_state(lat, lon, points):
    """US state of the nearest county point within STATE_MATCH_KM, else None (outside the US)."""
    best, best_km = None, STATE_MATCH_KM
    for state, plat, plon in points:
        dlat, dlon = math.radians(plat - lat), math.radians(plon - lon)
        a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat)) * math.cos(math.radians(plat)) * math.sin(dlon / 2) ** 2
        km = 2 * 6371.0088 * math.asin(math.sqrt(a))
        if km < best_km:
            best, best_km = state, km
    return best


def osm_hz(text, mhz_below):
    """A frequency or shift tag in Hz; values under mhz_below are taken as MHz (146.94, -0.6)."""
    v = float(text.split(";")[0].strip().replace(" ", ""))
    return round(v * 1e6) if abs(v) < mhz_below else round(v)


def fetch(url, data=None, timeout=330):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def irlp_records(text, now):
    rows = list(csv.reader(io.StringIO(text), delimiter="\t"))
    if len(rows) < 100 or not rows[0] or rows[0][0] != "Node":
        sys.exit("Unexpected IRLP status file; not overwriting the bundle.")
    out = []
    for r in rows[1:]:
        if len(r) < 14:
            continue
        node, call, city, region, country, status = (x.strip() for x in r[:6])
        try:
            lat, lon, seen = float(r[8]), float(r[9]), int(r[10])
            hz = round(float(r[11]) * 1e6)
            shift = round(float(r[12]) * 1e3)
        except ValueError:
            continue
        if status == "DOWN" or now - seen > STALE_SECONDS or not in_band(hz) or not in_band(hz + shift) or not call:
            continue
        out.append({
            "src": "IRLP", "id": node, "call": call.upper(), "out": hz, "inp": hz + shift,
            "up": r[13].strip() or None, "city": city or None,
            "state": region if country == "USA" and len(region) == 2 else None,
            "lat": round(lat, 5), "lon": round(lon, 5), "seen": seen * 1000,
            "url": "https://status.irlp.net/index.php?nodeid=%s" % node,
        })
    return out


def osm_records(doc, points):
    out = []
    for e in doc.get("elements", []):
        t = e.get("tags", {})
        hz_text = t.get("communication:amateur_radio:repeater:frequency_out") or t.get("communication:amateur_radio:frequency_out")
        mod = t.get("communication:amateur_radio:repeater:modulation", "F3E")
        if not hz_text or "F3E" not in mod.upper():
            continue
        try:
            hz = osm_hz(hz_text, 1e6)
        except ValueError:
            continue
        if not in_band(hz):
            continue
        call = t.get("communication:amateur_radio:callsign")
        if not call:
            m = CALL.search(" ".join(t.get(k, "") for k in ("name", "description", "ref")).upper())
            call = m.group(1) if m else None
        if not call:
            continue
        inp = None
        if t.get("communication:amateur_radio:repeater:frequency_in"):
            try:
                inp = osm_hz(t["communication:amateur_radio:repeater:frequency_in"], 1e6)
            except ValueError:
                pass
        elif t.get("communication:amateur_radio:repeater:shift"):
            try:
                inp = hz + osm_hz(t["communication:amateur_radio:repeater:shift"], 1e3)
            except ValueError:
                pass
        if inp is not None and not in_band(inp):
            continue
        lat = e.get("lat", e.get("center", {}).get("lat"))
        lon = e.get("lon", e.get("center", {}).get("lon"))
        state = t.get("addr:state")
        if not (state and len(state) == 2) and lat is not None and lon is not None:
            state = nearest_state(lat, lon, points)
        out.append({
            "src": "OSM", "id": "%s/%d" % (e["type"], e["id"]), "call": call.strip().upper(), "out": hz,
            "inp": inp, "up": t.get("communication:amateur_radio:repeater:ctcss"),
            "city": t.get("addr:city"), "state": state if state and len(state) == 2 else None,
            "lat": round(lat, 5) if lat is not None else None, "lon": round(lon, 5) if lon is not None else None,
            "seen": None, "url": "https://www.openstreetmap.org/%s/%d" % (e["type"], e["id"]),
        })
    return out


def overpass():
    body = urllib.parse.urlencode({"data": OSM_QUERY}).encode()
    for attempt in range(4):
        for url in OVERPASS:
            try:
                return json.loads(fetch(url, body))
            except Exception as e:  # busy mirrors answer 429/504 or HTML
                print("Overpass %s: %s" % (url, e), file=sys.stderr)
        time.sleep(30 * (attempt + 1))
    sys.exit("No Overpass server answered; try again later.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--irlp", help="saved nohtmlstatus.txt")
    ap.add_argument("--osm", help="saved Overpass JSON response")
    args = ap.parse_args()
    now = int(time.time())
    if args.irlp:
        irlp_text = open(args.irlp, encoding="latin-1").read()
        irlp_at = int(os.path.getmtime(args.irlp))
    else:
        with zipfile.ZipFile(io.BytesIO(fetch(IRLP_URL))) as z:
            irlp_text = z.read("nohtmlstatus.txt").decode("latin-1")
        irlp_at = now
    osm_doc = json.load(open(args.osm, encoding="utf-8")) if args.osm else overpass()
    osm_at = int(os.path.getmtime(args.osm)) if args.osm else now

    irlp = irlp_records(irlp_text, irlp_at)
    osm = osm_records(osm_doc, load_county_points())
    if len(irlp) < MIN_IRLP or len(osm) < MIN_OSM:
        sys.exit("Only %d IRLP and %d OpenStreetMap records; a source looks broken, not overwriting the bundle." % (len(irlp), len(osm)))
    records = sorted(irlp + osm, key=lambda r: (r["src"], r["id"]))
    records = [{k: v for k, v in r.items() if v is not None} for r in records]
    payload = json.dumps(records, separators=(",", ":"), ensure_ascii=False, sort_keys=True).encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
        gz.write(payload)
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "listings.json.gz"), "wb") as f:
        f.write(buf.getvalue())
    meta = {
        "IRLP": {"url": IRLP_URL, "fetchedAt": irlp_at * 1000, "records": len(irlp),
                 "attribution": "Node data from the IRLP status page"},
        "OSM": {"url": "https://overpass-api.de", "fetchedAt": osm_at * 1000, "records": len(osm),
                "attribution": "(c) OpenStreetMap contributors, ODbL 1.0"},
    }
    with open(os.path.join(OUT_DIR, "listings.meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, sort_keys=True)
        f.write("\n")
    print("IRLP %d, OpenStreetMap %d, %d bytes compressed." % (len(irlp), len(osm), len(buf.getvalue())))


if __name__ == "__main__":
    main()
