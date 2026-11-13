# Contributing to Offset

Thanks for helping. Offset writes to people's radios, so correctness and safety come before
features.

## Ground rules

- **No telemetry of any kind.** No analytics, crash reporting, Play Services or Firebase. Network
  access is limited to the repeater data sources the user chose.
- **FOSS dependencies only**, and as few as possible. The app must stay F-Droid eligible.
  Explain any new dependency in the pull request.
- **No secrets in the repository.** No tokens, keystores or credentials, ever.
- **No em dashes** in UI text, docs or comments. Use commas, colons or parentheses.
- **License.** Contributions are GPL-3.0-or-later. Code ported from another project keeps its
  attribution in the file header and in NOTICE.

## Layout

| Path | What lives there |
| --- | --- |
| `protocol/` | Pure Kotlin, no Android: image model, channel codec, clone protocol, simulated radio, CHIRP .img and CSV. |
| `app/.../transport` | USB serial (usb-serial-for-android) and the simulated radio link. |
| `app/.../data` | Room (images, staging), repeater providers, settings, encrypted credentials, location. |
| `app/.../domain` | Use cases: ReadRadio, WriteRadio, BuildStaging, ImportImage, ExportImage, RadioOperations. |
| `app/.../ui` | Compose screens, one ViewModel per screen, shared components and theme. |

Domain code depends only on interfaces (`RadioLink`, `ImageLibrary`, `RepeaterProvider`) and the
protocol module. The UI never talks to the radio directly; it goes through `RadioOperations`,
which runs reads and writes in the application scope so they cannot be cancelled halfway.

## Building and testing

You need JDK 17 and the Android SDK (platform 36, build tools 35.0.0).

```sh
./gradlew test                         # protocol and app unit tests
./gradlew lint                         # Android lint, must be clean
./gradlew assembleDebug                # debug APK with the simulated radio switch
./gradlew connectedDebugAndroidTest    # end to end tests on a device or emulator
OFFSET_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest --tests '*HearhamProviderTest*'   # opt in live call
```

In a debug build, turn on **Settings, Debug, Simulated radio** to use every screen without
hardware.

## Changing the protocol

- Read the matching code in CHIRP's `chirp/drivers/uv5r.py` first and cite it in the header.
- Every encoder and decoder change needs a round trip test and a fixture test. New fixture
  images go in `protocol/src/test/resources/fixtures/` with their source recorded in NOTICE.
- Never widen the regions Offset writes (`ImageRegions.WRITABLE`) without a design discussion.
- Update `docs/PROTOCOL.md` in the same change.

## Testing on hardware

Changes that touch the clone protocol or the USB transport must be tried on a real radio before
release. Record the radio model, firmware string (shown on the Radio tab), cable chip and phone
in the pull request. Always keep the automatic backup until you have checked the radio.

## Releases

1. Refresh the bundled repeater snapshot: `python3 scripts/update_repeater_bundle.py`, then run
   `./gradlew test` (BundledDataTest checks it) and commit `app/src/main/assets/repeaters/`.
   The county list rarely changes; refresh it yearly with `python3 scripts/update_counties.py`.
2. Add a `## [x.y.z] - date` section to CHANGELOG.md.
3. Tag `vx.y.z` and push the tag. The release workflow tests, builds, signs, attests and
   publishes. See docs/REPRODUCIBLE.md for the signing key setup.
