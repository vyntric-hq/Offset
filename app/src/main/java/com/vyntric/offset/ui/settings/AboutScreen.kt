/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.vyntric.offset.BuildConfig
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.CellRow
import com.vyntric.offset.ui.components.DetailRow
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.LargeTitleScreen
import java.security.MessageDigest

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val fingerprint = remember { signingFingerprint(context) }
    val published = BuildConfig.RELEASE_CERT_SHA256
    val matches = fingerprint != null && normalize(fingerprint) == normalize(published)

    LargeTitleScreen(title = "About", onBack = onBack, backLabel = "Settings") {
        item(key = "app") {
            InsetSection(footer = "Offset programs Baofeng UV-5R family, Baofeng DM-32UV and Radtel RT-950 Pro radios over a USB cable. No accounts, no analytics, no tracking.") {
                cell { DetailRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
                cell { DetailRow("Commit", BuildConfig.GIT_COMMIT) }
                cell { DetailRow("License", "GPL-3.0-or-later") }
                cell { ButtonRow("Source code") { uri.openUri("https://${BuildConfig.GITHUB_REPO}") } }
            }
        }
        item(key = "credits") {
            InsetSection(header = "Credits") {
                cell {
                    CellRow("CHIRP", subtitle = "The UV-5R and DM-32UV clone protocols, memory maps and the .img format are ported from CHIRP's uv5r.py and baofeng_dm32uv.py (GPL).",
                        chevron = true) { uri.openUri("https://github.com/kk7ds/chirp") }
                }
                cell {
                    CellRow("baoclone", subtitle = "Protocol cross check and test images, by Serge Vakulenko (BSD).", chevron = true) {
                        uri.openUri("https://github.com/sergev/baoclone")
                    }
                }
                cell {
                    CellRow("RT-950 Pro reverse engineering", subtitle = "Clone protocol from Hertzz58's RT-950 Pro firmware tools (GPL). " +
                        "Channel layout from Nathan Barguss, 2E0NBS (MIT).", chevron = true) {
                        uri.openUri("https://github.com/NathanBarguss/Chirp_Radtel-RT-950-Pro")
                    }
                }
                cell {
                    CellRow("Hearham", subtitle = "Repeater data from Hearham.com, the default source.", chevron = true) { uri.openUri("https://hearham.com") }
                }
                cell {
                    CellRow("IRLP", subtitle = "Node data from the IRLP status page, included with the app.", chevron = true) { uri.openUri("https://status.irlp.net") }
                }
                cell {
                    CellRow("OpenStreetMap", subtitle = "Repeater listings \u00A9 OpenStreetMap contributors, ODbL 1.0, included with the app.", chevron = true) {
                        uri.openUri("https://www.openstreetmap.org/copyright")
                    }
                }
                cell {
                    CellRow("RepeaterBook", subtitle = "Data courtesy of RepeaterBook.com, when you configure your own token.", chevron = true) {
                        uri.openUri("https://www.repeaterbook.com")
                    }
                }
                cell {
                    CellRow("usb-serial-for-android", subtitle = "USB serial drivers by mik3y (MIT).", chevron = true) {
                        uri.openUri("https://github.com/mik3y/usb-serial-for-android")
                    }
                }
                cell {
                    CellRow("Longley-Rice (NTIA ITM)", subtitle = "Reach's terrain model, ported from NTIA's Irregular Terrain Model, a public domain work of the U.S. Government.", chevron = true) {
                        uri.openUri("https://github.com/NTIA/itm")
                    }
                }
                cell {
                    CellRow("Terrain tiles", subtitle = "Elevation from the AWS Open Data terrain tiles: USGS 3DEP, SRTM and GMTED2010 courtesy of the U.S. Geological Survey; Canada under the Open Government Licence; Mexico from INEGI.", chevron = true) {
                        uri.openUri("https://registry.opendata.aws/terrain-tiles/")
                    }
                }
                cell {
                    CellRow("MapLibre and OpenFreeMap", subtitle = "Map by MapLibre Native (BSD). Tiles from OpenFreeMap: \u00A9 OpenMapTiles, data \u00A9 OpenStreetMap contributors.",
                        chevron = true) { uri.openUri("https://openfreemap.org") }
                }
                cell { CellRow("Inter", subtitle = "Typeface by Rasmus Andersson (SIL Open Font License 1.1).") }
            }
        }
        item(key = "verify") {
            InsetSection(
                header = "Signing certificate",
                footer = "To verify a downloaded APK, run: apksigner verify --print-certs offset.apk, and compare the SHA-256 " +
                    "digest with the one above and in the README. Release APKs also carry a GitHub build attestation: " +
                    "gh attestation verify offset.apk --owner vyntric-hq",
            ) {
                cell { CellRow("SHA-256 of this install", subtitle = fingerprint ?: "Unavailable") }
                if (published.isNotEmpty()) {
                    cell {
                        CellRow(
                            if (matches) "Matches the published release key" else "Does not match the published release key",
                            subtitle = if (matches) null
                            else "Debug builds and builds from other sources are signed with a different key.",
                        )
                    }
                }
            }
        }
    }
}

/** apksigner prints lowercase hex without colons; keytool prints uppercase with colons. */
private fun normalize(fp: String) = fp.replace(":", "").trim().lowercase()

/** SHA-256 of the APK signing certificate, colon separated, as apksigner prints it. */
private fun signingFingerprint(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signers = info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
    val cert = signers?.lastOrNull() ?: return@runCatching null
    MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
}.getOrNull()
