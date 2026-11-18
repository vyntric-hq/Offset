/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import com.vyntric.offset.data.TerrainRepository
import com.vyntric.offset.propagation.Earth
import com.vyntric.offset.propagation.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.hillshadeExaggeration
import org.maplibre.android.style.layers.PropertyFactory.hillshadeHighlightColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.rasterResampling
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
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * The Reach map: hillshade, the coverage raster, a beam wedge, verdict-coloured pins for
 * repeaters and places, a line to the selected one, and the station. Everything can be set before
 * the map is ready and is reapplied after a style switch (which drops custom layers).
 */
class ReachMapController(context: Context) {
    /** Pin colours by verdict, and the station colour. */
    data class Palette(val likely: Int, val marginal: Int, val unlikely: Int, val station: Int, val accent: Int, val dark: Boolean)

    /** One pin. [kind] is "repeater" or "place"; [verdict] is "likely", "marginal" or "unlikely". */
    data class Pin(val key: String, val name: String, val point: GeoPoint, val kind: String, val verdict: String)

    data class Beam(val aimDeg: Double, val widthDeg: Double, val lengthM: Double)

    val view: MapView

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleUrl: String? = null
    private var palette: Palette? = null

    private var coverage: Pair<Bitmap, Pair<GeoPoint, GeoPoint>>? = null
    private var pins: List<Pin> = emptyList()
    private var station: Pair<GeoPoint, String>? = null
    private var beam: Beam? = null
    private var selected: String? = null
    private var fittedOrigin: GeoPoint? = null
    private var fitRadiusM: Double = 30_000.0
    private var started = false
    private var resumed = false
    private var destroyed = false

    var onPinTap: (String) -> Unit = {}
    var onLongPress: (GeoPoint) -> Unit = {}
    var onEmptyTap: () -> Unit = {}

    init {
        MapLibre.getInstance(context.applicationContext)
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
            m.addOnMapLongClickListener { onLongPress(GeoPoint(it.latitude, it.longitude)); true }
            loadStyle()
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** MapLibre must only be touched on the UI thread; callers may be anywhere. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { if (!destroyed) block() }
    }

    fun setStyle(url: String, palette: Palette) = onMain {
        val changed = url != styleUrl || palette != this.palette
        styleUrl = url
        this.palette = palette
        if (changed) loadStyle()
    }

    fun setCoverage(bitmap: Bitmap?, bounds: Pair<GeoPoint, GeoPoint>?) = onMain {
        coverage = if (bitmap != null && bounds != null) bitmap to bounds else null
        renderCoverage()
    }

    fun setStation(point: GeoPoint?, label: String, beam: Beam?, radiusM: Double) = onMain {
        station = point?.let { it to label }
        this.beam = beam
        fitRadiusM = radiusM
        renderStation()
        if (point != null && point != fittedOrigin) fit()
    }

    fun setPins(pins: List<Pin>, selected: String?) = onMain {
        this.pins = pins
        this.selected = selected
        renderPins()
    }

    /** Back to the station with the whole coverage area in view. */
    fun recenter() = onMain { fit() }

    private var padTop = 0
    private var padBottom = 0
    private var padSides = 0

    /** Room the floating cards take, so framing keeps the coverage clear of them. */
    fun setFramePadding(top: Int, bottom: Int, sides: Int) = onMain {
        padTop = top; padBottom = bottom; padSides = sides
    }

    /** Frames [radiusM] around the station: the coverage the user can actually use. */
    fun frame(radiusM: Double) = onMain {
        fitRadiusM = radiusM.coerceAtLeast(3_000.0) * 1.08
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
            renderCoverage()
            renderStation()
            renderPins()
            if (station?.first != fittedOrigin) fit()
        }
    }

    private fun addLayers(s: Style) {
        val p = palette ?: return
        val labelColor = if (p.dark) "#FFFFFF" else "#1C1C1E"
        val haloColor = if (p.dark) "#1C1C1E" else "#FFFFFF"
        // Custom layers go under the base map's labels so place names stay readable.
        val firstLabel = s.layers.firstOrNull { it is SymbolLayer }?.id

        fun below(layer: org.maplibre.android.style.layers.Layer) {
            if (firstLabel != null) s.addLayerBelow(layer, firstLabel) else s.addLayer(layer)
        }

        val dem = TileSet("2.1.0", "${TerrainRepository.DEFAULT_URL}/{z}/{x}/{y}.png").apply {
            encoding = "terrarium"
            maxZoom = 12f
            attribution = "Terrain: USGS 3DEP, SRTM, GMTED2010 (AWS Terrain Tiles)"
        }
        s.addSource(RasterDemSource(SRC_DEM, dem, 256))
        below(
            HillshadeLayer(L_HILLSHADE, SRC_DEM).withProperties(
                hillshadeExaggeration(if (p.dark) 0.35f else 0.25f),
                hillshadeShadowColor(arrayOf(if (p.dark) "#000000" else "#5A4A3A")),
                hillshadeHighlightColor(arrayOf(if (p.dark) "#3A3A3C" else "#FFFFFF")),
            )
        )

        s.addSource(GeoJsonSource(SRC_BEAM, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_LINK, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_PINS, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_STATION, FeatureCollection.fromFeatures(emptyList())))

        below(FillLayer(L_BEAM, SRC_BEAM).withProperties(fillColor(color(p.accent)), fillOpacity(0.10f)))
        below(LineLayer(L_BEAM_EDGE, SRC_BEAM).withProperties(lineColor(color(p.accent)), lineWidth(1.2f), lineOpacity(0.6f), lineDasharray(arrayOf(2f, 2f))))
        s.addLayer(LineLayer(L_LINK, SRC_LINK).withProperties(lineColor(color(p.accent)), lineWidth(2.5f), lineDasharray(arrayOf(1.5f, 1.5f))))

        val verdictColor = Expression.match(get("verdict"), color(p.unlikely),
            stop("likely", color(p.likely)), stop("marginal", color(p.marginal)))
        s.addLayer(
            CircleLayer(L_SELECTED, SRC_PINS).withFilter(eq(get("selected"), literal(true))).withProperties(
                circleRadius(16f), circleColor(color(p.accent)), circleOpacity(0.25f), circleStrokeColor(color(p.accent)), circleStrokeWidth(2f),
            )
        )
        s.addLayer(
            CircleLayer(L_REPEATERS, SRC_PINS).withFilter(eq(get("kind"), literal("repeater"))).withProperties(
                circleColor(verdictColor), circleRadius(7f), circleStrokeColor(labelColor), circleStrokeWidth(1.5f),
            )
        )
        s.addLayer(
            CircleLayer(L_PLACES, SRC_PINS).withFilter(eq(get("kind"), literal("place"))).withProperties(
                circleColor(verdictColor), circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
            )
        )
        s.addLayer(
            SymbolLayer(L_REPEATER_LABELS, SRC_PINS).withFilter(eq(get("kind"), literal("repeater"))).withProperties(
                textField(get("name")), textFont(arrayOf(FONT)), textSize(11f), textOffset(arrayOf(0f, 0.9f)),
                textAnchor(Property.TEXT_ANCHOR_TOP), textColor(labelColor), textHaloColor(haloColor), textHaloWidth(1.6f), textOptional(true),
            ).also { it.minZoom = 8.5f }
        )
        s.addLayer(
            SymbolLayer(L_PLACE_LABELS, SRC_PINS).withFilter(eq(get("kind"), literal("place"))).withProperties(
                textField(get("name")), textFont(arrayOf(FONT)), textSize(13f), textOffset(arrayOf(0f, 1.1f)),
                textAnchor(Property.TEXT_ANCHOR_TOP), textColor(labelColor), textHaloColor(haloColor), textHaloWidth(1.8f),
                textAllowOverlap(true), textIgnorePlacement(true),
            )
        )
        s.addLayer(
            CircleLayer(L_STATION_HALO, SRC_STATION).withProperties(circleColor(color(p.station)), circleRadius(18f), circleOpacity(0.18f))
        )
        s.addLayer(
            CircleLayer(L_STATION, SRC_STATION).withProperties(circleColor(color(p.station)), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f))
        )
        s.addLayer(
            SymbolLayer(L_STATION_LABEL, SRC_STATION).withProperties(
                textField(get("label")), textFont(arrayOf(FONT)), textSize(13f), textOffset(arrayOf(0f, -1.3f)),
                textAnchor(Property.TEXT_ANCHOR_BOTTOM), textColor(labelColor), textHaloColor(haloColor), textHaloWidth(1.8f),
                textAllowOverlap(true), textIgnorePlacement(true),
            )
        )
    }

    private fun renderCoverage() {
        val s = style ?: return
        val c = coverage
        if (c == null) {
            s.getLayer(L_COVERAGE)?.let { s.removeLayer(it) }
            s.getSource(SRC_COVERAGE)?.let { s.removeSource(it) }
            return
        }
        val (bitmap, bounds) = c
        val (nw, se) = bounds
        val quad = LatLngQuad(
            LatLng(nw.latitude, nw.longitude), LatLng(nw.latitude, se.longitude),
            LatLng(se.latitude, se.longitude), LatLng(se.latitude, nw.longitude),
        )
        val source = s.getSourceAs<ImageSource>(SRC_COVERAGE)
        if (source == null) {
            s.addSource(ImageSource(SRC_COVERAGE, quad, bitmap))
            val layer = RasterLayer(L_COVERAGE, SRC_COVERAGE).withProperties(
                rasterOpacity(0.82f), rasterFadeDuration(0f), rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            )
            if (s.getLayer(L_BEAM) != null) s.addLayerBelow(layer, L_BEAM) else s.addLayer(layer)
        } else {
            source.setCoordinates(quad)
            source.setImage(bitmap)
        }
    }

    private fun renderStation() {
        val s = style ?: return
        val st = station
        s.getSourceAs<GeoJsonSource>(SRC_STATION)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(st?.let { (p, label) ->
                Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply { addStringProperty("label", label) }
            }))
        )
        val b = beam
        val wedge = if (st != null && b != null) {
            val o = st.first
            val steps = 24
            val ring = buildList {
                add(Point.fromLngLat(o.longitude, o.latitude))
                for (i in 0..steps) {
                    val p = Earth.destination(o, b.aimDeg - b.widthDeg / 2 + b.widthDeg * i / steps, b.lengthM)
                    add(Point.fromLngLat(p.longitude, p.latitude))
                }
                add(Point.fromLngLat(o.longitude, o.latitude))
            }
            listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
        } else emptyList()
        s.getSourceAs<GeoJsonSource>(SRC_BEAM)?.setGeoJson(FeatureCollection.fromFeatures(wedge))
        renderLink()
    }

    private fun renderPins() {
        val s = style ?: return
        val features = pins.map { pin ->
            Feature.fromGeometry(Point.fromLngLat(pin.point.longitude, pin.point.latitude)).apply {
                addStringProperty("key", pin.key)
                addStringProperty("name", pin.name)
                addStringProperty("kind", pin.kind)
                addStringProperty("verdict", pin.verdict)
                addBooleanProperty("selected", pin.key == selected)
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        renderLink()
    }

    private fun renderLink() {
        val s = style ?: return
        val from = station?.first
        val to = pins.firstOrNull { it.key == selected }?.point
        val line = if (from != null && to != null) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(from.longitude, from.latitude), Point.fromLngLat(to.longitude, to.latitude)))))
        } else emptyList()
        s.getSourceAs<GeoJsonSource>(SRC_LINK)?.setGeoJson(FeatureCollection.fromFeatures(line))
    }

    /** Frames the coverage area around the station. Remembers it did, once the map could. */
    private fun fit() {
        val m = map ?: return
        val o = station?.first ?: return
        fittedOrigin = o
        val r = fitRadiusM
        val ne = Earth.destination(Earth.destination(o, 0.0, r), 90.0, r)
        val sw = Earth.destination(Earth.destination(o, 180.0, r), 270.0, r)
        val bounds = LatLngBounds.Builder().include(LatLng(ne.latitude, ne.longitude)).include(LatLng(sw.latitude, sw.longitude)).build()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padSides, padTop, padSides, padBottom), 600)
    }

    private fun handleClick(point: LatLng): Boolean {
        val m = map ?: return false
        val screen = m.projection.toScreenLocation(point)
        val box = RectF(screen.x - TAP, screen.y - TAP, screen.x + TAP, screen.y + TAP)
        val hit = m.queryRenderedFeatures(box, L_PLACES, L_REPEATERS, L_PLACE_LABELS, L_REPEATER_LABELS).firstOrNull()?.getStringProperty("key")
        if (hit != null) {
            onPinTap(hit)
            return true
        }
        onEmptyTap()
        return false
    }

    companion object {
        private const val SRC_DEM = "reach-dem"
        private const val SRC_COVERAGE = "reach-coverage"
        private const val SRC_BEAM = "reach-beam"
        private const val SRC_LINK = "reach-link"
        private const val SRC_PINS = "reach-pins"
        private const val SRC_STATION = "reach-station"
        private const val L_HILLSHADE = "reach-hillshade"
        private const val L_COVERAGE = "reach-coverage"
        private const val L_BEAM = "reach-beam"
        private const val L_BEAM_EDGE = "reach-beam-edge"
        private const val L_LINK = "reach-link"
        private const val L_SELECTED = "reach-selected"
        private const val L_REPEATERS = "reach-repeaters"
        private const val L_PLACES = "reach-places"
        private const val L_REPEATER_LABELS = "reach-repeater-labels"
        private const val L_PLACE_LABELS = "reach-place-labels"
        private const val L_STATION_HALO = "reach-station-halo"
        private const val L_STATION = "reach-station"
        private const val L_STATION_LABEL = "reach-station-label"
        private const val FONT = "Noto Sans Bold"
        private const val TAP = 24f
    }
}
