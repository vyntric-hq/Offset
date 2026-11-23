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
11. **A UV-5R write is one clone session** (read, backup, write, read back). The first build used a
    session per step with a 1.5 s gap, assuming the radio would accept a new handshake by then. A
    real UV-5R (firmware HN5RV011, FTDI cable) did not: it stays in clone mode after a session,
    then restarts, and the write session got no answer. Reading back within the same session is
    assumed to return what the radio stored, as a later read would. The DM-32UV and RT-950 Pro
    still use a session per step, since their protocols end each session with an explicit
    command. **(hardware)**
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
    `Lat`, `Long`, `Callsign`, `Operational Status`, `FM Analog`, `Last Update`). RepeaterBook
    approved Offset on 2026-11-20 (item 115); this is not yet tested against the live API.
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
79. **RT-950 Pro connection:** a CH340 USB serial cable. The app no longer speculates about which
    plug it uses.
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

## Reach (0.4.0)

82. **HF is out of scope.** Reach models line-of-sight bands (6 m, 2 m, 1.25 m, 70 cm, GMRS).
    The user agreed that HF propagation belongs in a separate app, if anywhere. On 6 m the screen
    says that sporadic-E openings are not modelled.
83. **Longley-Rice point-to-point at median conditions.** 50% time, location and situation,
    mobile variability, vertical polarization, average ground (permittivity 15, conductivity
    0.005 S/m), continental temperate climate, surface refractivity 301 N-units. These suit most
    of the continental US; coastal and desert areas differ by a few dB.
84. **Verdict bands.** Likely at 10 dB or more over the receiver's 12 dB SINAD sensitivity,
    marginal from 0 to 10 dB, unlikely below 0. The 10 dB allows for fading the median ignores.
85. **Far station defaults.** Handheld 1.5 m, 5 W, 0 dBi; mobile 1.8 m, 50 W, 2.15 dBi, 1 dB
    loss; base 10 m, 50 W, 6 dBi, 2 dB loss; repeater 30 m, 50 W, 6 dBi, 4 dB (feedline and
    duplexer), -120 dBm. Receivers -121 dBm (0.2 microvolts, the usual spec sheet figure). The
    directories list no repeater power or height, so these are shown on every link card and the
    repeater ones can be changed for all repeaters.
86. **Catalog figures.** Radio powers are the makers' high power figures (UV-5R 4 W as in
    Offset's driver; BF-F8HP 8 W; DM-32UV 5 W; RT-950 Pro 10 W). Antenna gains are typical
    published figures converted to dBi (dBd + 2.15); the stock UV-5R rubber duck is taken as
    -4 dBi on 2 m and -2 dBi on 70 cm, reflecting published measurements that stock handheld
    antennas are lossy. Collinear figures are the makers' and are optimistic. Feedline loss per
    100 ft at 144 and 440 MHz is from manufacturer tables (RG-58 6.1/11.7, RG-8X 4.7/8.6, RG-213
    2.8/5.1, LMR-400 1.5/2.7 dB) and scaled as f^k between and beyond them.
    Named antennas (added 2026-11-18): ZBM2 Industries states about 3.5 dBi for every Foul
    Weather Whip and the Tough Whip. A quarter wave on a handheld cannot beat a half-wave
    dipole, so the whips are modelled at 0 dBi on 70 cm, GMRS and the 1.25 m side -1 dBi, the
    loaded 2 m side -2 dBi, the Stubby -6/-3 dBi, and the Tough Whip NMO as a quarter wave on a
    roof (2.15 dBi). Practical Comms publishes no gain for its jungle antennas, quarter-wave
    ground planes with drooping radials, taken as 2 dBi. N9TAX Labs publishes no gain for its
    slim jims; a review measured just over 2 dBi on a 2 m unit, so all are 2.15 dBi. N9TAX's
    store was closed when these were added, so its lineup comes from the archived catalog of
    2026-10-22; its single-band slim jims are built to order and only 2 m and 70 cm are listed.
    The integrated 10 ft and 16 ft cable versions do not say which coax they use; picking one
    fills in that length of RG-58 as the feedline, which can be changed.
87. **Beam pattern.** A Yagi's horizontal pattern is the usual parabolic main lobe (12 dB at
    one beamwidth off axis) floored at a 15 dB front to back ratio, with the beamwidth from its
    gain assuming a round main lobe. Elevation patterns are not modelled.
88. **Coverage area.** Out to 1.8 times the combined radio horizon plus 15 km, between 30 and
    150 km; 360 radials, at most 320 samples on each. Buildings, trees and clutter are not
    modelled, and the link card says so.
89. **One far station type for the map shading.** The shaded map assumes every far station is
    of the type chosen ("To handhelds", "To repeaters" and so on). Pins use each target's own
    type: repeaters the repeater assumptions, places their saved type.
90. **Reach uses the selected repeater provider, RepeaterBook included,** as the user decided
    on 2026-11-18. The RepeaterBook API application sent the same day (request 302) described
    Offset before Reach existed; tell RepeaterBook about Reach if they ask.
91. **No address search.** Places are entered as GPS, coordinates, grid squares, map links or a
    long press, so no location is ever sent to a geocoding service.
92. **Places beyond 300 km are modelled over smooth ground.** No VHF/UHF path that long closes
    over land, and fetching terrain for it would mean hundreds of tiles; those places show as
    unlikely without downloading anything.
93. **Reach shows repeaters on 2 m, 70 cm and GMRS only,** because those are the bands the
    repeater directories Offset uses carry. On 6 m and 1.25 m the map still models coverage and
    places, and says why no repeaters appear.

## RT-950 Pro on nicFW950 (0.5.0)

94. **nicFW950 is its own radio type**, not detected automatically. Stock firmware waits for
    `PROGRAMBT9000U` at 115200 baud and nicFW for `AA` packets at 38400, so probing one protocol
    would send the other's bytes to the radio. The user picks "RT-950 Pro (nicFW950)".
95. **Sources are nicsure's own published material,** read for facts only (none of it carries a
    licence, and no code is copied): the nicFW880/950 wiki (serial protocol, `channelInfo`,
    storage map), the RT950Pro BTLE bridge's channel manager, and the NicFW950 RMS desktop app,
    whose compiled code settles where the wiki and bridge differ. The RMS puts channel N at
    `0x50200 + (N + 3) * 62` (N from 1), checks the active magic `0xC950` (the wiki's `0xABC6` is
    nicFW880's), sums the address and data bytes for the checksum (the wiki says data only), sends
    `AA 30` reads as 7 bytes, and after writing sends `AA 48` (commit) then `AA 50` (reboot).
    **(hardware)**
96. **Slots 0 to 3 are VFOs and never written;** a read must find the `0xC950` magic in some slot,
    or the radio is refused as another nicFW (an RT-880 answers the same protocol). **(hardware)**
97. **The image is the channel section only** (0xF400 bytes). Settings, band plan, calibration
    and groups' names are not read, so a backup restores channels only.
98. **Writes send only the 128 byte blocks that changed,** each read again first and compared
    with the read made moments before (the protocol has no ident, so this is rule 2), and read
    once more just before it is written: a channel saved on the keypad in between stops the
    write before the commit, and the VFO or unused bytes sharing the first and last blocks are
    sent back as the radio holds them at that moment. The
    radio stays at 38400 baud; the RMS's faster speeds are optional.
99. **Power:** (0.6.0: all six transmit levels are read and written, see 109.) An unchanged
    channel keeps its byte. Transmit off is "N/T"
    (0) with the transmit frequency kept. Watts shown are the stock firmware's 10/5/1 W because
    nicFW's levels depend on its power calibration. **(hardware)**
100. **New channels start with the mode the channel says** (FM unless set; 0.5.0 used "Auto"),
    with no groups, clarifier or scrambler. nicFW has no per channel scan flag (it scans groups), so
    the channel editor hides "Scan add" for this radio and every channel reads as scanned.
101. **Channels keep their numbers on nicFW950** (as on the DM-32UV): reordering is off, and new
    channels go into free slots, because each record carries groups and other settings Offset
    keeps but does not model, which would otherwise stay behind in the old slot.
102. **A deleted channel loses its frequencies and magic and keeps its other bytes,** as nicsure's
    bridge does. Names are up to 30 printable ASCII characters, zero padded.
103. **After the reboot Offset waits 4 seconds,** then retries the first read for up to four
    attempts a second apart, before reading back. **(hardware)**
104. **The stock RT-950 Pro handshake now waits 3 seconds,** not 1.5, matching the timeout floor in
    Nathan Barguss's transport that the CHIRP module shown working on hardware uses.

## Every channel feature (0.6.0)

105. **Channel options are declared per radio** (`RadioProfile.channelOptions`) and travel as a
    string map on the channel. A channel that does not carry an option (one from a CHIRP file, a
    repeater list or another radio) keeps the slot's bytes for it, so nothing Offset was not told
    about is changed; a new slot gets the radio's own default, as its programming app creates one.
106. **Stock RT-950 Pro:** AM receive is bit 0 of byte 15, signalling group the low nibble of byte
    12 (0 to 15, numbered as CHIRP's module numbers them), scrambler the high nibble of byte 14
    (the menu offers off and 1 to 8; any nibble is kept), encryption bits 5 and 4 of byte 15 (off,
    types 1 to 3), learn frequency hopping bit 7, and the hopping code bytes 16 to 19 (six hex
    digits, last pair first, then `A0`; `FF FF FF FF` for none), all from Nathan Barguss's
    `channel.py`. What each signalling group, scrambler code and encryption type does on air is
    the radio's business. **(hardware)**
107. **nicFW950 values are the NicFW950 RMS app's:** modulation 0 to 8 (FM, AM, DSB, Auto, then
    the HF receiver's WFM, AM, LSB, USB, CW), bandwidth 0 to 7 (wide, narrow, auto, then 6, 4, 3,
    2, 1 kHz), power 0 to 6, scrambler 0 to 20 (off, 2600 to 4500 Hz), clarifier a signed 16 bit
    value in 10 Hz units, groups four bytes 1 to 26. The RMS packs groups with the first letter
    in the highest used byte; Offset writes them that way and reads them in any order.
108. **The HF receiver's modes only receive:** a channel in them must have transmit off, and the
    editor turns it off. nicFW channels accept 150 kHz to 580 MHz in 10 Hz steps; nicFW checks
    transmit against its own band plan. **(hardware)**
109. **Power levels:** Very high, High, Medium, Low, Very low and Zero map to nicFW bytes 6 to 1. A
    level a radio lacks becomes the nearest it has, the lower one on a tie (Very high is High on
    a UV-5R, Medium is Low on a two level radio). CHIRP export uses nominal watts: the stock
    RT-950 Pro's 10, 5 and 1 W, with Very high as 10, Very low as 0.5 and Zero as 0.
110. **CHIRP CSV:** Mode imports FM, NFM, AM, NAM, WFM, USB, LSB, CW (and its variants), DSB and
    Auto; digital modes are still refused. CHIRP has no DSB, so DSB and the HF receiver's AM
    export as AM. Radio options do not travel in CHIRP files.
111. **Scramblers, encryption and frequency hopping are editable** with a note that US rules
    forbid obscuring meaning on amateur and GMRS frequencies; Offset does not block them.
112. **Staging lists saved by 0.5.0 have no modes.** Their channels take the mode, and the nicFW
    power level 0.5.0 showed as High or Low, from the radio's channel in the same slot at the next
    read, which every write needs first; an untouched AM or HF channel is not written back as FM.
113. **The stock RT-950 Pro restarts after a write.** Seen on hardware (2026-11-19): the channels
    were written, the radio restarted, and the read back sent 100 ms after `E` went unanswered.
    Offset now waits 3 s, then the verifying read sends the handshake up to five times, 1 s apart,
    3 s each. How long the restart takes was not measured; an ordinary read still tries once so a
    radio that is off is reported at once. (hardware: confirm the verify passes)
114. **The stock RT-950 Pro is no longer experimental.** The user read and wrote a radio on the
    stock firmware several times with 0.6.1 (2026-11-19), with the write verified. The app no
    longer labels it experimental. The privacy and signalling values (item 106) are still
    unchecked on air. nicFW950 stays experimental until it is run on a radio.
115. **RepeaterBook approval (app ID 152, 2026-11-20).** The approval email's User-Agent field holds
    the whole answer from the application form, including the request to approve the pattern
    `Offset/* (github.com/vyntric-hq/offset; *)`, with prefix matching. Offset sends
    `Offset/<version> (github.com/vyntric-hq/offset; <email>)`, which fits the pattern. If Test
    connection reports a User-Agent rejection (`ua_mismatch`), the stored value needs correcting
    on RepeaterBook's side, not in Offset.
116. **Several repeater sources at once (0.7.0, the user's request).** Hearham, RepeaterBook and an
    imported CSV can be on together; results merge with `DirectoryMerge`, RepeaterBook first.
    The RepeaterBook application (2026-11-18) described one data source at a time, so showing
    RepeaterBook alongside Hearham goes beyond what was described; tell RepeaterBook if they ask.
    RepeaterBook is still only queried for what the user searches, and its attribution shows
    whenever its listings do. Settings from 0.6.4 and earlier (one source) carry over.
117. **A successful Test connection turns RepeaterBook on,** and removing the token turns it off.
118. **Offset sends RepeaterBook's stored approval text as its User-Agent (0.7.1, the user's
    decision).** A live test on 2026-11-20 showed every other User-Agent rejected with
    `ua_mismatch`: RepeaterBook requires a request to start with the whole stored text, which
    includes placeholders and a note. Offset sends exactly that text until RepeaterBook changes
    the approved value to the prefix `Offset/`; then it goes back to
    `RepeaterBookProvider.standardUserAgent`, with the version and the user's email. The email
    is still required in Settings.
119. **The stock RT-950 Pro's HF receiver keeps its own memory,** separate from the 960
    channels: 16 FM, 16 AM and 16 SSB entries with names, in the 0xB000 and 0xD000 blocks
    (Nathan Barguss's sections.py). Offset reads these blocks but does not decode or write them
    (safety rule 3) until a real image confirms their units; the user saved test entries on
    2026-11-20 to provide one.
120. **Dates and times follow Settings (0.7.4):** the phone's clock and zone by default, or a
    fixed 12 or 24 hour clock and a fixed zone, which is then named after each time. Exported
    file names use the phone's date.
121. **CB presets are AM at low power** (the radio's lowest level, under CB's 4 W AM limit).
    Whether the stock RT-950 Pro transmits AM, or needs a work mode set on the radio for CB, is
    unconfirmed; Offset writes only the channels. Generated image names from before 0.7.4 keep
    their timestamps: a typed name cannot be told apart from a generated one, so none is changed.
122. **A saved set is an image (0.7.5):** the staged channels laid over the last read, so saving
    needs a read. Two saves are the same set when the radio type and every channel slot match;
    settings bytes are not compared, because the radio changes some (VFO frequencies) on its own.
    Numbered copies follow Finder: "Name (2)", "Name (3)"; only user images count, and a match
    anywhere in the numbered family is reused.
123. **Marine 16 is receive only and MURS is at low power (0.7.5):** transmitting on marine
    channels from land is not permitted for ordinary users, and MURS allows 2 W.
124. **A set is saved over the read, else the loaded image (0.7.6):** staging remembers the id of
    the image last loaded from Images. A read in this session wins, since a write goes to that
    radio. Clear keeps the image so a set can be rebuilt over it; refilling staging from a read
    drops it. Loading an image selects its radio type, so every tab builds for the same radio
    (a read of another type is forgotten, as when the type is picked by hand), and the image is
    used only while that type stays selected. If the image is
    deleted, saving needs a read again.
125. **RT-950 Pro zones are detected from the names block (0.7.7):** firmware 0.24 and later keeps
    10 zone names of 16 bytes at radio 0xC000 and groups channels 10 x 99 (PE1HVH's CHIRP driver,
    tested on firmware 0.29; Hertzz58's firmware notes give the defaults "ZoneOne" to "ZoneTen").
    The firmware version is not readable over the cable, so Offset treats the block as zone names
    only when all ten slots hold printable ASCII and one is not empty. Otherwise there are no zones
    and 0xC000 is never written. Older firmware (15 x 64) has no known names location.
126. **Writing RT-950 Pro zone names is an exception to rule 3 (0.7.7):** the user asked to
    manage zone names on the radio. Only the 0xC000 blocks whose bytes changed are written, in
    the same session after the channels, as PE1HVH's driver does; the read back checks them.
    Names are printable ASCII, at most 12 characters (the CPS limit), and never blank, since
    detection relies on them.
127. **Offset still writes channels 1 to 960 on the RT-950 Pro (0.7.7):** on zone firmware,
    channels 961 to 990 (zone 10, channels 70 to 99) sit at 0x7800 to 0x7BBF, where older
    firmware may keep calibration-like data (NeonPlug). Without a firmware version Offset cannot
    tell them apart, so zone 10 shows 69 channels.
