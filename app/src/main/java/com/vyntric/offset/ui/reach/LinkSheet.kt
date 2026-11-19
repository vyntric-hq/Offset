/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.reach.FarStation
import com.vyntric.offset.domain.reach.ReachTarget
import com.vyntric.offset.propagation.Direction
import com.vyntric.offset.propagation.LinkBudget
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.DetailRow
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.components.SegmentedControl
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme
import java.util.Locale
import kotlin.math.pow

/** One link worked out both ways, the terrain between, and what was assumed about the far end. */
@Composable
fun LinkSheet(
    target: ReachTarget,
    direction: Direction,
    stationName: String,
    units: Units,
    onDismiss: () -> Unit,
    onAddToStaging: () -> Unit,
    onEditAssumptions: () -> Unit,
    onFarChange: (FarStation) -> Unit,
    onEditPlace: () -> Unit,
    onRemovePlace: () -> Unit,
) {
    val check = target.check
    OffsetSheet(onDismiss = onDismiss, title = target.name, trailingAction = { BarAction("Done", onClick = onDismiss) }) {
        val subtitle = listOfNotNull(
            target.repeater?.let { "${Fmt.mhz(it.outputHz)} MHz" },
            ReachFmt.distance(check.distanceM, units),
            ReachFmt.bearing(check.bearingDeg),
        ).joinToString(" · ")
        Text(subtitle, style = OffsetTheme.type.subhead, color = OffsetTheme.colors.secondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Row(Modifier.fillMaxWidth().padding(Dimens.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VerdictTile("They hear you", Icons.Rounded.Campaign, check.talk, check.pathLoss.modelled, emphasized = direction == Direction.TALK, Modifier.weight(1f))
            VerdictTile("You hear them", Icons.Rounded.Hearing, check.listen, check.pathLoss.modelled, emphasized = direction == Direction.LISTEN, Modifier.weight(1f))
        }

        Box(Modifier.padding(horizontal = Dimens.gutter).clip(RoundedCornerShape(Dimens.groupCorner)).background(OffsetTheme.colors.surface).padding(12.dp)) {
            PathProfileChart(check.geometry, stationName, target.name, units)
        }
        SectionFooter(listOfNotNull(ReachFmt.clearance(check.geometry, units), target.terrainNote).joinToString(" "))

        InsetSection(header = "Path") {
            if (check.pathLoss.modelled) {
                cell { DetailRow("Path loss", String.format(Locale.US, "%.0f dB", check.pathLoss.lossDb)) }
            } else {
                cell { DetailRow("Path loss", "Beyond the model's range") }
            }
            if (check.pathLoss.modelled) {
                cell { DetailRow("Your signal there", String.format(Locale.US, "%.0f dBm", check.talk.receivedDbm)) }
                cell { DetailRow("Their signal here", String.format(Locale.US, "%.0f dBm", check.listen.receivedDbm)) }
            }
        }

        val far = target.far
        InsetSection(
            header = if (target.repeater != null) "Assumed for every repeater" else "Assumed for ${target.name}",
            footer = if (target.repeater != null) {
                "Directories do not list a repeater's power or antenna height, so these are typical values. Change them if you know this machine."
            } else "Offset does not know their station, so it assumes a typical one of this type.",
        ) {
            if (target.place != null) {
                cell {
                    Box(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                        val types = listOf(FarStation.HANDHELD, FarStation.MOBILE, FarStation.BASE)
                        SegmentedControl(types.map { it.label }, types.indexOf(target.place.far), { onFarChange(types[it]) })
                    }
                }
            }
            cell { DetailRow("Antenna height", ReachFmt.height(far.heightM, units)) }
            cell { DetailRow("Power", ReachFmt.watts(10.0.pow((far.txPowerDbm - 30.0) / 10.0))) }
            cell { DetailRow("Antenna gain", String.format(Locale.US, "%.1f dBi", far.antennaGainDbi)) }
            cell { DetailRow("Feedline loss", String.format(Locale.US, "%.1f dB", far.lineLossDb)) }
            cell { DetailRow("Receiver sensitivity", String.format(Locale.US, "%.0f dBm", far.sensitivityDbm)) }
            if (target.repeater != null) cell { ButtonRow("Change assumptions", onClick = onEditAssumptions) }
        }

        if (target.repeater != null) {
            Column(Modifier.padding(Dimens.gutter)) { PrimaryButton("Add to staging", onClick = onAddToStaging) }
        }
        if (target.place != null) {
            InsetSection {
                cell { ButtonRow("Edit place", onClick = onEditPlace) }
                cell { ButtonRow("Remove place", destructive = true, onClick = onRemovePlace) }
            }
        }
        SectionFooter("An estimate from the Longley-Rice terrain model at median conditions. Buildings and trees are not modelled; check on the air.")
    }
}

@Composable
private fun VerdictTile(title: String, icon: ImageVector, budget: LinkBudget, modelled: Boolean, emphasized: Boolean, modifier: Modifier = Modifier) {
    val colors = OffsetTheme.colors
    val tint = ReachColors.of(budget.verdict)
    Column(
        modifier
            .clip(RoundedCornerShape(Dimens.groupCorner))
            .background(colors.surface)
            .then(if (emphasized) Modifier.border(2.dp, tint, RoundedCornerShape(Dimens.groupCorner)) else Modifier)
            .padding(14.dp)
            .semantics { contentDescription = "$title: ${ReachFmt.verdict(budget.verdict)}" + if (modelled) ", ${ReachFmt.margin(budget.marginDb)} margin" else "" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(16.dp))
            Text(title, style = OffsetTheme.type.footnote, color = colors.secondaryLabel)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
            Text(ReachFmt.verdict(budget.verdict), style = OffsetTheme.type.title, color = colors.label)
        }
        val detail = when {
            !modelled -> "No usable path at this distance"
            budget.marginDb >= 0 -> "${ReachFmt.margin(budget.marginDb)} above the receiver's threshold"
            else -> "${String.format(Locale.US, "%.0f", -budget.marginDb)} dB below the receiver's threshold"
        }
        Text(detail, style = OffsetTheme.type.caption, color = colors.secondaryLabel)
    }
}
