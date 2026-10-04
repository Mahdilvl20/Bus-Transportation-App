package com.example.ui.screens

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import com.example.R
import com.example.model.Stop
import com.example.ui.viewmodel.SheetPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
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

// OpenFreeMap answers in under a second on this network; Carto's style.json times out,
// so it is only a backup. Both are keyless.
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val STYLE_LIGHT_FALLBACK = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
private const val STYLE_DARK_FALLBACK = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

/**
 * Road fill colours for the dark style, whose interiors sit at #181818..#000 on a
 * #0C0C0C background and are unreadable above z6. Casing stays lighter than the
 * inner fill so the layering the style relies on still reads.
 */
private val DARK_ROAD_COLORS = mapOf(
    "road_pier" to "#2b2b2b",
    "highway_path" to "#3f3f41",
    "highway_minor" to "#343436",
    "highway_major_subtle" to "#2f2f31",
    "highway_major_inner" to "#3c3c3f",
    "highway_major_casing" to "#5a5a5e",
    "highway_motorway_inner" to "#4a4a4e",
    "highway_motorway_casing" to "#5f5f64",
    "highway_motorway_subtle" to "#2f2f31",
)

/** How long to wait for one style before trying the next. */
private const val STYLE_TIMEOUT_MS = 8_000L

@Composable
fun MapLibreContainer(
    isDarkTheme: Boolean,
    geoJsonData: String,
    selectedStop: Stop?,
    focusTarget: Pair<Double, Double>?,
    focusToken: Int,
    sheetPosition: SheetPosition,
    onStopClicked: (Long) -> Unit,
    onCameraCenterChanged: (Double, Double) -> Unit,
    onCenterStopChanged: (Long?) -> Unit,
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
    // True only when every style candidate failed — the map must never fail silently.
    var styleError by remember { mutableStateOf(false) }
    var styleRetry by remember { mutableStateOf(0) }
    // Set once the layers exist, so effects below can find the halo animator.
    var styleReady by remember { mutableStateOf(false) }

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

                // Idle, not move: the move listener fired ~60x/second while panning,
                // and each call wrote to UiState (recomposing the whole screen) and
                // re-sorted all 3,002 stops when GPS permission was missing.
                // 24px tolerance box, shared by tapping and by the centre pin so a stop
                // can be aimed at as well as tapped.
                val tolerancePx = with(density) { 24.dp.toPx() }
                val queryBox = RectF()

                map.addOnCameraIdleListener {
                    val target = map.cameraPosition.target
                    if (target != null) {
                        onCameraCenterChanged(target.latitude, target.longitude)

                        // What sits under the fixed pin? A cluster is not a stop, so at low
                        // zoom nothing is reported and the hint card stays hidden.
                        val screenPoint = map.projection.toScreenLocation(target)
                        queryBox.set(
                            screenPoint.x - tolerancePx,
                            screenPoint.y - tolerancePx,
                            screenPoint.x + tolerancePx,
                            screenPoint.y + tolerancePx
                        )
                        val clustered = map.queryRenderedFeatures(queryBox, LAYER_CLUSTER_CIRCLE)
                        val hits: List<org.maplibre.geojson.Feature> = if (clustered.isEmpty()) {
                            map.queryRenderedFeatures(queryBox, LAYER_STOP_CIRCLE, LAYER_STOP_HALO)
                        } else {
                            emptyList()
                        }
                        onCenterStopChanged(hits.firstOrNull()?.getNumberProperty("id")?.toLong())
                    }
                }

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

        // Pulse ValueAnimator (13 -> 22 radius, 0.9 -> 0.25 opacity, 1600ms).
        // Created here but NOT started: an infinite animator calls getStyle +
        // setProperties on every frame, which invalidates the layer and forces a
        // full re-render ~60x/s. Running it with nothing selected burns CPU for
        // nothing, so it is started only while a stop is selected.
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
        }
        // The dark style draws road interiors at #181818 / hsl(0,0%,7%) and motorways at
        // #000 above z6 — all on a #0C0C0C background, so at the zooms this app uses only
        // the casings were visible. Lift the fills while keeping the casing/inner pairing
        // that carries the hierarchy, so motorways still outrank major roads.
        if (isDarkTheme) {
            DARK_ROAD_COLORS.forEach { (layerId, color) ->
                style.getLayerAs<LineLayer>(layerId)?.setProperties(lineColor(Color.parseColor(color)))
            }
        }

        styleReady = true
    }

    // Load the base style. Keyed on mapInstance as well as the theme: getMapAsync()
    // completes *after* first composition, so without that key the first load was
    // silently skipped and the map stayed black for the whole session.
    val targetStyleUri = if (isDarkTheme) STYLE_DARK else STYLE_LIGHT
    LaunchedEffect(targetStyleUri, mapInstance, styleRetry) {
        val map = mapInstance ?: return@LaunchedEffect
        currentStyleUri = targetStyleUri
        styleError = false
        styleReady = false
        val candidates = listOf(
            targetStyleUri,
            if (isDarkTheme) STYLE_DARK_FALLBACK else STYLE_LIGHT_FALLBACK,
        )
        var loaded = false
        for (uri in candidates) {
            val outcome = CompletableDeferred<Boolean>()
            val onFail = object : MapView.OnDidFailLoadingMapListener {
                override fun onDidFailLoadingMap(reason: String) {
                    if (!outcome.isCompleted) outcome.complete(false)
                }
            }
            mapView.addOnDidFailLoadingMapListener(onFail)
            map.setStyle(Style.Builder().fromUri(uri)) { style ->
                if (!outcome.isCompleted) outcome.complete(true)
                setupLayersOnStyle(style, geoJsonData)
            }
            loaded = withTimeoutOrNull(STYLE_TIMEOUT_MS) { outcome.await() } ?: false
            mapView.removeOnDidFailLoadingMapListener(onFail)
            if (loaded) break
        }
        styleError = !loaded
    }

    // Run the halo pulse only while a stop is selected. Unconditional it would
    // re-render the map ~60 times a second for the whole session.
    LaunchedEffect(styleReady, selectedStop) {
        if (styleReady && selectedStop != null) haloAnimator?.start()
        else haloAnimator?.cancel()
    }

    // One-shot camera move requested by the ViewModel ("my location"). Keyed on
    // mapInstance as well so a press made before the map finished loading is not lost.
    LaunchedEffect(focusToken, mapInstance) {
        if (focusToken == 0) return@LaunchedEffect
        val target = focusTarget ?: return@LaunchedEffect
        val map = mapInstance ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(target.first, target.second), 16.0),
            600
        )
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

    // Camera padding when sheet is open (so selected stop is never hidden).
    // Read in composition because LocalConfiguration is composable-only, and EXPANDED
    // covers 90% of the screen on every device — a hardcoded 380dp only lined up on one
    // phone size and left the stop behind the sheet on the others.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val expandedPaddingPx = with(density) { (screenHeightDp.dp * 0.9f).toPx() }
    LaunchedEffect(sheetPosition) {
        val map = mapInstance ?: return@LaunchedEffect
        val bottomPadding = when (sheetPosition) {
            SheetPosition.HIDDEN -> 0
            SheetPosition.COLLAPSED -> 0
            SheetPosition.EXPANDED -> expandedPaddingPx.toInt()
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .testTag("maplibre_view")
        )

        // Fixed aim point. MapLibre centres the camera inside the same bottom-padded
        // region it gets for the sheet, so the pin stays exactly where the camera looks.
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = maxHeight * (1f - if (sheetPosition == SheetPosition.EXPANDED) 0.9f else 0f) / 2f - 22.dp)
                .size(44.dp)
                .testTag("map_centre_pin")
        )

        // A visible failure beats a black screen: the user can retry instead of guessing.
        if (styleError) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.map_load_failed),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.map_load_failed_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { styleRetry++ }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
