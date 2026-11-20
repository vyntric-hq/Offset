# UV-5R clone protocol, as implemented

This describes exactly what the `:protocol` module does. It is a port of CHIRP's
`chirp/drivers/uv5r.py` (reference revision `d3bed431003087948b17bf15d09c53ad7dbbeec5`),
cross checked against sergev/baoclone `uv-5r.c`. Code: `protocol/src/main/kotlin/com/vyntric/offset/protocol/`.

## Physical link

- USB OTG adapter, then an FTDI, PL2303, CH34x or CP210x Kenwood 2-pin cable.
- 9600 baud, 8 data bits, no parity, 1 stop bit. DTR and RTS are asserted when the port opens
  (pyserial does the same for CHIRP).
- `UsbSerialAdapter` buffers surplus bytes because USB reads return whole packets.
- A read is one clone session: open the port, handshake, transfer, close. A write is also one
  session: handshake once, read the whole radio, save the backup, write the changed blocks and
  read the radio back, then close. After a session ends the radio stays in clone mode until it
  times out and restarts, and does not answer a new handshake for several seconds, so a write
  cannot be split into separate sessions (`RadioDriver.oneSessionWrite`). Sessions are still at
  least 1.5 s apart (`UsbRadioLink.SESSION_GAP_MS`).

## Handshake (`Uv5rClone.identify`)

1. Purge input.
2. Send a 7 byte magic one byte at a time, 10 ms apart.
3. Expect ACK `0x06` within 1 s, otherwise try the next magic after 2 s.
4. Send `0x02`.
5. Read up to 12 bytes, stopping after `0xDD`. 8 bytes is the ident. 12 bytes (UV-6 style
   firmware) is folded to 8 by keeping bytes 0, 3, 5 and 7..11.
6. Send ACK, expect ACK.

| Magic | Bytes | Used for |
| --- | --- | --- |
| UV5R_MODEL_291 | `50 BB FF 20 12 07 25` | UV-5R BFB291 and later, BF-F8HP |
| UV5R_MODEL_ORIG | `50 BB FF 01 25 98 4D` | UV-5R original firmware |
| UV5R_MODEL_A58 | `50 BB FF 20 14 04 13` | BF-F8HP family |

If no magic gets an answer the user sees "The radio did not answer" with cable advice
(`RadioException.NoContact`). CHIRP's blacklist probe for the UV-5X3 and UV-5G is not ported;
those radios simply fail to identify.

## Reading a block

```
host:  'S' addrHi addrLo size
radio: [0x06] 'X' addrHi addrLo size <size bytes>
host:  0x06            (then 50 ms pause)
```

After the first block the radio acknowledges the host's ACK; that byte arrives ahead of the
next header. CHIRP reads it explicitly; baoclone notes the BF-F8HP can deliver it late. Offset
accepts an optional leading `0x06` before any header, then requires `'X'`, the same address and
the same size, then exactly `size` data bytes within 1.5 s. Anything else is a protocol error
naming the block address.

## Download (`Uv5rClone.download`)

1. Handshake.
2. Firmware probe, as CHIRP does: read `0x1E80` (new firmware returns bad aux data unless a
   non-aux block is read first), `0x1EC0` and `0x1FC0`, all 0x40 bytes. The firmware string is
   bytes 48..62 of the `0x1EC0` block. If byte 15 of the `0x1FC0` block is `0xFF` the radio has
   the "dropped byte" quirk.
3. Main memory `0x0000..0x1800` in 0x40 byte blocks.
4. Aux memory `0x1EC0..0x2000` in 0x40 byte blocks, except that radios with the dropped byte
   quirk read `0x1FC0..0x2000` in 0x10 byte blocks.
5. Progress is reported in bytes of image data (6,464 total).

The result is always the full image (safety rule 1).

## Writing a block

```
host:  'X' addrHi addrLo 0x10 <16 bytes>     (then 50 ms pause)
radio: 0x06
```

## Upload (`Uv5rClone.upload`)

Offset never writes a whole image. It writes only the 16 byte blocks that differ between the
radio's freshly read image and the target, and only inside two regions:

| Region | Image offsets | Radio addresses | Content |
| --- | --- | --- | --- |
| Channels | `0x0008..0x0808` | `0x0000..0x0800` | 128 memory records |
| Names | `0x1008..0x1808` | `0x1000..0x1800` | 128 name records |

Both sit inside CHIRP's default upload ranges. Settings (`0x0E28..`), VFOs, DTMF, squelch tables,
band limits, calibration and the whole aux block are never written (safety rule 3).

Before the first block goes out, `upload` repeats three guards (the use case checks them too):

- every block offset is inside the regions above, otherwise `Unsupported`;
- the handshake ident equals the image ident byte for byte, otherwise `IdentMismatch`;
- the radio's firmware string equals the image's, otherwise `IdentMismatch`;
- the radio's channel and name memory (read again, 64 blocks) still equals the image read at the
  start of this write, otherwise `IdentMismatch`. The ident names a model, not a unit, so this is
  what stops a second radio of the same model, connected between the read and the write, from
  receiving blocks computed for the first.

It then consumes the radio's trailing ACK from the last read so block ACKs stay aligned.

## Image layout (`RadioImage`)

CHIRP's layout, 0x1948 bytes:

| Image offset | Size | Radio address | Content |
| --- | --- | --- | --- |
| `0x0000` | 8 | | Ident from the handshake |
| `0x0008` | `0x0800` | `0x0000` | Memory records, 128 x 16 |
| `0x0B08` | | `0x0B00` | PTT-ID codes (not edited) |
| `0x0E28` | | `0x0E20` | Settings (not edited) |
| `0x0F10`, `0x0F30` | | | VFO A and B (not edited) |
| `0x1008` | `0x0800` | `0x1000` | Name records, 128 x 16 |
| `0x1808` | `0x0140` | `0x1EC0` | Aux block: power on message, firmware string, squelch, band limits |

Images of 0x1808 bytes (no aux block) are accepted, as CHIRP accepts them. The firmware string
is image `0x1838..0x1846`; the upper band byte is ident byte 3 (`0x02` marks a 220 MHz radio).

## Memory record (`ChannelCodec`)

Bit numbers are most significant first, as in CHIRP's bitwise format.

| Byte | Field |
| --- | --- |
| 0..3 | `rxfreq`, little endian BCD in 10 Hz units (146.520 MHz is `00 20 65 14`) |
| 4..7 | `txfreq`, same encoding; `FF FF FF FF` or `00 00 00 00` means transmit inhibited |
| 8..9 | `rxtone`, little endian u16 |
| 10..11 | `txtone`, little endian u16 |
| 12 | `unused1:3`, `isuhf:1`, `scode:4` (PTT-ID code slot 0..14) |
| 13 | `unknown1:7`, `txtoneicon:1` |
| 14 | `mailicon:3`, `unknown2:3`, `lowpower:2` |
| 15 | `unknown3:1`, `wide:1`, `unknown4:2`, `bcl:1`, `scan:1`, `pttid:2` (0 off, 1 BOT, 2 EOT, 3 both) |

A slot is empty when byte 0 is `0xFF`. Clearing a slot writes `0xFF` over both its memory and
name records, as CHIRP does.

**Tones.** `0` or `0xFFFF` is none. Values at or above `0x0258` are CTCSS in tenths of a hertz
(1000 is 100.0 Hz). Smaller values are DCS: `index + 1` for normal polarity, `index + 1 + 0x69`
for inverted, where `index` is the position in CHIRP's UV5R_DTCS list (the 104 standard codes
plus 645, sorted).

**Duplex.** Derived exactly as CHIRP's `get_memory`: transmit inhibited is "off"; equal
frequencies are simplex; a difference over 70 MHz is "split" (offset holds the transmit
frequency); otherwise "+" or "-" with the difference as offset.

**Power.** `lowpower` indexes the model's level list: UV-5R High, Low; BF-F8HP High, Med, Low.
An out of range value decodes as High. On a dual power radio a Mid request is stored as Low.

**Byte preservation.** When a slot's decoded channel is unchanged, its 16 memory bytes and 16
name bytes are kept exactly, including undocumented bits and OEM padding. A changed channel is
rebuilt from zero as CHIRP's `set_memory` does (which clears `isuhf` and the icon bits), and the
9 trailing bytes of its name record are kept.

## Name record

Bytes 0..6 are the name (`nameLength` is 7 for every supported model), padded with `0xFF`;
bytes 7..15 are preserved. The character set is CHIRP's UV5R_CHARSET: `A-Z 0-9`, space and
`!@#$%^&*()+-=[]:";'<>?,./`. Names are uppercased; other characters become spaces. On decode,
`0xFF` (and `0x00`) inside a name read as spaces and trailing spaces are trimmed.

## Models (`RadioModel`)

The model is identified from the firmware string by CHIRP's `_basetype` lists. "N5RV" appears
in both lists; like CHIRP's registration order, Offset picks the UV-5R. Unrecognised firmware is
treated as a UV-5R and flagged in the UI.

| Profile | Base types | VHF | UHF | Power | Also covers |
| --- | --- | --- | --- | --- | --- |
| UV-5R | BFS, BFB, N5R-2, N5R2, N5RV, BTS, D5R2, B5R2 | 130 to 176 MHz | 400 to 520 MHz | High, Low | UV-5X, RT5R, RT5RV, RT5, RH5R, UV-5R EX, A-5R |
| BF-F8HP | BFP3V3 F, N5R-3, N5R3, F5R3, BFT, N5RV | 130 to 180 MHz | 400 to 521 MHz | High, Med, Low | UV-5R 8W, UV-5RTP, RT5(tri-power), GA-5S, UV-5XP, TI-F8+, UV-5R Pro, TS-T9+ |

UV-5R firmware older than BFB291 is "original" firmware (`RadioImage.isOriginalFirmware`).
Its layout for channels and names is the same, so Offset handles it the same way.

## Validation (`ChannelValidator`)

- Receive frequency inside the model's VHF or UHF range, on a 2.5 kHz or 6.25 kHz step.
- "+" and "-" offsets between 0 and 70 MHz; larger shifts must be split. The transmit frequency
  must also be in band.
- Name at most 7 characters from the character set above.
- CTCSS between 60.0 and 260.0 Hz; DCS from the supported code list.
- Power level available on the model; PTT-ID code within the model's codes (1 to 15 on the
  UV-5R; none on the RT-950 Pro).
- Mode one the model stores; the HF receiver's modes (nicFW950) need transmit off. Radios with
  `fineTuning` (nicFW950) take any 10 Hz step instead of the 2.5 or 6.25 kHz raster.
- Every radio option the channel carries is checked by its declaration (`ChannelOption`): a
  choice from what the field can hold, letters within the set and count, a number in range, a
  hex code of the right length. Options for other radios are ignored.

## Channel options (`ChannelOption`)

Settings only some radios store in a channel are declared by the radio's profile: key, label,
editor section (Mode, Scan, Signalling, Privacy, Advanced), kind and help. The codec decodes every
declared option into `Channel.options` and writes an option only when the channel carries it and
its value changed, so a channel from a file keeps the slot's bytes. See ASSUMPTIONS 105 to 111 for
each radio's fields.

## CHIRP .img files (`ChirpImg`)

```
<0x1948 image bytes> 00 FF 'chirp' EE 'img' 00 01 <base64 of JSON>
```

Offset writes the JSON with Python's default separators so it matches CHIRP byte for byte in
style:

```json
{"mem_extra": {}, "rclass": "BaofengUV5RGeneric", "vendor": "Baofeng", "model": "UV-5R", "variant": ""}
```

(`BaofengBFF8HPRadio` and `BF-F8HP` for the tri-power profile.) `chirp_version` is omitted,
which CHIRP accepts. On import, files without the trailer (raw dumps such as baoclone's) are
accepted; a trailer naming another model is rejected.

Verified against desktop CHIRP at the reference revision: an Offset export loads in CHIRP as a
Baofeng UV-5R with the expected channels, and the file CHIRP saved back is a test fixture
(`chirp-resaved-offset-export.img`) that Offset decodes to the same channels.

## CHIRP CSV (`ChirpCsv`)

Export writes CHIRP's columns: `Location, Name, Frequency, Duplex, Offset, Tone, rToneFreq,
cToneFreq, DtcsCode, DtcsPolarity, RxDtcsCode, CrossMode, Mode, TStep, Skip, Power, Comment,
URCALL, RPT1CALL, RPT2CALL, DVCODE`, with CHIRP's number formats (`146.520000`, `100.0`, `023`).
Power is written in watts (`4.0W`) because CHIRP's CSV importer parses watts. Mode is FM or NFM,
AM or NAM by bandwidth, otherwise CHIRP's name for the mode (WFM, USB, LSB, CW, Auto); DSB, which
CHIRP lacks, is written as AM. Verified by loading
an Offset CSV in CHIRP's generic CSV driver with no errors.

Import accepts columns in any order and these values: tone modes Tone, TSQL, DTCS and Cross with
cross modes `Tone->Tone`, `Tone->DTCS`, `DTCS->Tone`, `->Tone`, `->DTCS`, `DTCS->`, `DTCS->DTCS`;
power as a label or watts; frequencies as `146.52`, `146.52 MHz` or `600 kHz`. Bad lines are
reported and skipped. Extra columns (Callsign, Latitude, Longitude, City, County, State) are
kept for repeater search.

## Write safety (`WriteRadio`)

| Rule | Implementation |
| --- | --- |
| 1. Read the full image first | Every write starts with `download()` of the connected radio. |
| 2. Never write a mismatched ident | Blocked if the radio is not the one on the confirmation sheet, if a restored image's ident or model differs, and again inside `upload()`, which also re-reads the channel and name memory to catch a different unit of the same model. |
| 3. Patch only channels and names | The target is the fresh radio image with channels applied (or a restored image's writable memory copied in); `RadioImage.differsOnlyInWritableRegions` must hold. Each driver defines its writable memory (UV-5R: channel and name regions; DM-32UV: channel, TX contact, scan list and zone pages; RT-950 Pro: the channel segment). |
| 4. Backup before every write | The fresh image is saved to the library as "Backup before write <timestamp>"; if that fails nothing is written. |
| 5. Read back and compare | A third session downloads the radio and `RadioImage.mismatchedChannels` reports per channel differences. |
| 6. Confirmation sheet | Shown before every write and restore, naming model, firmware, ident, image name and channel count. |
| 7. Detach, timeout, permission | Every failure becomes an outcome: Blocked (nothing written), Failed with `radioMayBePartial` and the backup id, or Mismatch. A storage failure after a successful write still reports the verified write. Writes run in the application scope, are never cancelled midway, and `RadioOperations` always returns to idle. |

## Simulated radio (`SimulatedRadio`)

An in-memory radio with a 0x2000 byte address space that implements the same state machine:
magic, ident, clone mode, `S` reads with the ACK of ACK, `X` writes with ACK. It never blocks; an
empty output queue behaves like a serial timeout. Fault injection: `detachAfterHostBytes`,
`silent`, `corruptWriteAddresses`. Debug builds expose it under Settings, Debug.

## Other radio families

Each family implements `RadioDriver` (protocol module, `RadioDriver.kt`): image format, clone
protocol, serial speed, channel limits (`RadioProfile`) and writable memory. The use cases in the
app never touch bytes directly.

**Baofeng DM-32UV** (`Dm32uv*.kt`, ported from CHIRP's `baofeng_dm32uv.py`). 115200 baud. Session:
`PSEARCH` (retried, reply ACK + `DP570UV`), `PASSSTA` (password flags; protected radios are
refused), `SYSINFO`, `V` info queries (firmware, codeplug range), `G` and `PROGRAM` to enter
programming mode, then every 4 KB flash page's last byte is read to learn which tag each page
holds. Reads are `R` + 24 bit address + 16 bit length; writes are whole aligned pages with `W`,
each acknowledged and read back. The image is CHIRP's logical layout, one 4 KB slot per tag, so
.img files are interchangeable with CHIRP. Offset writes only pages with tags 0x12 to 0x41
(channels), 0x42 and 0x43 (TX contacts), 0x11 (scan lists) and 0x5C to 0x64 (zones), and only
those that differ; the zone header bytes that follow the radio's display are taken from the radio.

**Radtel RT-950 Pro** (`Rt950Pro*.kt`, protocol from Hertzz58's `cps_flash.py`, layout from Nathan
Barguss's tools). 115200 baud. Handshake `PROGRAMBT9000U` / ACK, `F` (16 bytes), `M` (12 byte
model), then a 25 byte `SEND` key frame selecting one of 20 four byte XOR keys. Payload bytes are
XORed except where the key byte is a space or the data byte is 00, FF, the key byte or its
complement. Blocks are 128 bytes: read `52 hi lo 80`, write `57 hi lo 80` + data, end with `E`.
The image is six segments (channels 0x0000 to 0x7800, VFO, function, DTMF, modulation, modulation
names) plus APRS (commands 54 and 55), 33,152 bytes. Channel records are 32 bytes: BCD RX and TX,
two tone words (DCS index or CTCSS tenths), signalling, PTT ID, power and scrambler, flags (narrow,
busy lockout, scan, TX enable, AM), frequency hopping code, 12 byte GB2312 name. Offset writes the
channel segment only. The radio restarts after a write session ends, so Offset waits 3 s and the
verifying read retries its handshake up to five times (about 20 s in all).

**Radtel RT-950 Pro on nicFW950** (`NicFw950*.kt`, from nicsure's nicFW880/950 wiki and RT950Pro
BTLE bridge, checked against the NicFW950 RMS app). 38400 baud, no handshake. Packets start `AA`,
numbers little endian. Read: `AA 30 addr32 00` answered `AA 30 addr32` + 128 bytes + sum. Write:
`AA 40 addr32` + 128 bytes + sum, answered `AA 40`. The sum is the low byte of the address and
data bytes added. `AA 48` commits (answered `AA 48`), `AA 50` reboots. The image is the channel
section, flash 0x50200 to 0x5F600: 62 byte `channelInfo` records, slots 0 to 3 the VFOs and
channel N in slot N + 3. A record is active when bytes 24 and 25 hold `0xC950`. Fields: RX and TX
(u32, 10 Hz), groups, RX and TX subtones (CTCSS tenths, or `0x8000` | octal DCS code, `0x4000`
inverted), clarifier, modulation, bandwidth, busy lock, power (0 no transmit to 6 very high),
reversed, PTT ID, magic, scrambler, 30 byte ASCII name. Offset writes only the changed blocks of
channel records, then commits and reboots.
