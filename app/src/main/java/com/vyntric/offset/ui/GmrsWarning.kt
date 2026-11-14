/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.theme.Dimens

const val GMRS_WARNING =
    "GMRS channels are programmed with normal transmit settings. In the US, transmitting on GMRS requires an " +
        "FCC GMRS license, and GMRS transmitters must be certified under Part 95. The radios Offset programs are " +
        "sold for amateur use (Part 90 in some models), not certified under Part 95 for GMRS. FRS channels 8 to 14 are limited to " +
        "0.5 W. You are responsible for operating legally. Receiving is always allowed."

/** Shown once, the first time GMRS channels are added. */
@Composable
fun GmrsWarningSheet(onCancel: () -> Unit, onAccept: () -> Unit) {
    OffsetSheet(onDismiss = onCancel, title = "GMRS channels", leadingAction = { BarAction("Cancel", onClick = onCancel) }) {
        SectionFooter(GMRS_WARNING)
        Column(Modifier.padding(Dimens.gutter)) {
            PrimaryButton("I understand, add them", onClick = onAccept)
        }
    }
}
