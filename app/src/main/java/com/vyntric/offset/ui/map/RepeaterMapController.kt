/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.map

import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import com.vyntric.offset.domain.Repeater
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Owns one MapLibre MapView showing repeaters as clustered, band coloured pins with callsign
 * labels and a "You" marker. Data and style can be set at any time; they are applied once the
 * map and style are ready, and again after a style switch (which drops custom layers).
 */
class RepeaterMapController(context: Context, userAgent: String) {
    data class Palette(val twoMeter: Int, val seventyCm: Int, val gmrs: Int, val other: Int, val me: Int, val dark: Boolean)

    private data class Data(val repeaters: List<Repeater>, val me: Pair<Double, Double>?, val nationwide: Boolean, val meLabel: String)

    val view: MapView

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleUrl: String? = null
    private var palette: Palette? = null
    private var data: Data? = null
    private var fittedFor: Data? = null
    private var started = false
    private var resumed = false
    private var destroyed = false

    /** Called with a repeater id when its pin or label is tapped. */
    var onRepeaterTap: (String) -> Unit = {}

    init {
        MapLibre.getInstance(context.applicationContext)
        installUserAgent(userAgent)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isLogoEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isAttributionEnabled = true
                attributionGravity = Gravity.BOTTOM or Gravity.END
            }
            m.addOnMapClickListener { handleClick(it) }
            loadStyle()
        }
    }

    fun setStyle(url: String, palette: Palette) {
        val changed = url != styleUrl || palette != this.palette
        styleUrl = url
        this.palette = palette
        if (changed) loadStyle()
    }

    fun setData(repeaters: List<Repeater>, me: Pair<Double, Double>?, nationwide: Boolean, meLabel: String = "You") {
        val next = Data(repeaters, me, nationwide, meLabel)
        if (next == data) return
        data = next
        render()
    }

    /** Moves the camera back to fit the results (or the whole country). */
    fun recenter() {
        fittedFor = null
        fit()
    }

    fun onStart() { if (!started && !destroyed) { view.onStart(); started = true } }
    fun onResume() { if (!resumed && started) { view.onResume(); resumed = true } }
    fun onPause() { if (resumed) { view.onPause(); resumed = false } }
    fun onStop() { if (started) { onPause(); view.onStop(); started = false } }
    fun onDestroy() {
        if (destroyed) return
        onStop()
        view.onDestroy()
        destroyed = true
    }

    private fun loadStyle() {
        val m = map ?: return
        val url = styleUrl ?: return
        style = null
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            style = s
            addLayers(s)
            render()
        }
    }

    private fun addLayers(s: Style) {
        val p = palette ?: return
        s.addSource(GeoJsonSource(SRC, FeatureCollection.fromFeatures(emptyList()), GeoJsonOptions().withCluster(true).withClusterMaxZoom(8).withClusterRadius(48)))
        s.addSource(GeoJsonSource(SRC_ME, FeatureCollection.fromFeatures(emptyList())))
        val labelColor = if (p.dark) "#FFFFFF" else "#1C1C1E"
        val haloColor = if (p.dark) "#1C1C1E" else "#FFFFFF"
        s.addLayer(
            CircleLayer(L_CLUSTERS, SRC).withFilter(has("point_count")).withProperties(
                circleColor(color(p.twoMeter)),
                circleRadius(Expression.step(get("point_count"), Expression.literal(18f), stop(100, 22f), stop(1000, 28f))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            )
        )
        s.addLayer(
            SymbolLayer(L_COUNTS, SRC).withFilter(has("point_count")).withProperties(
                textField(Expression.toString(get("point_count_abbreviated"))),
                textFont(arrayOf(FONT)),
                textSize(13f),
                textColor("#FFFFFF"),
                textIgnorePlacement(true),
                textAllowOverlap(true),
            )
        )
        s.addLayer(
            CircleLayer(L_POINTS, SRC).withFilter(not(has("point_count"))).withProperties(
                circleColor(
                    Expression.match(get("kind"), color(p.other),
                        stop("2m", color(p.twoMeter)), stop("70cm", color(p.seventyCm)), stop("GMRS", color(p.gmrs)))
                ),
                circleRadius(7f),
                circleStrokeColor(labelColor),
                circleStrokeWidth(1.5f),
            )
        )
        s.addLayer(
            SymbolLayer(L_LABELS, SRC).withFilter(not(has("point_count"))).withProperties(
                textField(get("call")),
                textFont(arrayOf(FONT)),
                textSize(12f),
                textOffset(arrayOf(0f, 0.9f)),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textColor(labelColor),
                textHaloColor(haloColor),
                textHaloWidth(1.6f),
                textOptional(true),
            ).also { it.minZoom = 7.5f }
        )
        s.addLayer(
            CircleLayer(L_ME, SRC_ME).withProperties(circleColor(color(p.me)), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f))
        )
        s.addLayer(
            SymbolLayer(L_ME_LABEL, SRC_ME).withProperties(
                textField(get("label")), textFont(arrayOf(FONT)), textSize(13f), textOffset(arrayOf(0f, -1.0f)),
                textAnchor(Property.TEXT_ANCHOR_BOTTOM), textColor(labelColor), textHaloColor(haloColor), textHaloWidth(1.6f),
                textAllowOverlap(true), textIgnorePlacement(true),
            )
        )
    }

    private fun render() {
        val s = style ?: return
        val d = data ?: return
        val features = d.repeaters.mapNotNull { r ->
            val lat = r.latitude ?: return@mapNotNull null
            val lon = r.longitude ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("id", r.id)
                addStringProperty("call", r.callsign)
                addStringProperty("kind", r.kind)
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(FeatureCollection.fromFeatures(features))
        val me = d.me?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.second, it.first)).apply { addStringProperty("label", d.meLabel) }) }.orEmpty()
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(FeatureCollection.fromFeatures(me))
        if (fittedFor != d) fit()
    }

    private fun fit() {
        val m = map ?: return
        val d = data ?: return
        fittedFor = d
        if (d.nationwide) {
            val center = d.me?.let { LatLng(it.first, it.second) } ?: LatLng(39.5, -98.35)
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(center, if (d.me != null) 5.0 else 3.2))
            return
        }
        val points = d.repeaters.mapNotNull { r -> r.latitude?.let { lat -> r.longitude?.let { LatLng(lat, it) } } } +
            listOfNotNull(d.me?.let { LatLng(it.first, it.second) })
        when {
            points.isEmpty() -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.5, -98.35), 3.2))
            points.distinct().size == 1 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 11.0))
            else -> {
                val bounds = LatLngBounds.Builder().includes(points).build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            }
        }
    }

    private fun handleClick(point: LatLng): Boolean {
        val m = map ?: return false
        val screen = m.projection.toScreenLocation(point)
        val box = RectF(screen.x - TAP, screen.y - TAP, screen.x + TAP, screen.y + TAP)
        m.queryRenderedFeatures(box, L_POINTS, L_LABELS).firstOrNull()?.getStringProperty("id")?.let {
            onRepeaterTap(it)
            return true
        }
        if (m.queryRenderedFeatures(box, L_CLUSTERS).isNotEmpty()) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(point, m.cameraPosition.zoom + 2))
            return true
        }
        return false
    }

    companion object {
        const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        private const val SRC = "repeaters"
        private const val SRC_ME = "me"
        private const val L_CLUSTERS = "repeater-clusters"
        private const val L_COUNTS = "repeater-cluster-counts"
        private const val L_POINTS = "repeater-points"
        private const val L_LABELS = "repeater-labels"
        private const val L_ME = "me-point"
        private const val L_ME_LABEL = "me-label"
        private const val FONT = "Noto Sans Bold"
        private const val TAP = 24f
        private var userAgentInstalled = false

        /** MapLibre fetches tiles with its own OkHttp client; give it an honest User-Agent. */
        @Synchronized
        private fun installUserAgent(ua: String) {
            if (userAgentInstalled) return
            HttpRequestUtil.setOkHttpClient(
                OkHttpClient.Builder()
                    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build()) }
                    .build()
            )
            userAgentInstalled = true
        }
    }
}
