#!/usr/bin/env python3
# Offset, a UV-5R programming client.
# Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
"""
Refresh the bundled US county list from the Census Bureau Gazetteer (public domain).

    python3 scripts/update_counties.py [YEAR]

Writes app/src/main/assets/places/counties.tsv: state, county name, latitude, longitude of the
county's internal point. Deterministic: sorted by state then name. Standard library only.
"""
import csv
import io
import os
import sys
import urllib.request
import zipfile

YEAR = sys.argv[1] if len(sys.argv) > 1 else "2025"
URL = ("https://www2.census.gov/geo/docs/maps-data/data/gazetteer/%s_Gazetteer/%s_Gaz_counties_national.zip"
       % (YEAR, YEAR))
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "places", "counties.tsv")


def main():
    req = urllib.request.Request(URL, headers={"User-Agent": "Offset (github.com/vyntric-hq/offset)"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        archive = zipfile.ZipFile(io.BytesIO(resp.read()))
    name = next(n for n in archive.namelist() if n.endswith(".txt"))
    text = archive.read(name).decode("utf-8")
    rows = []
    for r in csv.DictReader(io.StringIO(text), delimiter="|"):
        r = {k.strip(): v.strip() for k, v in r.items()}
        rows.append((r["USPS"], r["NAME"], "%.5f" % float(r["INTPTLAT"]), "%.5f" % float(r["INTPTLONG"])))
    if len(rows) < 3000:
        sys.exit("Unexpected gazetteer (%d rows); not overwriting." % len(rows))
    rows.sort()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("# US counties and equivalents, Census Bureau %s Gazetteer (public domain): %s\n" % (YEAR, URL))
        f.write("# state\tname\tlatitude\tlongitude (internal point)\n")
        for row in rows:
            f.write("\t".join(row) + "\n")
    print("Wrote %d counties to %s" % (len(rows), OUT))


if __name__ == "__main__":
    main()
