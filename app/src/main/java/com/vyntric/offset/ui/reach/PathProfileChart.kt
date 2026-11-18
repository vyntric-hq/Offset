/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.vyntric.offset.data.Units
import com.vyntric.offset.propagation.Clearance
import com.vyntric.offset.propagation.PathGeometry
import com.vyntric.offset.ui.theme.OffsetTheme

/**
 * The path between two stations seen side on: terrain (raised by the earth's bulge), both masts,
 * the line of sight coloured by how clear it is, the first Fresnel zone around it, and the worst
 * obstruction marked. Heights are exaggerated to fit; distances are true.
 */
@Composable
fun PathProfileChart(geometry: PathGeometry, startLabel: String, endLabel: String, units: Units, modifier: Modifier = Modifier) {
    val colors = OffsetTheme.colors
    val measurer = rememberTextMeasurer()
    val caption = OffsetTheme.type.caption.copy(color = colors.secondaryLabel)
    val terrainTop = if (colors.isDark) Color(0xFF8A7F6A) else Color(0xFF9C8A68)
    val lineColor = when (geometry.clearance) {
        Clearance.CLEAR -> colors.success
        Clearance.GRAZING -> colors.warning
        Clearance.BLOCKED -> colors.destructive
    }
    val description = remember(geometry, units) {
        "Terrain profile over ${ReachFmt.distance(geometry.profile.lengthM, units)}. " + ReachFmt.clearance(geometry, units)
    }
    val n = geometry.profile.intervals
    val (low, high) = remember(geometry) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0..n) {
            lo = minOf(lo, geometry.apparentTerrainM[i])
            hi = maxOf(hi, geometry.apparentTerrainM[i], geometry.lineOfSightM(i) + geometry.fresnelM[i])
        }
        hi = maxOf(hi, geometry.startAntennaM, geometry.endAntennaM)
        val pad = (hi - lo).coerceAtLeast(20.0) * 0.12
        (lo - pad) to (hi + pad)
    }

    Canvas(modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = description }) {
        val left = 4.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        fun x(i: Int) = left + (right - left) * i / n
        fun y(m: Double) = (bottom - (bottom - top) * ((m - low) / (high - low))).toFloat()

        // First Fresnel zone: a soft neutral band around the line of sight, kept inside the chart.
        val zone = Path().apply {
            moveTo(x(0), y(geometry.lineOfSightM(0)))
            for (i in 0..n) lineTo(x(i), y(geometry.lineOfSightM(i) + geometry.fresnelM[i]))
            for (i in n downTo 0) lineTo(x(i), y(geometry.lineOfSightM(i) - geometry.fresnelM[i]))
            close()
        }
        clipRect(left, top, right, bottom) { drawPath(zone, colors.accent.copy(alpha = 0.07f)) }

        // Terrain, filled to the bottom with a fade.
        val ground = Path().apply {
            moveTo(left, bottom)
            for (i in 0..n) lineTo(x(i), y(geometry.apparentTerrainM[i]))
            lineTo(right, bottom)
            close()
        }
        drawPath(ground, Brush.verticalGradient(listOf(terrainTop.copy(alpha = 0.85f), terrainTop.copy(alpha = 0.25f)), startY = top, endY = bottom))
        val ridge = Path().apply {
            moveTo(x(0), y(geometry.apparentTerrainM[0]))
            for (i in 1..n) lineTo(x(i), y(geometry.apparentTerrainM[i]))
        }
        drawPath(ridge, terrainTop, style = Stroke(1.5.dp.toPx()))

        // Masts.
        val mast = colors.label.copy(alpha = 0.7f)
        drawLine(mast, Offset(x(0), y(geometry.apparentTerrainM[0])), Offset(x(0), y(geometry.startAntennaM)), 2.dp.toPx(), StrokeCap.Round)
        drawLine(mast, Offset(x(n), y(geometry.apparentTerrainM[n])), Offset(x(n), y(geometry.endAntennaM)), 2.dp.toPx(), StrokeCap.Round)

        // Line of sight.
        drawLine(lineColor, Offset(x(0), y(geometry.startAntennaM)), Offset(x(n), y(geometry.endAntennaM)), 2.5.dp.toPx(), StrokeCap.Round)
        drawCircle(colors.accent, 4.5.dp.toPx(), Offset(x(0), y(geometry.startAntennaM)))
        drawCircle(colors.accent, 4.5.dp.toPx(), Offset(x(n), y(geometry.endAntennaM)))

        // The worst point, when it matters.
        if (geometry.clearance != Clearance.CLEAR) {
            val w = geometry.worstIndex
            val px = x(w)
            drawLine(lineColor, Offset(px, y(geometry.apparentTerrainM[w])), Offset(px, y(geometry.lineOfSightM(w))), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            drawCircle(lineColor, 4.dp.toPx(), Offset(px, y(geometry.apparentTerrainM[w])))
        }

        // Labels: who is where, and the distance.
        val startText = measurer.measure(startLabel, caption)
        drawText(startText, topLeft = Offset(left, 0f))
        val endText = measurer.measure(endLabel, caption)
        drawText(endText, topLeft = Offset(right - endText.size.width, 0f))
        val dist = measurer.measure(ReachFmt.distance(geometry.profile.lengthM, units), caption)
        drawText(dist, topLeft = Offset((left + right - dist.size.width) / 2, size.height - dist.size.height))
    }
}
