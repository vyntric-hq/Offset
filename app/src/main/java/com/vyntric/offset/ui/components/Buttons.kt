/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme

/** The one filled button style: accent (or destructive) with a 12dp radius. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    busy: Boolean = false,
) {
    val colors = OffsetTheme.colors
    val bg = when {
        !enabled -> colors.fill
        destructive -> colors.destructive
        else -> colors.accent
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimens.buttonHeight)
            .clip(RoundedCornerShape(Dimens.groupCorner))
            .background(bg)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = colors.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(text, style = OffsetTheme.type.headline, color = if (enabled) colors.onAccent else colors.tertiaryLabel)
        }
    }
}

/** Thin rounded progress bar in the accent colour. */
@Composable
fun ThinProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val colors = OffsetTheme.colors
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.fill)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.accent))
        }
    }
}

/** Empty, loading and error states share one calm layout. */
@Composable
fun StateMessage(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = OffsetTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            loading -> CircularProgressIndicator(color = colors.secondaryLabel, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            icon != null -> Icon(icon, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(title, style = OffsetTheme.type.headline, color = colors.label, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = OffsetTheme.type.subhead, color = colors.secondaryLabel, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = OffsetTheme.type.body,
                color = colors.accent,
                modifier = Modifier.clickable(role = Role.Button, onClick = onAction).padding(12.dp),
            )
        }
    }
}

/** A small pill label, for example "Factory" or "Backup". */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(OffsetTheme.colors.fill).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = OffsetTheme.type.caption, color = OffsetTheme.colors.secondaryLabel)
    }
}
