/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.ui.theme.OffsetTheme

/** Two or three letters naming a repeater source, for badges. */
val RepeaterSource.badge: String
    get() = when (this) {
        RepeaterSource.REPEATERBOOK -> "RB"
        RepeaterSource.HEARHAM -> "HH"
        RepeaterSource.CSV -> "CSV"
        RepeaterSource.IRLP -> "IRLP"
        RepeaterSource.OPENSTREETMAP -> "OSM"
    }

@Composable
private fun RepeaterSource.badgeColor(): Color = when (this) {
    RepeaterSource.REPEATERBOOK -> OffsetTheme.colors.warning
    RepeaterSource.HEARHAM -> OffsetTheme.colors.success
    else -> OffsetTheme.colors.secondaryLabel
}

/** A small tinted tag naming [source]: on result rows, and next to each source in Settings. */
@Composable
fun SourceBadge(source: RepeaterSource, modifier: Modifier = Modifier) {
    val color = source.badgeColor()
    Text(
        source.badge,
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        color = color,
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = "From ${source.displayName}" },
    )
}

/** How a source is named in the credit line. */
private val RepeaterSource.creditName: String
    get() = when (this) {
        RepeaterSource.REPEATERBOOK -> "RepeaterBook.com"
        RepeaterSource.HEARHAM -> "Hearham.com"
        RepeaterSource.CSV -> "your imported list"
        RepeaterSource.IRLP -> "the IRLP status page"
        RepeaterSource.OPENSTREETMAP -> "© OpenStreetMap contributors"
    }

/**
 * One sentence crediting [sources] in order, each linked to its site: "Data courtesy of
 * RepeaterBook.com and Hearham.com". RepeaterBook asks for exactly "Data courtesy of
 * RepeaterBook.com", so it is named first whenever it is credited.
 */
@Composable
fun CreditLine(sources: List<RepeaterSource>, style: TextStyle, modifier: Modifier = Modifier) {
    if (sources.isEmpty()) return
    val colors = OffsetTheme.colors
    val ordered = sources.sortedBy { if (it == RepeaterSource.REPEATERBOOK) 0 else 1 }
    val text = buildAnnotatedString {
        append("Data courtesy of ")
        ordered.forEachIndexed { i, src ->
            if (i > 0) append(if (i == ordered.lastIndex) " and " else ", ")
            if (src.url.isEmpty()) {
                append(src.creditName)
            } else {
                withLink(LinkAnnotation.Url(src.url, TextLinkStyles(SpanStyle(color = colors.accent)))) { append(src.creditName) }
            }
        }
    }
    Text(text, style = style, color = colors.secondaryLabel, modifier = modifier)
}
