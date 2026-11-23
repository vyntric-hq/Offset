# Changelog

All notable changes to Offset are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/). The release workflow publishes the section whose
heading matches the tag as the GitHub Release notes, so every tag needs a section here.

## [Unreleased]

## [0.7.8] - 2026-11-22

### Added

- Radio: Move to zone. Tap it above staging, check channels, then pick a zone to move them all
  there in their order. Each zone shows how many free channels it has.

### Changed

- An RT-950 Pro set without zones now says why in the Set section: "Read the radio" (tap it to
  read), or "Not found on this radio" when a read held no zone names. Sets loaded from images
  saved before 0.7.7 have no zones until the radio is read; the read keeps the staged channels.

## [0.7.7] - 2026-11-22

### Added

- RT-950 Pro zones (firmware 0.24 and later, 10 zones of 99 channels). Staging groups channels
  under their zone names. Rename zones from Zones in the Set section, pick a channel's zone in
  the channel editor, and choose where new channels go. A write renames the zones on the radio
  along with the channels, and the read back checks the names. Saved sets keep their zone names.
- Offset reads the RT-950 Pro zone names at 0xC000. Images saved by earlier versions still open;
  they have no zone names.

## [0.7.6] - 2026-11-22

### Changed

- Radio: a set loaded from Images can be named and saved to Images with no radio connected.
  The set is laid over the image it was loaded from, shown as "Based on". Clear keeps that
  image, so a new set can be built over it from scratch.

## [0.7.5] - 2026-11-22

### Added

- Radio: name the staged set and save it to Images without writing. The set is laid over the
  last read, so it exports and restores like any image.
- Writes save the result under the set's name. A set already saved with the same channels is
  not saved again; a different one under a used name becomes "Name (2)", "Name (3)" and so on.
- Presets: MURS 1 to 5 (low power; 1 to 3 narrow, 4 and 5 wide) and Marine 16 (receive only).
- Images: Select to check several images and delete them together.

### Changed

- The write confirmation shows the set's name instead of asking for one. The connection's
  button now reads "Save read to Images", apart from the set's own Save.

## [0.7.4] - 2026-11-20

### Added

- Radio: Save to Images keeps any read, not just the first read of a radio, so it can be
  exported.
- Presets: CB 1 to 40 (AM, low power), with a one time note on CB certification. Presets list
  only what the selected radio can tune, so CB shows for the RT-950 Pro and not the UV-5R.
- Settings, Date and time: a 12 or 24 hour clock (or the phone's choice) and a time zone (the
  phone's, UTC, or any zone by search). A fixed zone is named after each time.

### Changed

- Images show the date and time each image was saved. New generated names ("Written", "Backup
  before write", "Factory read RT-950 Pro") no longer carry timestamps; exported files still get
  the date in their file name.

## [0.7.3] - 2026-11-20

### Changed

- Staging: channels move only after tapping Reorder (next to Clear); Done locks the order again,
  so scrolling never reorders by accident.
- Repeater results show a small badge for each source that lists them (RB, HH, CSV) when more
  than one source is on; Settings shows the same badges next to the sources.
- Credits are one line, such as "Data courtesy of RepeaterBook.com and Hearham.com", each name
  linked, on the results, the map and Reach.

## [0.7.2] - 2026-11-20

### Changed

- Search shows a spinner while it finds your location and searches, then a tick with the number
  of repeaters found, or a warning when the search failed.
- Confirmations and errors on Repeaters, Settings and Images appear as a short notice at the
  bottom of the screen, so they are seen wherever the screen is scrolled.

## [0.7.1] - 2026-11-20

### Fixed

- RepeaterBook searches work. RepeaterBook only accepts the exact User-Agent text it stored when
  it approved Offset, so Offset sends that text until RepeaterBook corrects it.
- RepeaterBook's error codes are read from the field it actually uses (`error_code`).

## [0.7.0] - 2026-11-20

### Added

- Several repeater sources at once: turn Hearham, RepeaterBook and an imported CSV on together
  under Settings, Repeater sources. Results are merged, a repeater two sources list appears once,
  and a source that fails is reported without hiding the others' results.
- Test connection shows a spinner while it runs, then a tick or the error. Saving a token tests it
  straight away, and a working token turns RepeaterBook on.

### Changed

- Settings text is much shorter.
- A User-Agent rejection from RepeaterBook now reads as one plain line.

## [0.6.4] - 2026-11-20

### Changed

- RepeaterBook has approved Offset, so a RepeaterBook token for Offset can now be generated on
  RepeaterBook's token page. The Settings instructions are shorter and no longer say approval is
  pending.

## [0.6.3] - 2026-11-20

### Changed

- Simplex presets: tapping a preset shows its description and every channel, with Add at the top
  and bottom. Nothing is staged until Add is tapped. The list shows each preset's channel count.
- The Connection section's note is one line: seat the cable firmly, then turn the radio on. The
  RT-950 Pro and nicFW950 cable notes are shorter.

## [0.6.2] - 2026-11-19

### Changed

- RT-950 Pro (stock firmware) is no longer marked experimental: reading and writing are
  confirmed on a radio. Its cable note is shorter. nicFW950 stays experimental.

## [0.6.1] - 2026-11-19

### Fixed

- RT-950 Pro (stock firmware): a write reported "Write not completed" although the radio held
  the new channels. The radio restarts after a write and Offset tried to read it back before it
  had booted. Offset now waits for the restart and retries the verifying read for about 20 s.

## [0.6.0] - 2026-11-19

### Added

- Every setting the RT-950 Pro stores in a channel can be edited, on both firmwares.
  - Stock firmware: AM receive, signalling group, scrambler, encryption and frequency hopping
    (learn and code).
  - nicFW950: mode (FM, AM, DSB, Auto, and the HF receiver's broadcast FM, AM, LSB, USB and CW),
    bandwidth including the HF filter widths, all six power levels, scan groups A to Z, clarifier,
    reversed and scrambler. HF channels take 10 Hz steps and only receive.
- The channel editor is organised into Mode, Offset, Tone, Transmit, Scan, Signalling, Privacy
  and Advanced sections, showing only what the radio has. Scan groups are picked on an A to Z
  grid; privacy features carry a note about the law.
- Channel rows show the mode, receive only channels and nicFW scan groups.
- CHIRP CSV files carry the mode (AM, NAM, WFM, USB, LSB, CW, Auto) both ways.

### Changed

- Menu rows show their value against the right edge.
- A power level a radio lacks maps to the nearest it has, the lower one on a tie.

### Fixed

- The PTT-ID code row no longer appears for radios whose channels have no code (both RT-950 Pro
  firmwares).
- "1 memory block written" instead of "1 memory blocks".
- A staging list saved by 0.5.0 takes each channel's mode (and nicFW's extra power levels) from
  the radio at the next read, so writing it does not turn AM or HF channels into FM.

## [0.5.0] - 2026-11-19

### Added

- Radtel RT-950 Pro running nicsure's nicFW950 firmware (experimental): read, write and restore
  its 999 channels. Pick "RT-950 Pro (nicFW950)" as the radio type. Only the channel blocks that
  change are written, each checked against the radio first; the radio restarts afterwards and
  Offset reads it back to verify.

### Changed

- RT-950 Pro (stock firmware): the handshake now waits 3 seconds for the radio, as the CHIRP
  module shown working on this radio does.

### Fixed

- The channel editor no longer uppercases names on radios that show lowercase (RT-950 Pro,
  DM-32UV, nicFW950); the UV-5R family still gets capitals.

## [0.4.1] - 2026-11-18

### Added
- Reach's antenna catalog lists the ZBM2 Industries Foul Weather Whip V2 (2 m/70 cm, 1.25 m/70 cm,
  70 cm 7 in, GMRS/MURS, GMRS 6 in), Stubby and Tough Whip NMO; the Practical Comms Jungle Antenna
  (2 m/70 cm, GMRS, 220 MHz); and the N9TAX Labs slim jims (2 m/70 cm and MURS/GMRS, standard or
  with 10 ft or 16 ft cable, and single band 2 m or 70 cm). Gains are realistic figures rather
  than advertised ones; see docs/ASSUMPTIONS.md.

### Fixed
- A link card no longer says "Mainly line of sight" for a path a mountain blocks. That label was
  the terrain model's internal category, not what the terrain does, so it is gone.
- On a path several ridges block, the card points at the highest ridge, the one the chart shows,
  and says how many places block the line of sight.

## [0.4.0] - 2026-11-18

### Added
- **Reach**, a new tab that maps how far your station's signal carries over the real terrain on
  VHF and UHF, and which repeaters and people are inside it.
  - Save your radios, antennas and stations once (radio, antenna, power, height, feedline, a
    beam's heading, and GPS or a fixed home location), then pick a station in one tap. A catalog
    of common radios and antennas fills in the numbers.
  - Two directions: "Who hears me" (your transmitter) and "Who I hear" (theirs), on each band the
    station has, to handhelds, mobiles, base stations or repeaters.
  - Coverage is computed on the phone with a port of NTIA's Longley-Rice terrain model, sweeps
    in as it is modelled, and is drawn in three bands (likely, marginal, unlikely) with contours.
  - Tap any repeater or place for its link both ways, with a terrain profile showing the line of
    sight, the Fresnel zone and what blocks it. Repeaters can be added to staging from there.
  - Add places (team members, a friend's house) by long press on the map, coordinates, a grid
    square or a map link, or paste a whole team's locations at once.
  - Elevation tiles are kept on the phone, so an area works offline once mapped. Settings can save
    a whole state ahead of a trip, show how much is stored, and remove it.

## [0.3.3] - 2026-11-17

### Changed
- The screen stays on while Offset reads or writes a radio, so the phone does not go to sleep
  with the progress hidden. It turns off normally again once the read or write ends.

## [0.3.2] - 2026-11-17

### Fixed
- Writing to a UV-5R failed with "The radio did not answer" after reading it, and nothing was
  written. The radio does not answer a new programming session for several seconds after one
  ends, and a write used three (read, write, verify). A UV-5R write is now one session.
- "The radio did not answer" now says to wait 10 seconds and try again when the radio was just
  read or written.

## [0.3.1] - 2026-11-14

### Added
- README screenshots of the main screens, in `docs/screenshots`.

### Fixed
- The Refresh link under repeater results no longer wraps one letter per line next to a long
  "Snapshot included with the app" date.
- Section headers with actions (Staging on the Radio tab) move the actions to their own line when
  both do not fit, instead of breaking the title.
- Local builds without a release tag report the current version instead of 0.1.0.
- At large font sizes, the collapsed screen title no longer draws over the top bar buttons (it
  shortens, and the bar grows taller), channel numbers no longer wrap and stay in line, and tab
  labels shrink to fit instead of being cut off.

## [0.3.0] - 2026-11-14

### Added
- Baofeng DM-32UV support, ported from CHIRP's driver and cross checked byte for byte against
  it: read, write and restore of analog channels across 4000 channels. DMR channels are shown and
  kept exactly as they are. Channels keep their numbers; new ones go into free numbers and a zone
  named "Offset". Deleting a channel removes it from zones and scan lists, as CHIRP does.
- Radtel RT-950 Pro support (experimental): read, write and restore of its 960 channels, from
  community reverse engineering. Only channel memory is written; AM, frequency hopping and
  scrambler settings are kept as stored. Writes carry an extra warning until verified on a radio.
- A radio type picker on the Radio tab. Images remember their radio type; existing images become
  UV-5R family images on upgrade.
- CHIRP .img import and export for the DM-32UV (compatible with CHIRP), CHIRP CSV numbering per
  radio, and RepeaterBook setup steps in Settings (account, callsign requirement, sign-in, token).

### Changed
- A write checks only the channels it changes, so a channel already on the radio that Offset
  would not create itself (an airband channel, for example) no longer blocks it.
- Radio code is organized as one driver per radio family behind a common interface.

## [0.2.0] - 2026-11-13

### Added
- IRLP node listings (607 nodes that reported in the last 30 days) and OpenStreetMap repeater
  listings (81) ship with the app and are merged into the default Hearham data: 89 repeaters
  are added and 590 Hearham entries gain a matching listing.
- Verification on every repeater: list rows say "2 sources" (independent directories only),
  "IRLP node" or "Sources disagree on tone", and the detail sheet's "Listed by" section names
  each directory, which entries were copied from another directory, each tone when they differ,
  and when an IRLP node last reported in.
- Credits for every directory behind the results, in the result list, the map legend and About.
- `scripts/update_listings_bundle.py` refreshes the IRLP and OpenStreetMap listings.

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
