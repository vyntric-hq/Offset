/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme

/** Position of a cell inside an inset group, for corner rounding and separators. */
enum class CellPosition { ONLY, FIRST, MIDDLE, LAST;
    companion object {
        fun of(index: Int, count: Int) = when {
            count == 1 -> ONLY
            index == 0 -> FIRST
            index == count - 1 -> LAST
            else -> MIDDLE
        }
    }
}

private fun shapeFor(p: CellPosition): Shape {
    val r = Dimens.groupCorner
    return when (p) {
        CellPosition.ONLY -> RoundedCornerShape(r)
        CellPosition.FIRST -> RoundedCornerShape(topStart = r, topEnd = r)
        CellPosition.LAST -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        CellPosition.MIDDLE -> RoundedCornerShape(0.dp)
    }
}

/** Places one cell of an inset grouped list: gutter, rounded ends, surface background. */
@Composable
fun GroupedCell(position: CellPosition, modifier: Modifier = Modifier, separatorInset: Dp = Dimens.gutter, content: @Composable () -> Unit) {
    val colors = OffsetTheme.colors
    Box(modifier.padding(horizontal = Dimens.gutter).clip(shapeFor(position)).background(colors.surface)) {
        content()
        if (position == CellPosition.FIRST || position == CellPosition.MIDDLE) {
            Spacer(
                Modifier.align(Alignment.BottomStart).padding(start = separatorInset).fillMaxWidth()
                    .height(Dimens.hairline).background(colors.separator)
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    // The trailing actions sit at the end of the title's line, or move to their own line together
    // when both do not fit, so the title never breaks between words.
    FlowRow(
        modifier.fillMaxWidth().padding(start = Dimens.gutter * 2, end = Dimens.gutter * 2, top = Dimens.sectionGap, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Text(text.uppercase(), style = OffsetTheme.type.footnote, color = OffsetTheme.colors.secondaryLabel)
        if (trailing != null) {
            // A flow too, so at large font sizes the actions wrap among themselves instead of squeezing.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), itemVerticalAlignment = Alignment.Bottom, content = trailing)
        }
    }
}

@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OffsetTheme.type.footnote,
        color = OffsetTheme.colors.secondaryLabel,
        modifier = modifier.fillMaxWidth().padding(start = Dimens.gutter * 2, end = Dimens.gutter * 2, top = 6.dp),
    )
}

/** Builder for a non lazy inset group. */
class SectionScope {
    internal val cells = mutableListOf<Pair<Dp, @Composable () -> Unit>>()
    fun cell(separatorInset: Dp = Dimens.gutter, content: @Composable () -> Unit) {
        cells += separatorInset to content
    }
}

/** A short inset grouped section (settings style). Use [groupedItems] for long lists. */
@Composable
fun InsetSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    build: SectionScope.() -> Unit,
) {
    val scope = SectionScope().apply(build)
    Column(modifier.fillMaxWidth()) {
        if (header != null) SectionHeader(header, trailing = headerTrailing) else Spacer(Modifier.height(Dimens.sectionGap))
        scope.cells.forEachIndexed { i, (inset, content) ->
            GroupedCell(CellPosition.of(i, scope.cells.size), separatorInset = inset) { content() }
        }
        if (footer != null) SectionFooter(footer)
    }
}

/** Lazy inset grouped rows for long lists. */
fun <T> LazyListScope.groupedItems(
    items: List<T>,
    key: (T) -> Any,
    separatorInset: Dp = Dimens.gutter,
    itemModifier: @Composable (T) -> Modifier = { Modifier },
    content: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
        GroupedCell(CellPosition.of(index, items.size), itemModifier(item).animateItem(), separatorInset) { content(item) }
    }
}

/** The standard row: title, optional subtitle, trailing value, chevron when navigable. */
@Composable
fun CellRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    titleColor: Color = OffsetTheme.colors.label,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = OffsetTheme.colors
    val type = OffsetTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.rowMinHeight)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.gutter, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = type.body, color = if (enabled) titleColor else colors.tertiaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = type.subhead, color = colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            // Fills the space the title leaves, so the value sits against the trailing edge.
            Text(value, style = type.body, color = colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f))
        }
        trailing?.invoke()
        if (chevron) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(22.dp))
        }
    }
}

/** A row that is a single action, accent or destructive. */
@Composable
fun ButtonRow(text: String, destructive: Boolean = false, enabled: Boolean = true, centered: Boolean = false, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Text(
        text,
        style = OffsetTheme.type.body,
        color = when {
            !enabled -> colors.tertiaryLabel
            destructive -> colors.destructive
            else -> colors.accent
        },
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.rowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimens.gutter, vertical = 13.dp),
    )
}

/** A row with a toggle; the whole row is the switch for touch and accessibility. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.rowMinHeight)
            .toggleableRow(checked, enabled) { haptics.toggle(it); onCheckedChange(it) }
            .padding(horizontal = Dimens.gutter, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OffsetTheme.type.body, color = if (enabled) OffsetTheme.colors.label else OffsetTheme.colors.tertiaryLabel)
            if (subtitle != null) Text(subtitle, style = OffsetTheme.type.subhead, color = OffsetTheme.colors.secondaryLabel)
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked = checked, enabled = enabled)
    }
}

/** A labelled single line text field styled as a cell. */
@Composable
fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    error: String? = null,
    labelWidth: Dp = 110.dp,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = OffsetTheme.colors
    val type = OffsetTheme.type
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = type.body, color = colors.label, modifier = Modifier.width(labelWidth))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = type.body, color = colors.tertiaryLabel)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = type.body.copy(color = colors.label),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = keyboardOptions,
                    visualTransformation = visualTransformation,
                    keyboardActions = keyboardActions,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
            }
        }
        if (error != null) Text(error, style = type.footnote, color = colors.destructive, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Width of a channel number column: [minWidth], or wider when the largest number shown
 * ([capacity]) needs it at the current font size, so every row lines up and no number wraps.
 */
@Composable
fun rememberChannelNumberWidth(capacity: Int, minWidth: Dp): Dp {
    val style = OffsetTheme.type.footnote
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(capacity, minWidth, style, density) {
        val digits = with(density) { measurer.measure(capacity.toString(), style).size.width.toDp() }
        maxOf(minWidth, Dimens.gutter + digits + 8.dp)
    }
}
