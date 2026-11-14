# Assumptions and decisions

Decisions made while building Offset 0.1.0 without being able to ask. Each one says what was
assumed and why. Items that need a physical radio to confirm are marked **(hardware)**.

## Radios and protocol

1. **UV-5R 8W and UV-5RTP use the BF-F8HP profile.** CHIRP has no separate class for either. Both
   are tri-power radios that CHIRP users program as the BF-F8HP (its alias list includes similar
   tri-power UV-5R variants). Their firmware strings are expected to match the BF-F8HP base
   types (N5R-3, BFT and so on). **(hardware)**
2. **"N5RV" firmware is treated as a UV-5R.** It is in both CHIRP base type lists; CHIRP's
   registration order picks the UV-5R. A tri-power N5RV radio would show two power levels.
   **(hardware)**
3. **Unrecognised firmware is treated as a UV-5R** and flagged on the Radio tab, rather than
   refused, because the channel and name layout is the same across the family.
4. **Name length is 7 for every supported model.** CHIRP uses 7 for the whole uv5r.py family. The
   "name length differences" in CHIRP concern other drivers (UV-5R Mini, UV-17), which are out
   of scope. The length lives in `RadioModel.nameLength` so a future profile can differ.
5. **Model detection is automatic** from the firmware string in the aux block, instead of a model
   picker as in CHIRP. The firmware string is what CHIRP itself uses to match image files.
6. **Only channel and name memory is ever written.** CHIRP also uploads settings ranges by
   default; Offset deliberately does not (safety rule 3). Radio settings must be changed on the
   radio or with CHIRP.
7. **Only changed 16 byte blocks are written.** The diff is against a read taken seconds earlier
   in the same write, so it is exact; it also shortens the time the radio spends in clone mode.
8. **"Ident matches" means byte for byte equality of the 8 byte ident**, plus the same model and
   firmware string. The ident encodes model and band limits, not a serial number, so two radios
   of the same model and firmware share one. To catch a second unit connected between the read
   and the write, `upload` re-reads the channel and name memory and refuses if it changed. Two
   units with identical channel memory are indistinguishable, and writing either gives the same
   result.
9. **"First time a radio is read"** means no factory image with this ident exists in the library.
   Deleting the factory image makes the next read save a new one.
10. **The firmware probe and ACK handling follow CHIRP, with baoclone's tolerance of an early or
    late ACK.** Real radios vary in timing. **(hardware)**
11. **A 1.5 s gap between clone sessions** (read, write, verify) is assumed enough for the radio to
    accept a new handshake without being power cycled. CHIRP users routinely download and upload
    back to back. **(hardware)**
12. **DTR and RTS are asserted** on open, matching pyserial's default behaviour used by CHIRP.
    Some cable chips power the level shifter from these lines. **(hardware)**
13. **The blacklist probe for the BTECH UV-5X3 and Radioddity UV-5G is not ported.** Those radios
    fail identification with the generic "did not answer" message instead of a model specific
    one.
14. **220 MHz (vhf_220) radios are not supported for editing.** Channels outside 2m and 70cm
    ranges of the profile fail validation. Reading and backing them up still works.
15. **Staging keeps channel numbers.** Each staged channel has a fixed radio slot. Reading a radio
    stages every channel at its own number and keeps gaps (channels 1 and 128 stay 1 and 128), so a
    read and write back changes nothing. New channels take the lowest free numbers, removing one
    leaves a gap, and dragging reorders channels across the numbers already in use. "Close gaps"
    renumbers to 1..n on request. (The first build packed channels; review flagged it as a
    silent renumbering of the user's memories, so it was changed.)
16. **New channels default to High power, wide, scan add on, no BCL, no PTT-ID.** Channels from a
    repeater listing use tone squelch only when the listing's downlink tone equals its uplink
    tone; otherwise they transmit the tone and receive carrier squelch, which always works.

## Files and formats

17. **The .img metadata omits `chirp_version`.** CHIRP only uses it to warn about images from a
    newer CHIRP; leaving it out avoids pretending to be a CHIRP version. Verified to load in
    CHIRP.
18. **CSV power is written in watts** (UV-5R High 4 W, Low 1 W; BF-F8HP 8, 4, 1 W) because CHIRP's
    CSV importer parses watts, not labels. Import accepts both.
19. **CSV import into an image** writes rows to their Location slots and leaves other slots alone.

## Repeater data

20. **Hearham state comes from the free text city field.** Hearham has no state or county
    field. Most US entries end in a state name or abbreviation; entries that do not are found by
    "near me" but not by "by state". County search is unavailable for Hearham.
21. **Hearham "analog FM" includes mixed modes** such as YSF/FM and DMR/FM, since the repeater
    also passes analog FM. Entries with `operational` 0 are dropped.
22. **Hearham tone fields:** `encode` is the uplink tone (what the radio sends), `decode` the
    downlink tone, as in Repeater-START. `CC<n>` (DMR colour code), `0`, `CSQ` and empty mean no
    tone.
23. **RepeaterBook county filtering happens on the phone** against the cached state result,
    instead of sending a `county` parameter. This satisfies "query by state and optional county"
    with fewer requests, which suits RepeaterBook's rate limits.
24. **RepeaterBook "near me" picks states by bounding box** (`UsState`) and queries at most four.
    No reverse geocoder is available without Play Services.
25. **RepeaterBook test connection** queries the District of Columbia, the smallest state result.
26. **RepeaterBook field names** follow the export format as published in RepeaterBook's API docs
    and used by CHIRP (`Frequency`, `Input Freq`, `PL`, `TSQ`, `Nearest City`, `County`, `State`,
    `Lat`, `Long`, `Callsign`, `Operational Status`, `FM Analog`, `Last Update`). Offset has not
    yet been approved by RepeaterBook, so this was not tested against the live API.
27. **CSV files without coordinates list every in-band entry** for "near me" and "by state",
    since an empty result would be useless.
28. **Distances are great circle** from the phone's fix; bearings are initial true bearings.

## App

29. **Manual dependency injection** (`AppContainer`) instead of Koin: one fewer dependency.
30. **Staging is stored as one JSON document in Room**, written atomically on every change.
31. **Image lists are bounded at 1,000 rows.** Room stores raw image bytes (6.5 KB each).
32. **The simulated radio's factory image** is baoclone's UV-5R factory dump (BFB291, BSD
    licensed). Its state persists in app storage so it behaves like a real radio across restarts.
33. **Settings use plain SharedPreferences**; only the RepeaterBook token and email use
    EncryptedSharedPreferences, as requested. `androidx.security:security-crypto` 1.1.0 is
    deprecated upstream but stable, FOSS and available; there is no maintained drop-in
    replacement.
34. **Icons are Material Icons** (`material-icons-extended`, the Apache-2.0 predecessor of
    Material Symbols), rounded style. R8 strips unused icons from release builds.
35. **No blur behind the tab and title bars.** Compose has no cheap backdrop blur before API 31,
    so the bars are 97% opaque instead.
36. **Toggles use the accent colour** when on, to keep a single accent.
37. **`allowBackup` is off** and data extraction rules exclude everything, so cached repeater
    data, credentials and images never leave the phone through Android backup.
38. **License is GPL-3.0-or-later.** CHIRP's uv5r.py is GPL-2.0-or-later, which can be combined
    into a GPL-3.0-or-later work.

## GMRS, map and search (added after the first build, per the user's choices)

46. **GMRS channels transmit.** The user chose "allow transmit with a warning": GMRS repeaters and
    FRS/GMRS presets are programmed with normal transmit settings, after a one time warning that
    a GMRS license and a Part 95 certified radio are required and that UV-5R radios are not
    certified. The acknowledgement is remembered; the write confirmation sheet counts GMRS and FRS
    channels. FRS 8 to 14 are preset low power and narrow.
47. **GMRS data comes from RepeaterBook (`stype=gmrs`) and imported CSVs.** Hearham's list holds
    only a handful of GMRS entries, so the Repeaters screen says so when GMRS is selected with
    Hearham.
48. **A frequency decides GMRS.** A repeater whose output is a GMRS or FRS channel frequency (462 and
    467 MHz channel plan) is GMRS regardless of source; RepeaterBook GMRS results are GMRS by query.
49. **"Mixed mode"** means FM plus any digital mode the source reports (Hearham mode strings such as
    YSF/FM; RepeaterBook's DMR, D-Star, System Fusion, APCO P-25, NXDN, M17, Tetra flags). It is
    shown, and hidden on request; mixed-mode repeaters still work in FM.
50. **"Closest" means the 100 nearest repeaters** with no distance limit. 100 leaves room under the
    128 channel limit. RepeaterBook "closest" looks at states within 160 km.
51. **Text search** matches every word against callsign, city, county, state (abbreviation or
    name) or the start of the output frequency, over data already on the phone, and returns at
    most 500 rows.
52. **The map is online** (user's choice): MapLibre with OpenFreeMap vector tiles, light and dark
    styles. Viewed areas are cached by MapLibre. The map uses its own OkHttp client with Offset's
    User-Agent.
53. **Pin colours are categorical** (2m blue, 70cm teal, GMRS orange), an exception to the single
    accent rule because a map needs distinguishable categories. Clusters dissolve above zoom 8.
54. **The map renders with OpenGL** (`android-sdk-opengl`), not MapLibre's default Vulkan
    artifact, for compatibility with older GPUs and emulators.
55. **Release APKs are ARM only** (arm64-v8a, armeabi-v7a). MapLibre's x86 libraries would double the
    APK for no real phones. Debug builds keep every ABI for emulators.
56. **Native libraries are packaged unstripped**, exactly as MapLibre publishes them, so the APK does
    not depend on whether an NDK is installed on the build machine.
57. **Bluetooth pairing** from Offline Repeater Pro stays out of scope (non-goal: no Bluetooth radios).
58. **Hearham data is bundled in the APK** (user request: bundled records are key). Basis and the
    recommendation to confirm with Hearham are in docs/DATA_SOURCES.md. RepeaterBook data is never
    bundled (its policy requires written permission). The snapshot is refreshed by running
    `scripts/update_repeater_bundle.py` before a release and committing the result; builds never
    fetch it, which keeps them reproducible.
59. **Offline first.** With bundled data always present, a Hearham search never waits for the
    network: stale data is answered and refreshed quietly once per session. This replaces the
    earlier "refetch after 7 days before answering" behaviour.
60. **AAPT unpacks `.gz` assets** and drops the suffix, so the APK holds `assets/repeaters/hearham.json`
    (deflated by the APK zip, about 0.7 MB). The repository keeps the smaller `.gz`; the app opens
    either name.

61. **Search centre sources** (from the Offline Repeater Pro screenshots): GPS, Maidenhead grid
    (2, 4, 6 or 8 characters; the centre of the square is used), typed decimal coordinates (signs
    or N/S/E/W) and a county's Census internal point. The centre's 6 character grid square is shown.
62. **Live tracking** re-locates every 30 seconds while on and re-searches after moving more than
    500 m. It runs while the Repeaters screen's state is alive and stops when switched off or when
    another location source is chosen; it never asks for background location.

63. **Secondary directories are bundled to corroborate Hearham** (user request: more records, with
    source and verification flags). Only directories whose data may be redistributed are used:
    IRLP's public node status list and OpenStreetMap (ODbL). RepeaterBook, the ARRL directory,
    RFinder and myGMRS forbid redistribution and are never scraped. openrepeater.org publishes CC0
    data but offers bulk download only to its admins; a request for an export is pending.
64. **Matching rule** (`DirectoryMerge`): same output frequency within 2.5 kHz and either the same
    callsign within 100 km (or with no coordinates to compare) or any callsign within 25 km, and
    matching input frequencies when both are known.
    Frequency coordination keeps two machines on one output far apart, and directories often list
    a club call where another lists the trustee's. The default provider's values win; a secondary
    listing only fills missing coordinates. Unmatched listings become repeaters of their own,
    credited to their directory.
65. **What "verified" means.** Offset never claims a repeater is on the air. It shows how many
    independent directories list it, flags directories that disagree on the access tone, and, for
    IRLP nodes, when the node itself last reported to the IRLP network. A listing copied from
    another directory (Hearham's imported IRLP nodes) is shown but not counted as independent. IRLP nodes silent for 30
    days or marked DOWN are left out of the bundle.
66. **IRLP simplex link nodes** (offset 0) are kept as simplex entries: they are reachable on that
    frequency, and they corroborate Hearham entries on the same frequency and site.
67. **IRLP has no stated licence** for its status list. It is published for operators to find
    nodes, Offset uses only the fields needed to program a radio, and credits IRLP wherever the
    data appears. Remove `"src":"IRLP"` records if the IRLP project objects.

## More radios (0.3.0)

68. **The radio type is chosen, not probed.** The three families use different speeds and
    handshakes; sending one family's handshake to another radio was judged riskier than asking.
    Writes always use the type of the radio that was read.
69. **Validation covers only changed channels.** A channel already on the radio in the same slot is
    written back byte for byte, so radio-specific channels (airband, unusual steps) never block a
    write. Restores still check frequencies and tones of analog channels.
70. **DM-32UV identity is the model ID "DP570UV".** The image bytes do not hold the firmware, so
    firmware shows only for an image read in this session; CHIRP's `dm32uv_firmware` metadata is
    written on export but not read back.
71. **DM-32UV channel mapping:** scan add always reads as on (scan membership lives in scan lists);
    busy lockout maps to TX admit (anything but Allow TX reads as on; turning it on sets Channel
    Idle); PTT ID maps to the ptt_id field; DMR channels read with frequency, duplex, name and power
    only and are never re-encoded. Invalid BCD values read as no tone, empty or TX off.
72. **DM-32UV edits apply as CHIRP's set_memory in ascending channel order**, so results match
    CHIRP byte for byte (cross checked headless on hundreds of edits). New channels start from
    CHIRP's default record with a 5 kHz step; the channel count rises and falls as CHIRP's does.
73. **DM-32UV new channels join a zone named "Offset"** (then "Offset 2" and so on at 64 members);
    with 250 zones and no room, a channel joins no zone rather than failing. Offset writes channel,
    TX contact, scan list and zone pages only, never settings or DMR lists, and plans every page
    before writing so "no free page" is refused before anything is written. Restoring an image empties writable pages the radio gained since that image was taken, and verification compares zone and scan list membership as well as channel records.
74. **DM-32UV password protected radios are refused**, as CHIRP does, and the firmware is not
    checked against CHIRP's tested list (CHIRP only warns).
75. **RT-950 Pro image is the 33,152 byte decrypted clone dump** in Nathan Barguss's format, with
    960 channels (not the 990 one source mentions). Identity is the constant "RT-950 Pro"; the 16
    bytes after `F` are ignored, and firmware is unknown.
76. **RT-950 Pro sessions either read or write, never both.** An upload writes all 240 channel
    blocks in order, the flow tested on hardware, so the only check that the same unit is still
    connected is the full read made moments before. **(hardware)**
77. **RT-950 Pro channel details guessed from documentation:** byte 13 values 0 to 3 are PTT ID
    Off, BOT, EOT, Both; byte 12 (signalling group) is not modeled; TX inhibit clears flag bit 1
    and keeps the stored TX frequency; the DCS table has 210 entries (105 codes, normal then
    inverted). **(hardware)**
78. **RT-950 Pro bands are 18 to 64 MHz and 70 to 580 MHz** (Nathan Barguss's valid_bands), power
    10, 5 and 1 W. Offset does not block transmit frequencies the radio itself may refuse. **(hardware)**
79. **RT-950 Pro connection:** a CH340 USB serial cable is reported; whether it uses the 2-pin
    jack or the USB-C port is not confirmed, and the app says so.
80. **Staging is not cleared when the radio type changes.** Channels that do not fit the new radio
    (too many slots, long names, DMR) are flagged and block the write until fixed.

81. **Factory images are kept per identity.** For the DM-32UV and RT-950 Pro the identity is the
    model (as the UV-5R ident is), so only the first radio of each model read on a phone gets an
    automatic factory image; every write still saves its own backup.

## Build and release

39. **AGP 8.13.2, Kotlin 2.2.21 and Gradle 8.14.3** rather than the newest AGP 9 and Kotlin 2.4,
    for a proven, stable combination with compileSdk 36. targetSdk is 35 as requested.
40. **compileSdk 36** because the AndroidX versions in use require it; it does not change runtime
    behaviour (targetSdk stays 35).
41. **The release key fingerprint is a committed file** (`release-cert.sha256`) so that rebuilds
    are byte identical. The key was generated on 2026-11-13; its fingerprint is published there and
    in the README.
42. **GitHub Actions are pinned by major version** (`actions/checkout@v4` and so on), not by
    commit SHA, because the SHAs could not be verified offline. Pinning to SHAs is a good follow
    up.
43. **`github.token`** (the workflow's automatic token, not a repository secret) creates the
    release, so the workflows reference only the four signing secrets.
44. **CI runs on every push and pull request**; it does not need any secret.
45. **The repository is public** at github.com/vyntric-hq/offset, because GitHub artifact
    attestations need a public repository (or Enterprise Cloud). The release keystore and its
    passwords live only on the maintainer's machine (`~/.config/offset-release/`, owner-only) and in
    GitHub Actions secrets.
