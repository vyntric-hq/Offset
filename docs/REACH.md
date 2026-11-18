# Reach

Reach estimates how far a station's VHF/UHF signal carries over the real terrain, and whether
particular repeaters and people are inside it, in both directions. It answers the question that
comes before programming a radio: which of these repeaters can I actually use from here?

## Goals

- Pick a saved station (radio, antenna, height, power) in one tap and see coverage on a map.
- Two directions: **Who hears me** (my transmitter reaching them) and **Who I hear** (theirs
  reaching me). A repeater is two links and they often differ.
- Check any single link in detail: a terrain profile, the margin in dB, and what blocks it.
- Add other places (team members, a friend's house) by GPS, grid square, coordinates, a long
  press on the map, or pasted text, and see whether each is inside coverage.
- Work offline once the terrain for an area is on the phone.

## Non-goals

- HF. Reach covers line-of-sight bands only (6 m and up). Ionospheric propagation is a different
  problem and a different app. On 6 m, sporadic-E openings are ignored, and the screen says so.
- Exact predictions. Reach shows likelihood in three bands (likely, marginal, unlikely), never a
  hard boundary, and is always labelled an estimate.

## Anti-goals

- A repeater finder with a coverage veneer. Every repeater card ends in "Add to staging".
- Hiding assumptions. Whatever Offset assumes about the far station is shown on the link card
  and can be changed.
- Sending location anywhere. No geocoding service: addresses are not supported, places are
  entered as GPS, grid, coordinates or a map press.

## Approaches considered

| Choice | Picked | Alternative | Why |
| --- | --- | --- | --- |
| Signal model | Port of NTIA's Longley-Rice ITM, point-to-point mode | Line of sight plus Fresnel clearance and knife-edge loss | ITM is the accepted standard (Radio Mobile, SPLAT!), public domain, and checkable against NTIA's reference cases. The simpler model gives numbers that are hard to stand behind. |
| Terrain | Open terrain tiles fetched for the area and kept on the phone, plus per-state offline download | Elevation bundled in the APK | US elevation is hundreds of MB. Tiles for a 150 km area are a few MB. |
| Repeater data | The current repeater provider (Hearham, RepeaterBook or CSV) | Hearham only | The user's decision: Reach uses whichever provider is selected, RepeaterBook included. |

## How it works

### Model (`:propagation`, pure Kotlin)

- `itm/Itm.kt`: NTIA ITM P2P_TLS, ported function by function and tested against reference cases.
- `Terrain`: elevation lookup; `TerrainProfile` samples a great-circle path into ITM's profile
  format.
- `RfStation`: height, transmit power, antenna gain and pattern, line loss, receiver sensitivity.
- `Link`: received power and margin one way between two stations, using ITM's median loss
  (50% time, location and situation, mobile variability). Verdict from the margin:
  likely at 10 dB or more, marginal from 0 to 10 dB, unlikely below 0.
- `Coverage`: 360 radials from the station; along each, ITM loss at every sample to a far
  station of a chosen type. The result is a polar grid of margins, rasterised for the map.
- `PathAnalysis`: what the profile chart draws: terrain with earth curvature (k = 4/3), the line
  of sight, the first Fresnel zone, and the worst obstruction.

### Defaults for the far end (shown and editable on the link card)

| Far station | Height | Power | Antenna | Line loss | Sensitivity |
| --- | --- | --- | --- | --- | --- |
| Handheld | 1.5 m | 5 W | 0 dBi | 0 dB | -121 dBm |
| Mobile | 1.8 m | 50 W | 2.15 dBi | 1 dB | -121 dBm |
| Base | 10 m | 50 W | 6 dBi | 2 dB | -121 dBm |
| Repeater | 30 m | 50 W | 6 dBi | 4 dB (feedline and duplexer) | -120 dBm |

### App

- **Inventory** (Room): radios, antennas, stations (radio + antenna + height + power + feedline +
  optional bearing for a beam + GPS or a fixed spot), and places. A catalog of common radios,
  antennas and feedlines fills in numbers so setting up takes a few taps.
- **Terrain** (`TerrainRepository`): Terrarium PNG tiles from the AWS Open Data terrain tiles,
  decoded on the phone, kept in app storage, with a size shown and a Clear button in Settings.
- **Reach tab**: a full-screen map with the station card, the direction switch, the band and the
  far station type on top, and a summary panel at the bottom listing repeaters and places by
  verdict. Tapping any pin or row opens the link card.

### Map layers, bottom to top

Base map, subtle hillshade from the same terrain tiles, the coverage raster (green likely,
amber marginal, clear unlikely), a beam wedge for a directional antenna, repeater pins coloured
by verdict, place pins with names, and the station pin.

## Left to the builder

Tile zoom per radius, raster resolution, colour ramp details, and the catalog's exact entries
(with sources noted in docs/ASSUMPTIONS.md).
