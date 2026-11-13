# Changelog

All notable changes to Offset are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/). The release workflow publishes the section whose
heading matches the tag as the GitHub Release notes, so every tag needs a section here.

## [0.1.0] - 2026-11-13

### Added
- UV-5R clone protocol in a pure Kotlin module, ported from CHIRP and cross checked against
  baoclone: identification (BFB291 and later, original firmware, BF-F8HP A58 magic), full image
  read with the firmware probe and dropped byte workaround, block write, and read back verify.
- Model profiles for the UV-5R (and its CHIRP aliases) and the tri-power BF-F8HP family, which
  also covers the UV-5R 8W and UV-5RTP.
- Write safety: full read before every write, ident and firmware match checks, channel and name
  memory only, automatic timestamped backup, read back verification with per channel mismatch
  report, confirmation sheet, and clear reporting of interrupted writes.
- Simulated radio that speaks the clone protocol in memory, with a debug-only switch in
  Settings.
- Repeater search near the phone's GPS position or by state, from Hearham (default, 7 day
  cache), RepeaterBook (your own token, 24 hour per state cache) or an imported CHIRP CSV.
- Staging list of up to 128 channels with drag to reorder, swipe to remove and inline editing.
- Image library: factory read on first contact, backups, edit channels, duplicate, rename,
  notes, delete, restore to radio, CHIRP .img and CSV export and import.
- Amateur and GMRS repeater search: GMRS from RepeaterBook (`stype=gmrs`) and imported CSVs, a
  service filter, a hide mixed-mode filter, "closest 100" as well as radius search, and text
  search by callsign, city, county, state or frequency.
- A snapshot of about 12,500 Hearham repeaters ships with the app: search and the map work offline
  from the first launch, with a quiet weekly refresh when online.
- Search from GPS (with optional live tracking), a Maidenhead grid square, typed coordinates or
  a county (Census Gazetteer bundled), with the search centre's grid square shown.
- "N amateur + M GMRS, updated <date>" summary of the data on the phone.
- Repeater map (MapLibre with OpenFreeMap tiles): results or everything cached, clustered band
  coloured pins with callsign labels, your position, legend, recenter, light and dark styles, and
  tap for details.
- Simplex presets: national calling, 2m and 70cm simplex, NOAA weather (receive only), FRS/GMRS 1
  to 22 and GMRS repeater pairs, with a one time GMRS licensing warning.
- Light and dark themes, Inter typeface, haptics, accessibility labels and actions.
- Reproducible release builds, signed releases with SHA256SUMS and build provenance attestation.
