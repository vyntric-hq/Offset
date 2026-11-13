# Repeater data sources

Offset uses repeater data only to fill channels. It is not a directory, never republishes data,
and never sends the phone's position anywhere: distance and bearing are computed on the phone.

All three providers implement `RepeaterProvider` (`app/.../domain/Repeater.kt`) and return the
same `Repeater` fields: callsign, output frequency, input frequency (and so offset), uplink and
downlink tone (and so tone mode), city, county, state, latitude and longitude, distance, bearing,
source, last updated date when the source has one, service (amateur or GMRS) and whether the
repeater is mixed mode (FM plus a digital mode). Filtering by service, band, mixed mode, radius or
closest N, state, county and text is shared code (`RepeaterFilter`), so the providers differ only
in where data comes from. Each provider also reports a summary of what is on the phone ("N
amateur + M GMRS, updated <date>") without a network request.

Text search and "View all on map" use only data already on the phone. They fetch nothing,
except that Hearham downloads its list once if nothing is cached yet.

Pick the source under **Settings, Repeater data source**.

## Hearham (default)

| | |
| --- | --- |
| Endpoint | `GET https://hearham.com/api/repeaters/v1` (JSON array, about 10 MB, about 23,000 entries) |
| Auth | None |
| User-Agent | `Offset/<version> (github.com/vyntric-hq/offset)` |
| What is kept | Operational (`operational != 0`) analog FM repeaters on 2m (144 to 148 MHz) and 70cm (420 to 450 MHz), and on GMRS channel frequencies (marked GMRS). Mixed modes that include FM (for example `YSF/FM`) are kept and marked mixed mode. DMR, D-STAR and other digital-only entries are dropped. Hearham lists very few GMRS repeaters (2 operational on 2026-11-13). |
| Field mapping | `frequency` output Hz; `offset` added for input; `encode` uplink tone; `decode` downlink tone; `city` free text, with the state parsed from it ("Allen, TX", "Merced, California", "Parker, CO USA"); detail page `https://hearham.com/repeaters/<id>`. Tone strings like `100.0`, `DCS023`, `D754I` are parsed; DMR colour codes (`CC1`) and `0`, `CSQ` mean no tone. Mapping follows Repeater-START. |
| Bundled snapshot | The APK ships a snapshot of the list (`app/src/main/assets/repeaters/hearham.json.gz` plus `hearham.meta.json` with its date and record count, about 12,500 repeaters, 0.5 MB compressed), so search, text search and the map work on first launch with no network. It is produced by `scripts/update_repeater_bundle.py` from the public API, trimmed to operational analog FM records on 2m, 70cm and GMRS frequencies and to the fields Offset uses, and committed so builds never touch the network. |
| Cache | Offline first. Searches answer from the newest data on the phone: a download (`noBackupFilesDir/repeaters/hearham.json`) or the bundled snapshot. Data older than 7 days is still answered at once and marked stale; the Repeaters screen then tries one quiet refresh per session and, offline, keeps the local results. "Refresh" fetches on demand. The date shown says whether it is the bundled snapshot or a download. |
| Clear | Settings, Cached data, Clear Hearham cache. This removes the download; the bundled snapshot stays. |
| Never done | No account, no tracking parameters, no position sent. |
| Attribution | "Repeater data from Hearham.com" with a link under every result list, in the detail sheet, and on About. |
| County | Not available; Hearham has no county field, so the county picker is hidden. |

### Hearham terms

From `https://hearham.com/terms` (read 2026-11-13). Nothing there restricts reading the public
API; the relevant points are:

- Do not disassemble, reverse engineer or intrude on server or app components that are not
  published as open source. Offset only calls the documented public endpoint.
- Users must follow FCC rules for their radio hardware.
- Hearham is not responsible for missed contacts or incorrect listings, and the data must not be
  relied on for medical emergencies or other life and death situations. Offset's README repeats
  this.
- The rest of the page is Hearham's privacy policy for its own site and apps.

The terms state no rate limit and no licence for the data, and do not restrict copying the
public API output. Offset fetches the list at most once per 7 days per session unless the user
taps Refresh, and credits Hearham wherever its data appears.

**Bundling.** Offset ships a snapshot of Hearham's public list in the APK. The basis: Hearham's
terms do not restrict it, and Hearham's own open source client, Repeater-START (GPL, by the same
author), distributes a copy of the same API output (`repeaters.json`) in its public repository.
Hearham has not been asked for explicit permission; before the first public release, email the
operator (contact at hearham dot com, per the terms page) to confirm, and remove the bundle if
they object. The snapshot is credited "Repeater data from Hearham.com" in the app, the README and
NOTICE. A small extract (26 entries) is also kept as a unit test fixture.

**What is not bundled.** No RepeaterBook data is ever bundled: RepeaterBook's policy requires
written permission for offline bundling. GMRS coverage in the bundle is limited to the few GMRS
repeaters Hearham lists (2 at the 2026-11-13 snapshot); for GMRS, use RepeaterBook or import a
CSV.

## RepeaterBook (optional, off until configured)

| | |
| --- | --- |
| Endpoint | `GET https://www.repeaterbook.com/api/export.php?state_id=<FIPS>&mode=analog` for amateur, `...?state_id=<FIPS>&stype=gmrs` for GMRS (RepeaterBook documents `stype=gmrs` as required for GMRS). JSON object with `count` and `results`. |
| Auth | The user's own app-bound token (`rbuapp_...`), generated at `https://www.repeaterbook.com/user/api_apps.php`, sent only as `X-RB-App-Token`. Offset ships no token. |
| User-Agent | `Offset/<version> (github.com/vyntric-hq/offset; <user's contact email>)` |
| Storage of credentials | EncryptedSharedPreferences (AES-256, key in the Android Keystore), excluded from backup. If the keystore is unusable, the token cannot be saved; it never falls back to plain text. |
| Redirects | Never followed (`followRedirects(false)`, `followSslRedirects(false)`). Any 3xx is reported as an error and the request is not repeated, so the token cannot reach another origin. HTTPS only. |
| 429 | Stops immediately. Honours `Retry-After` (seconds or HTTP date). Without it, backs off 60 s, doubling to 15 min. No request is sent while backing off. |
| 401 and 403 | Not retried. The RepeaterBook error code (`auth_invalid`, `auth_revoked`, `ua_mismatch` and so on) becomes a plain message. |
| Query | By state (FIPS code), one request per state and service, and only for the services selected. "Near me" queries each state whose bounding box the radius can reach (160 km for "closest"), at most 4, and filters by distance on the phone. The county filter is applied on the phone to the cached state result, which avoids extra requests; the county picker lists the counties present in that result. Text search and the all-USA map use only states already cached. |
| Kept | `FM Analog` not `No`, `Operational Status` not off-air. Any of `DMR`, `D-Star`, `System Fusion`, `APCO P-25`, `NXDN`, `M17`, `Tetra` set to `Yes` marks the repeater mixed mode. GMRS detail links go to `/gmrs/details.php`. |
| Cache | The current query's states in memory for the session, plus one file per state and service on disk (`noBackupFilesDir/repeaters/repeaterbook/state-<FIPS>.json` and `gmrs-state-<FIPS>.json`) for 24 hours. |
| Clear | Settings, Cached data, Clear RepeaterBook cache. Removing the token is separate. |
| Test connection | One live request for the District of Columbia (FIPS 11), the smallest state query. |
| Never done | No bulk download, no bundled database, no sharing or exporting of cached data, no background refresh, no use of RepeaterBook data to build another directory or feed. Cached files are app private and excluded from backup and device transfer. |
| Attribution | "Data courtesy of RepeaterBook.com" with a link under every result list, in the detail sheet with a link to the repeater's RepeaterBook page, and on About. |

### RepeaterBook policy

From `https://www.repeaterbook.com/wiki/doku.php?id=api` (read 2026-11-13): API access requires
approval; distributed apps must not embed a shared token, and each user generates their own
app-bound token for an approved app; a valid User-Agent with a contact email is required;
redirects must not be followed with credentials; clients must back off on 429; programming tools
may cache only the minimum data needed and may not redistribute it. Offset's design follows each
of these points.

**Approval covers this app only.** RepeaterBook approves applications individually. Approval of
Offset (`github.com/vyntric-hq/offset`, with the User-Agent above) does not extend to forks,
rebuilds under another name or derived apps. If you fork Offset and keep RepeaterBook support,
you must apply to RepeaterBook for your own approval, change the User-Agent to name your app, and
tell your users to generate tokens for your app. Until the Offset application is approved by
RepeaterBook, users cannot generate a token for it and this source stays off.

The RepeaterBook test fixture (`app/src/test/resources/repeaterbook-fixture.json`) is synthetic;
no RepeaterBook data is stored in this repository.

## Imported CHIRP CSV

| | |
| --- | --- |
| Input | A CHIRP CSV chosen with the system file picker (Repeaters, Import). Coordinator lists and personal lists both work. |
| Optional columns | Callsign, Latitude or Lat, Longitude or Long or Lon, City, County, State. With coordinates, "near me" filters by distance; without any coordinates, every matching entry is listed. Rows on GMRS channel frequencies are treated as GMRS, so a GMRS list (for example from myGMRS) can be imported. |
| Storage | The parsed list on disk (`noBackupFilesDir/repeaters/imported-csv.json`), replacing any earlier import, until cleared. |
| Clear | Settings, Cached data, Clear imported CSV. |
| Network | None. |

## US counties (Census Bureau)

| | |
| --- | --- |
| Data | Every US county and equivalent with its internal point, from the Census Bureau 2025 Gazetteer (public domain, US government work): `app/src/main/assets/places/counties.tsv`, 3,222 rows, 120 KB. |
| Use | "Search from County" centres a near search on the chosen county. No network. |
| Refresh | `python3 scripts/update_counties.py [YEAR]`, deterministic output; commit the file. |

Grid squares (Maidenhead locators) and typed coordinates are converted on the phone; no data or
network is involved.

## Map tiles (OpenFreeMap)

| | |
| --- | --- |
| Library | MapLibre Native for Android (`org.maplibre.gl:android-sdk-opengl`), BSD-2-Clause. No Play Services, no telemetry. |
| Styles | `https://tiles.openfreemap.org/styles/liberty` (light) and `.../styles/dark`. |
| Auth | None. No key, account, cookies or registration (openfreemap.org). |
| User-Agent | `Offset/<version> (github.com/vyntric-hq/offset)` on every tile, glyph and sprite request. |
| What the server learns | The tiles requested reveal the area being viewed, which on the results map is around the phone. Repeater data and the phone's exact position are drawn locally and never sent. |
| Cache | MapLibre's ambient tile cache in app storage; previously viewed areas work offline. |
| Attribution | © OpenFreeMap, © OpenMapTiles, data © OpenStreetMap contributors: shown by the map's attribution button and in About. The repeater source's credit is in the map legend. |

Applying a CSV to a stored image (Images, an image, Import CHIRP CSV into this image) is a
separate feature that writes rows into their Location slots.
