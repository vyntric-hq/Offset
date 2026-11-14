/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vyntric.offset.ui.theme.OffsetTheme

/** Clickable with button semantics. */
fun Modifier.tappable(onClickLabel: String? = null, onClick: () -> Unit): Modifier =
    this.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)

/** Makes a whole row behave as one switch. */
fun Modifier.toggleableRow(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): Modifier =
    this.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)

/**
 * A pill switch: 51 by 31 track, white thumb, accent when on. Pass [onCheckedChange] to use it
 * standalone; inside [ToggleRow] the row owns the interaction.
 */
@Composable
fun IosSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val colors = OffsetTheme.colors
    val haptics = rememberHaptics()
    val track by animateColorAsState(if (checked) colors.accent else colors.fill, tween(160), label = "switchTrack")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "switchThumb")
    val interactive = if (onCheckedChange != null) {
        Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { haptics.toggle(it); onCheckedChange(it) }
    } else Modifier
    Box(
        modifier
            .then(interactive)
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.5f)),
    ) {
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), 0) }
                .align(Alignment.CenterStart)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** Segmented control with a sliding thumb. Each segment is a selectable tab for accessibility. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = OffsetTheme.colors
    val haptics = rememberHaptics()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.segmentTrack)
            .padding(2.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val thumbX by animateDpAsState(segment * selectedIndex.coerceAtLeast(0), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium), label = "segment")
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset { IntOffset(thumbX.roundToPx(), 0) }
                    .width(segment)
                    .fillMaxHeight()
                    .shadow(1.dp, RoundedCornerShape(7.dp))
                    .background(colors.segmentThumb, RoundedCornerShape(7.dp)),
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = selected, enabled = enabled, role = Role.Tab) {
                            if (!selected) {
                                haptics.select()
                                onSelect(i)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = OffsetTheme.type.footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (enabled) colors.label else colors.tertiaryLabel,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A labelled segmented control laid out as a cell. */
@Composable
fun SegmentedRow(label: String?, options: List<String>, selectedIndex: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (label != null) {
            Text(label, style = OffsetTheme.type.subhead, color = OffsetTheme.colors.secondaryLabel, modifier = Modifier.padding(bottom = 8.dp))
        }
        SegmentedControl(options, selectedIndex, onSelect, enabled = enabled)
    }
}
