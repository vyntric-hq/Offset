# Reproducible builds

Anyone can rebuild the release APK from its tag and check that it matches the published APK
byte for byte, except for the signature.

## Pinned toolchain

| Component | Version | Where it is pinned |
| --- | --- | --- |
| JDK | 17 (CI uses Eclipse Temurin 17.0.13+11) | `.github/workflows/*.yml`, `kotlin.jvmToolchain(17)` |
| Gradle | 8.14.3, distribution SHA-256 checked | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.13.2 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.21 (Compose compiler plugin matches) | `gradle/libs.versions.toml` |
| KSP | 2.2.21-2.0.5 | `gradle/libs.versions.toml` |
| Android build tools | 35.0.0 | `app/build.gradle.kts` (`buildToolsVersion`) |
| compileSdk / targetSdk / minSdk | 36 / 35 / 29 | `app/build.gradle.kts` |
| Libraries | exact versions, no ranges (MapLibre 13.7.1) | `gradle/libs.versions.toml` |

## What makes the output deterministic

- `versionName` comes from the tag (`v1.2.3` gives `1.2.3`) and `versionCode` is computed from it
  (`major*10000 + minor*100 + patch`). Nothing reads the clock at build time.
- The commit shown on About comes from `git rev-parse --short=12 HEAD`, which is fixed for a tag.
- The release key fingerprint shown on About is read from the committed file `release-cert.sha256`,
  not from the keystore, so an unsigned rebuild contains the same bytes.
- Archive tasks have `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true`.
- `vcsInfo.include = false`: no `META-INF/version-control-info.textproto`.
- `dependenciesInfo` is off: no Google dependency metadata block (also an F-Droid requirement).
- Baseline profile (`ArtProfile`) tasks are disabled; they are a known source of differing bytes.
- R8 is deterministic for the same inputs and versions.
- The bundled repeater snapshot and secondary listings are committed files written deterministically (sorted records,
  compact JSON, gzip with a zero timestamp). Builds never download it.
- MapLibre's prebuilt native libraries are packaged unstripped (`jniLibs.keepDebugSymbols`), so the
  result does not depend on whether an NDK is installed. Release APKs contain the ARM ABIs only.

Checked on 2026-11-13 with the 0.1.0 source including the map (outside a git checkout, so the
commit reads `unknown`): building `-Poffset.versionName=0.1.0` in two different directories, one
with a fresh Gradle daemon, produced the same unsigned APK (SHA-256
`4c1597ad6c66fa49a8c2d030916cfebe8620cce3dd8e04efe7afecd6511da223`). An APK signed the way the
release workflow signs compared equal to an independent unsigned build with
`apksigcopier compare`. Hashes from a git checkout differ, because the commit is embedded.

## Rebuild and compare

You need git, JDK 17, the Android SDK command line tools, and Python 3 for `apksigcopier`.

```sh
# 1. Get the exact source. Build from a git checkout: the commit hash is part of the APK.
git clone https://github.com/vyntric-hq/offset.git
cd offset
git checkout v1.2.3

# 2. Point Gradle at your SDK and install the pinned packages.
echo "sdk.dir=$ANDROID_HOME" > local.properties
sdkmanager "platforms;android-36" "build-tools;35.0.0"

# 3. Build the unsigned release APK with the version from the tag. Always from clean: an
#    incremental Kotlin build can order synthetic classes differently and change classes.dex.
./gradlew --no-daemon clean assembleRelease -Poffset.versionName=1.2.3
#    -> app/build/outputs/apk/release/app-release-unsigned.apk

# 4. Download the published APK and compare everything except the signature.
gh release download v1.2.3 --repo vyntric-hq/offset --pattern 'offset-1.2.3.apk'
pip install apksigcopier
apksigcopier compare offset-1.2.3.apk --unsigned app/build/outputs/apk/release/app-release-unsigned.apk \
  && echo "Reproducible: identical apart from the signature"
```

`apksigcopier compare` copies the signature from the published APK onto your unsigned build and
checks that the result is byte identical to the published APK. If you build from a source
archive instead of git, pass the tag's commit explicitly: `-Poffset.gitCommit=<first 12 hex
digits of the tag commit>`.

If the comparison fails, check the JDK major version, that you built the tag (not a branch), and
that `release-cert.sha256` is unmodified, then open an issue with both APKs' `sha256sum`.

## Signing key

The release keystore is never committed. It lives only in GitHub Actions secrets.

### Generate it once, locally

```sh
keytool -genkeypair -v \
  -keystore offset-release.jks \
  -alias offset \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Offset, O=vyntric-hq"
```

Choose strong store and key passwords. Keep an offline backup of the keystore and passwords:
losing them means users cannot upgrade to builds signed with a new key.

### Add it to GitHub

```sh
base64 < offset-release.jks | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64 --repo vyntric-hq/offset
gh secret set KEYSTORE_PASSWORD --repo vyntric-hq/offset   # paste the store password
gh secret set KEY_ALIAS --repo vyntric-hq/offset           # offset
gh secret set KEY_PASSWORD --repo vyntric-hq/offset        # paste the key password
```

These four secrets are the only ones the workflows use.

### Publish the fingerprint

```sh
keytool -list -v -keystore offset-release.jks -alias offset | grep 'SHA256:'
```

Put the value in `release-cert.sha256` (one line, colons optional) and in the README section
"Verify the APK you downloaded", and commit both before tagging. The release workflow refuses to
sign with a keystore whose fingerprint differs from `release-cert.sha256`, and the About screen
compares the installed app's certificate against it.

## Release workflow

`.github/workflows/release.yml` runs on tags `v*`: unit tests, decode the keystore into the
runner's temp directory, check its fingerprint, `assembleRelease` signed with APK signature
schemes v2 and v3, delete the keystore, `apksigner verify`, write `SHA256SUMS`, attest build
provenance with `actions/attest-build-provenance`, and create the GitHub Release with the APK,
`SHA256SUMS` and the CHANGELOG section for the version.
