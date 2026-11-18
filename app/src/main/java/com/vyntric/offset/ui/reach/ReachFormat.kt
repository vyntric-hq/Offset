/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.Geo
import com.vyntric.offset.domain.reach.StationSetup
import com.vyntric.offset.propagation.Clearance
import com.vyntric.offset.propagation.PathGeometry
import com.vyntric.offset.propagation.Verdict
import com.vyntric.offset.propagation.itm.PropagationMode
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.theme.OffsetTheme
import java.util.Locale
import kotlin.math.roundToInt

/** Verdict colours: the same on the map, the legend, the list and the link card. */
object ReachColors {
    @Composable
    fun of(v: Verdict): Color = when (v) {
        Verdict.LIKELY -> OffsetTheme.colors.success
        Verdict.MARGINAL -> OffsetTheme.colors.warning
        Verdict.UNLIKELY -> OffsetTheme.colors.tertiaryLabel
    }

    /** Pins for links that will not work stay visible but quiet. */
    val Unlikely = Color(0xFF8E8E93)

    /**
     * The coverage ramp, as a lookup table from -10 dB to +40 dB in 0.25 dB steps. Below -6 dB
     * the map is clear; from -6 to 0 a faint amber hints at "maybe on a good day"; 0 to 10 runs
     * amber to green; above 10 the green deepens a little as the margin grows. Kept light so the
     * base map stays readable underneath; the contours carry the boundaries.
     */
    class Ramp(likely: Color, marginal: Color) {
        private val table = IntArray(201) { i -> color(-10.0 + i * 0.25, likely, marginal) }
        private val likelyEdge = likely.copy(alpha = 0.8f).toArgb()
        private val marginalEdge = marginal.copy(alpha = 0.85f).toArgb()

        fun argb(marginDb: Double): Int {
            if (marginDb < -6.0) return 0
            val i = ((marginDb + 10.0) * 4).roundToInt().coerceIn(0, table.size - 1)
            return table[i]
        }

        /**
         * Colours [margins] (a width x height grid, NaN for nothing) and traces the two verdict
         * boundaries as one pixel contours: green where likely ends, amber where marginal ends.
         */
        fun paint(raw: FloatArray, width: Int, height: Int): IntArray {
            // Terrain makes the field speckled at pixel scale; contours of that read as static.
            // Two passes of a small box blur keep the shape and drop the noise.
            val margins = smooth(smooth(raw, width, height, 3), width, height, 2)
            fun band(m: Float) = when {
                m.isNaN() -> -1
                m >= Verdict.LIKELY_DB -> 2
                m >= 0f -> 1
                else -> 0
            }
            val out = IntArray(margins.size) { i -> margins[i].let { if (it.isNaN()) 0 else argb(it.toDouble()) } }
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                val b = band(margins[i])
                if (b < 0) continue
                val right = if (x + 1 < width) band(margins[i + 1]) else b
                val down = if (y + 1 < height) band(margins[i + width]) else b
                val edge = listOf(right, down).filter { it >= 0 && it != b }.minOfOrNull { minOf(it, b) } ?: continue
                out[i] = if (edge >= 1) likelyEdge else marginalEdge
            }
            return out
        }

        /** Box blur of radius [r] that ignores NaN (no coverage) and keeps it where it was. */
        private fun smooth(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(src.size)
            val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f; var n = 0
                for (dx in -r..r) { val v = src[y * w + (x + dx).coerceIn(0, w - 1)]; if (!v.isNaN()) { sum += v; n++ } }
                tmp[y * w + x] = if (n == 0) Float.NaN else sum / n
            }
            for (y in 0 until h) for (x in 0 until w) {
                if (src[y * w + x].isNaN()) { out[y * w + x] = Float.NaN; continue }
                var sum = 0f; var n = 0
                for (dy in -r..r) { val v = tmp[(y + dy).coerceIn(0, h - 1) * w + x]; if (!v.isNaN()) { sum += v; n++ } }
                out[y * w + x] = if (n == 0) Float.NaN else sum / n
            }
            return out
        }

        private fun color(m: Double, likely: Color, marginal: Color): Int {
            fun mix(a: Color, b: Color, t: Float, alpha: Float) =
                Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, alpha).toArgb()
            return when {
                m < -6.0 -> 0
                m < 0.0 -> mix(marginal, marginal, 0f, (0.16 * (m + 6.0) / 6.0).toFloat())
                m < Verdict.LIKELY_DB -> mix(marginal, likely, (m / Verdict.LIKELY_DB).toFloat(), 0.34f - 0.06f * (m / Verdict.LIKELY_DB).toFloat())
                else -> mix(likely, likely, 0f, (0.24 + 0.16 * ((m - Verdict.LIKELY_DB) / 30.0).coerceAtMost(1.0)).toFloat())
            }
        }
    }
}

object ReachFmt {
    fun verdict(v: Verdict) = when (v) {
        Verdict.LIKELY -> "Likely"
        Verdict.MARGINAL -> "Marginal"
        Verdict.UNLIKELY -> "Unlikely"
    }

    fun margin(db: Double): String = (if (db >= 0) "+" else "−") + String.format(Locale.US, "%.0f dB", kotlin.math.abs(db))

    fun height(m: Double, units: Units): String = when (units) {
        Units.MILES -> String.format(Locale.US, "%.0f ft", m / 0.3048)
        Units.KILOMETERS -> String.format(Locale.US, if (m < 10) "%.1f m" else "%.0f m", m)
    }

    /** Parses a height typed in the user's units, returning metres. */
    fun parseHeight(text: String, units: Units): Double? = text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it > 0 && it < 10_000 }?.let { if (units == Units.MILES) it * 0.3048 else it }

    fun heightInput(m: Double, units: Units): String = when (units) {
        Units.MILES -> String.format(Locale.US, "%.0f", m / 0.3048)
        Units.KILOMETERS -> String.format(Locale.US, "%.1f", m).removeSuffix(".0")
    }

    fun heightUnit(units: Units) = if (units == Units.MILES) "ft" else "m"

    fun distance(m: Double, units: Units) = Fmt.distance(m / 1000.0, units)

    fun watts(w: Double): String = if (w >= 1) String.format(Locale.US, "%.0f W", w) else String.format(Locale.US, "%.1f W", w)

    fun bearing(deg: Double) = "${deg.roundToInt() % 360}° ${Geo.compass(deg)}"

    /** "UV-5R · Stock rubber duck · 4 W · 5 ft" */
    fun station(setup: StationSetup, units: Units): String = listOf(
        setup.radio.name.removePrefix("Baofeng ").removePrefix("Radtel ").removePrefix("Yaesu ").removePrefix("Kenwood ").removePrefix("Icom "),
        setup.antenna.name,
        watts(setup.station.watts.coerceAtMost(setup.radio.maxWatts.values.maxOrNull() ?: setup.station.watts)),
        height(setup.station.heightM, units),
    ).joinToString(" · ")

    fun mode(m: PropagationMode) = when (m) {
        PropagationMode.LINE_OF_SIGHT -> "line of sight"
        PropagationMode.DIFFRACTION -> "over terrain (diffraction)"
        PropagationMode.TROPOSCATTER -> "troposcatter"
    }

    /** One sentence about what the terrain does to the path. */
    fun clearance(g: PathGeometry, units: Units): String {
        val at = distance(g.worstDistanceM, units)
        return when (g.clearance) {
            Clearance.CLEAR -> "Clear line of sight, with the Fresnel zone open."
            Clearance.GRAZING -> "Line of sight, but terrain $at out grazes the signal path."
            Clearance.BLOCKED -> "Terrain $at out blocks the line of sight by ${height(g.worstIntrusionM, units)}. The signal has to bend over it."
        }
    }
}
