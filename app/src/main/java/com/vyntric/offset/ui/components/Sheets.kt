/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme

/**
 * Bottom sheet for details and confirmations: grouped background, small grabber, a title bar
 * with optional leading and trailing text actions. A new [page] scrolls the content back to the top,
 * for a sheet that shows one thing after another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffsetSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    skipPartiallyExpanded: Boolean = true,
    dismissible: Boolean = true,
    page: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = skipPartiallyExpanded,
        confirmValueChange = { dismissible || it != androidx.compose.material3.SheetValue.Hidden },
    )
    val colors = OffsetTheme.colors
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = state,
        shape = RoundedCornerShape(topStart = Dimens.sheetCorner, topEnd = Dimens.sheetCorner),
        containerColor = colors.background,
        contentColor = colors.label,
        dragHandle = {
            Box(Modifier.padding(top = 6.dp, bottom = 4.dp).size(width = 36.dp, height = 5.dp).background(colors.tertiaryLabel, RoundedCornerShape(3.dp)))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (title != null || leadingAction != null || trailingAction != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) { leadingAction?.invoke() }
                    if (title != null) {
                        Text(title, style = OffsetTheme.type.headline, color = colors.label,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 88.dp).semantics { heading() })
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) { trailingAction?.invoke() }
                }
            }
            val scroll = key(page) { rememberScrollState() }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = 24.dp), content = content)
        }
    }
}

/** A key and value pair inside a sheet section. */
@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 12.dp)) {
        Text(label, style = OffsetTheme.type.body, color = OffsetTheme.colors.label, modifier = Modifier.weight(1f))
        Text(value, style = OffsetTheme.type.body, color = OffsetTheme.colors.secondaryLabel, modifier = Modifier.weight(1.4f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
