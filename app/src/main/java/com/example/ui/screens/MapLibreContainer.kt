package com.example.ui.screens

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.Stop
import com.example.ui.viewmodel.SheetPosition
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toString
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

private const val SOURCE_ID = "bus-stops-source"
private const val LAYER_CLUSTER_CIRCLE = "cluster-circle"
private const val LAYER_CLUSTER_COUNT = "cluster-count"
private const val LAYER_STOP_CIRCLE = "stop-circle"
private const val LAYER_STOP_HALO = "stop-halo"
private const val LAYER_NEAREST_RING = "nearest-ring"

private const val STYLE_LIGHT = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
private const val STYLE_DARK = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

@Composable
fun MapLibreContainer(
    isDarkTheme: Boolean,
    geoJsonData: String,
    selectedStop: Stop?,
    sheetPosition: SheetPosition,
    onStopClicked: (Long) -> Unit,
    onCameraCenterChanged: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Initialize MapLibre
    remember {
        MapLibre.getInstance(context)
    }

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var currentStyleUri by remember { mutableStateOf(if (isDarkTheme) STYLE_DARK else STYLE_LIGHT) }
    var haloAnimator by remember { mutableStateOf<ValueAnimator?>(null) }

    // Setup MapView
    val mapView = remember {
        MapView(context).apply {
            getMapAsync { map ->
                mapInstance = map
                // Initial camera at Isfahan center
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(32.6480, 51.6673))
                    .zoom(12.0)
                    .build()

                map.addOnCameraMoveListener {
                    val target = map.cameraPosition.target
                    if (target != null) {
                        onCameraCenterChanged(target.latitude, target.longitude)
                    }
                }

                // 24px tolerance box for tapping
                val tolerancePx = with(density) { 24.dp.toPx() }
                val queryBox = RectF()

                map.addOnMapClickListener { point ->
                    val screenPoint = map.projection.toScreenLocation(point)
                    queryBox.set(
                        screenPoint.x - tolerancePx,
                        screenPoint.y - tolerancePx,
                        screenPoint.x + tolerancePx,
                        screenPoint.y + tolerancePx
                    )

                    // 1. Query clusters
                    val clusterFeatures = map.queryRenderedFeatures(queryBox, LAYER_CLUSTER_CIRCLE)
                    if (clusterFeatures.isNotEmpty()) {
                        val zoom = map.cameraPosition.zoom + 1.5
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(point, zoom),
                            400
                        )
                        return@addOnMapClickListener true
                    }

                    // 2. Query individual stops
                    val stopFeatures = map.queryRenderedFeatures(queryBox, LAYER_STOP_CIRCLE, LAYER_STOP_HALO)
                    if (stopFeatures.isNotEmpty()) {
                        val feature = stopFeatures.first()
                        val id = feature.getNumberProperty("id")?.toLong()
                        if (id != null) {
                            onStopClicked(id)
                            return@addOnMapClickListener true
                        }
                    }

                    false
                }
            }
        }
    }

    // Function to configure layers on style
    fun setupLayersOnStyle(style: Style, geoJson: String) {
        // Remove existing layers & sources if already present
        try {
            style.removeLayer(LAYER_NEAREST_RING)
            style.removeLayer(LAYER_STOP_HALO)
            style.removeLayer(LAYER_STOP_CIRCLE)
            style.removeLayer(LAYER_CLUSTER_COUNT)
            style.removeLayer(LAYER_CLUSTER_CIRCLE)
            style.removeSource(SOURCE_ID)
        } catch (_: Exception) {}

        // 1. GeoJSON Source with clustering
        val options = GeoJsonOptions()
            .withCluster(true)
            // Individual stop dots only appear one level above this, so 15 meant
            // zooming from the initial 12 all the way to 15+ before any stop showed.
            .withClusterMaxZoom(13)
            .withClusterRadius(50)

        val source = GeoJsonSource(SOURCE_ID, geoJson, options)
        style.addSource(source)

        // 2. Cluster Circle Layer
        // 16px (2–10), 22px (11–50), 28px (51+)
        // Colors: #2563EB, #1D4ED8, #1E3A8A
        val clusterCircleLayer = CircleLayer(LAYER_CLUSTER_CIRCLE, SOURCE_ID).apply {
            setFilter(has("point_count"))
            setProperties(
                circleRadius(
                    step(
                        get("point_count"),
                        literal(16.0f),
                        stop(11, literal(22.0f)),
                        stop(51, literal(28.0f))
                    )
                ),
                circleColor(
                    step(
                        get("point_count"),
                        color(Color.parseColor("#2563EB")),
                        stop(11, color(Color.parseColor("#1D4ED8"))),
                        stop(51, color(Color.parseColor("#1E3A8A")))
                    )
                ),
                circleOpacity(0.92f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2.0f)
            )
        }
        style.addLayer(clusterCircleLayer)

        // 3. Cluster Count Symbol Layer
        val clusterCountLayer = SymbolLayer(LAYER_CLUSTER_COUNT, SOURCE_ID).apply {
            setFilter(has("point_count"))
            setProperties(
                textField(toString(get("point_count"))),
                textSize(12.0f),
                textColor(Color.WHITE),
                textAllowOverlap(true)
            )
        }
        style.addLayer(clusterCountLayer)

        // 4. Default Stop Circle Layer (unclustered)
        val stopCircleLayer = CircleLayer(LAYER_STOP_CIRCLE, SOURCE_ID).apply {
            setFilter(not(has("point_count")))
            setProperties(
                circleRadius(8.0f),
                circleColor(Color.parseColor("#2563EB")),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2.5f),
                circleOpacity(0.95f)
            )
        }
        style.addLayer(stopCircleLayer)

        // 5. Nearest Ring Layer (green indicator for single nearest stop)
        val nearestRingLayer = CircleLayer(LAYER_NEAREST_RING, SOURCE_ID).apply {
            setFilter(all(not(has("point_count")), eq(get("isNearest"), true)))
            setProperties(
                circleRadius(16.0f),
                circleColor(Color.TRANSPARENT),
                circleStrokeColor(Color.parseColor("#16A34A")),
                circleStrokeWidth(3.0f),
                circleOpacity(0.9f)
            )
        }
        style.addLayer(nearestRingLayer)

        // 6. Stop Halo Layer (Selected stop with pulse animation)
        val stopHaloLayer = CircleLayer(LAYER_STOP_HALO, SOURCE_ID).apply {
            setFilter(all(not(has("point_count")), eq(get("selected"), true)))
            setProperties(
                circleRadius(13.0f),
                circleColor(Color.parseColor("#1D4ED8")),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(3.0f),
                circleOpacity(0.9f)
            )
        }
        style.addLayer(stopHaloLayer)

        // Pulse ValueAnimator (13 -> 22 radius, 0.9 -> 0.25 opacity, 1600ms)
        haloAnimator?.cancel()
        haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val radius = 13.0f + (22.0f - 13.0f) * fraction
                val opacity = 0.9f - (0.9f - 0.25f) * fraction
                mapInstance?.getStyle { currentStyle ->
                    val halo = currentStyle.getLayerAs<CircleLayer>(LAYER_STOP_HALO)
                    halo?.setProperties(
                        circleRadius(radius),
                        circleOpacity(opacity)
                    )
                }
            }
            start()
        }
    }

    // Handle Theme Switch and critical style re-load
    val targetStyleUri = if (isDarkTheme) STYLE_DARK else STYLE_LIGHT
    LaunchedEffect(targetStyleUri) {
        val map = mapInstance ?: return@LaunchedEffect
        currentStyleUri = targetStyleUri
        map.setStyle(Style.Builder().fromUri(targetStyleUri)) { style ->
            setupLayersOnStyle(style, geoJsonData)
        }
    }

    // Update GeoJSON source when selection or nearest change
    LaunchedEffect(geoJsonData) {
        mapInstance?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
            if (source != null) {
                source.setGeoJson(geoJsonData)
            } else {
                setupLayersOnStyle(style, geoJsonData)
            }
        }
    }

    // Fly camera to selected stop with bottom sheet padding
    LaunchedEffect(selectedStop) {
        if (selectedStop != null) {
            val map = mapInstance ?: return@LaunchedEffect
            val target = LatLng(selectedStop.lat, selectedStop.lng)
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(target, 15.5),
                700
            )
        }
    }

    // Camera padding when sheet is open (so selected stop is never hidden)
    LaunchedEffect(sheetPosition) {
        val map = mapInstance ?: return@LaunchedEffect
        val bottomPadding = when (sheetPosition) {
            SheetPosition.COLLAPSED -> 0
            SheetPosition.HALF -> with(density) { 260.dp.toPx() }.toInt()
            SheetPosition.EXPANDED -> with(density) { 380.dp.toPx() }.toInt()
        }
        map.setPadding(0, 0, 0, bottomPadding)
    }

    DisposableEffect(Unit) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            haloAnimator?.cancel()
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
            .fillMaxSize()
            .testTag("maplibre_view")
    )
}
