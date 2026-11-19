# Every channel feature, editable (0.6.0)

## Intent

Anything a radio stores in a memory channel can be seen and changed in Offset, starting with the
RT-950 Pro on both firmwares. Radio settings (menus, keys, display, APRS) stay out: Offset
programs channels, and safety rule 3 still forbids writing anything else.

## Approaches considered

1. A separate editor per radio. Quick to make each look custom, but frequency, tone and offset
   would be duplicated per radio and drift apart.
2. Radio-declared options only. One editor, but mode and power would become radio specific even
   though CHIRP files and imports need to understand them.
3. **Hybrid (chosen by the user).** Shared concepts live on `Channel`; everything else is declared
   by the radio's profile and drawn by one editor.

## Design

- **Mode** (`Channel.mode`, enum `Modulation`): FM, AM, DSB, Auto, and the nicFW tuner modes
  (broadcast FM, tuner AM, LSB, USB, CW). Each profile lists the modes it stores; the tuner modes
  are receive only. CHIRP CSV maps Mode both ways (DSB exports as AM; CHIRP has no DSB).
- **Power**: the enum gains Very high, Very low and Zero. Each profile lists its levels. Mapping a
  level a radio lacks picks the nearest one, the lower on a tie.
- **Radio options** (`Channel.options`, a string map keyed `radio.field`, and
  `RadioProfile.channelOptions`): typed declarations (choice, switch, letters, number, hex code)
  with label, section, help and a privacy flag. A missing key means "keep what the slot holds", so
  CSV imports and new channels never clobber unmodelled bytes. The validator checks values.
- **Per radio**
  - Stock RT-950 Pro: AM receive (mode), signalling group, scrambler, encryption, frequency
    hopping (learn flag and code).
  - nicFW950: modes, bandwidth (wide, narrow, auto, tuner widths), all six transmit levels, scan
    groups A to Z (up to four), clarifier, reversed, scrambler.
- **Editor**: one sheet, sections Frequency, Offset, Tone, Mode, Transmit, Scan, Signalling,
  Privacy, Advanced; a section appears only when the radio has something in it. Privacy carries a
  legal warning in the style of the GMRS one. Choosing a receive only mode turns transmit off.
- **Frequency step**: profiles may allow 10 Hz steps (nicFW, for SSB); others keep the 2.5 or
  6.25 kHz rule.

## Non-goals

Radio settings, the stock firmware's separate broadcast tuner memory (0xB000), nicFW group names.

## Left to the builder

Labels, help text and section order details; the nominal watts for the new levels (used only for
CHIRP export).

## Design note (as built)

- **Seams.** `ChannelOption` (protocol) is the one new seam: a radio declares its extra channel
  fields, the codec reads and writes them, the validator checks them and the editor draws them
  by kind. `Channel.mode` and the wider `Power` enum are shared because CHIRP files, imports and
  every codec need them. `RadioProfile` gained `modes`, `channelOptions`, `storesWide`,
  `pttIdCodes`, `fineTuning`, `nearestMode`, `ownOptions` and `completeOptions`.
- **"Missing means keep".** Options are written only when the caller's channel carries them and
  they changed. `normalize` fills defaults (what an empty slot reads back as) but encode looks at
  the caller's map, so a CHIRP import never clears a scrambler or groups. A test caught the first
  version getting this wrong.
- **Values the radio holds but its menus never offer** (a scrambler nibble of 12) are storable,
  shown as "Setting n", never flagged; otherwise reading a radio could produce problems that
  block a write of channels the user never touched.
- **Tradeoffs.** Options are strings, not typed per radio, so they persist and compare simply;
  the declarations carry the typing. nicFW's bandwidth is an option and `wide` is derived from it
  for CHIRP export, so two fields describe one byte; the codec treats the option as the source
  when present. Labels for the stock signalling group and scrambler codes are the raw numbers
  CHIRP shows, because what they mean on air is not documented.
