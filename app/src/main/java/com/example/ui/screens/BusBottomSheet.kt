package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.CachedStopDetail
import com.example.model.ArrivalItem
import com.example.model.Stop
import com.example.ui.viewmodel.SheetPosition
import com.example.util.PersianUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The handle strip opens the panel: HIDDEN -> COLLAPSED -> EXPANDED. */
private fun nextSheetAnchor(position: SheetPosition): SheetPosition = when (position) {
    SheetPosition.HIDDEN -> SheetPosition.COLLAPSED
    SheetPosition.COLLAPSED -> SheetPosition.EXPANDED
    SheetPosition.EXPANDED -> SheetPosition.EXPANDED
}

@Composable
fun BusBottomSheet(
    sheetPosition: SheetPosition,
    onPositionChange: (SheetPosition) -> Unit,
    selectedStop: Stop?,
    stopDetail: CachedStopDetail?,
    arrivals: List<ArrivalItem>,
    isArrivalsLoading: Boolean,
    arrivalsError: Boolean,
    secondsSinceUpdate: Int,
    nearbyStops: List<Pair<Stop, Double>>,
    centerStop: Stop?,
    centerStopDistance: Double?,
    onSelectStop: (Stop) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val totalHeightPx = with(density) { maxHeight.toPx() }

        // Three anchor offsets from top:
        // HIDDEN: only the handle stays on screen, and it must clear the system
        //         navigation bar as well or it lands under the gesture pill
        // COLLAPSED: top is at 76% of screen (height is 24%) — nearby list preview
        // EXPANDED: top is at 10% of screen (height is 90%) — full screen, scrolls
        val navBarPx = with(density) {
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
        }
        // 16dp padding + 4.5dp line + 16dp padding, plus a clearance so the handle is
        // not inside the system navigation bar's touch strip (it was 30dp from the
        // bottom and taps fell through to the gesture bar).
        val handlePx = with(density) { 36.5f.dp.toPx() }
        val clearancePx = with(density) { 16f.dp.toPx() }
        val hiddenOffsetPx = totalHeightPx - (handlePx + navBarPx + clearancePx)
        val collapsedOffsetPx = totalHeightPx * (1f - 0.24f)
        val expandedOffsetPx = totalHeightPx * (1f - 0.90f)

        val targetOffsetPx = when (sheetPosition) {
            SheetPosition.HIDDEN -> hiddenOffsetPx
            SheetPosition.COLLAPSED -> collapsedOffsetPx
            SheetPosition.EXPANDED -> expandedOffsetPx
        }

        val animOffsetY = remember { Animatable(collapsedOffsetPx) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(targetOffsetPx) {
            animOffsetY.animateTo(
                targetValue = targetOffsetPx,
                // A plain eased slide instead of the medium-bouncy spring: the panel used
                // to overshoot on every snap, which is uncomfortable to watch.
                animationSpec = tween(
                    durationMillis = 320,
                    easing = FastOutSlowInEasing
                )
            )
        }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                // Bottom-anchored with height = the visible band, not the whole screen.
                // The old graphicsLayer translationY kept the sheet full-height and merely
                // slid it down, so at COLLAPSED the LazyColumn measured a viewport reaching
                // to 178% of the screen: every item fit without scrolling and the lower rows
                // simply landed below the display. Height must track the anchor instead.
                .align(Alignment.BottomCenter)
                .height(((totalHeightPx - animOffsetY.value) / density.density).dp)
                .pointerInput(totalHeightPx) {
                    // This Compose version passes no velocity to onDragEnd, so measure it
                    // here: smoothed dragAmount per second. A finger resting for a moment
                    // must not count as a flick, so it decays if the last move was recent.
                    var velocity = 0f
                    var lastNanos = 0L

                    detectVerticalDragGestures(
                        onDragEnd = {
                            val currentY = animOffsetY.value
                            val anchors = listOf(
                                SheetPosition.HIDDEN to hiddenOffsetPx,
                                SheetPosition.COLLAPSED to collapsedOffsetPx,
                                SheetPosition.EXPANDED to expandedOffsetPx,
                            )
                            // A flick carries the sheet to the next anchor even when it
                            // travelled less than half the gap — otherwise a swipe up from
                            // COLLAPSED (68% of the screen away from EXPANDED) always
                            // snapped straight back and the sheet never fully opened.
                            val settled = System.nanoTime() - lastNanos > 150_000_000L
                            val speed = if (settled) 0f else velocity
                            val next = if (kotlin.math.abs(speed) > 800f) {
                                if (speed > 0f) {
                                    // finger moving down -> larger offset (towards HIDDEN)
                                    anchors.filter { it.second > currentY + 1f }
                                        .minByOrNull { it.second }
                                } else {
                                    anchors.filter { it.second < currentY - 1f }
                                        .maxByOrNull { it.second }
                                }
                            } else null

                            val closest = next ?: anchors
                                .minByOrNull { kotlin.math.abs(currentY - it.second) }!!
                            onPositionChange(closest.first)
                            velocity = 0f
                            lastNanos = 0L
                        },
                        onDragCancel = {
                            velocity = 0f
                            lastNanos = 0L
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val now = System.nanoTime()
                            if (lastNanos != 0L) {
                                val dt = (now - lastNanos) / 1_000_000_000f
                                if (dt > 0f) {
                                    velocity = velocity * 0.6f + (dragAmount / dt) * 0.4f
                                }
                            }
                            lastNanos = now
                            val newY = (animOffsetY.value + dragAmount)
                                .coerceIn(expandedOffsetPx - 40f, hiddenOffsetPx)
                            scope.launch { animOffsetY.snapTo(newY) }
                        }
                    )
                }
                .testTag("bus_bottom_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Inset the CONTENT, not the sheet: padding on the Surface itself
                    // shrank the background too, leaving the map visible as a black strip
                    // below the panel.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
            ) {
                // Drag Handle. The whole strip is tappable: a bare line this close to
                // the system navigation bar looked pressable but did nothing, so taps
                // landed on the gesture bar instead.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPositionChange(nextSheetAnchor(sheetPosition)) }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(4.5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            .testTag("sheet_drag_handle")
                    )
                }

                // Fully closed shows the handle only: the extra clearance needed to keep
                // that handle out of the system navigation bar's touch strip would
                // otherwise leave the top of the title text peeking out above the bar.
                if (sheetPosition != SheetPosition.HIDDEN) {
                // Aim hint: whatever sits under the fixed centre pin. Only while browsing —
                // once a stop is open it would simply repeat itself. This is what makes
                // picking a stop possible when several sit on top of each other.
                if (centerStop != null && selectedStop == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectStop(centerStop) }
                            .padding(top = 4.dp, bottom = 6.dp)
                            .testTag("centre_stop_hint"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = centerStop.name,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            centerStopDistance?.let { meters ->
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = PersianUtils.formatDistance(meters),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // If no stop selected or collapsed: Show "Nearby Stops" list
                if (selectedStop == null || sheetPosition == SheetPosition.COLLAPSED) {
                    Text(
                        text = stringResource(id = R.string.nearby_stops),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .testTag("nearby_stops_title")
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(nearbyStops, key = { it.first.id }) { (stop, dist) ->
                            NearbyStopRow(
                                stop = stop,
                                distanceMeters = dist,
                                onClick = { onSelectStop(stop) }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                } else {
                    // STICKY HEADER for selected stop
                    StopDetailHeader(
                        stop = selectedStop,
                        detail = stopDetail,
                        isExpanded = sheetPosition == SheetPosition.EXPANDED
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // ARRIVAL CONTENT
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        item {
                            ArrivalsList(
                                arrivals = arrivals,
                                isLoading = isArrivalsLoading,
                                hasError = arrivalsError,
                                secondsSinceUpdate = secondsSinceUpdate
                            )
                        }

                        // Additional detail visible in EXPANDED state
                        if (sheetPosition == SheetPosition.EXPANDED) {
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                ExpandedStopDetails(
                                    stop = selectedStop,
                                    detail = stopDetail
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                }
                } // end HIDDEN guard
            }
        }
    }
}

@Composable
fun StopDetailHeader(
    stop: Stop,
    detail: CachedStopDetail?,
    isExpanded: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stop.name,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("selected_stop_name")
            )

            // Station Code Chip
            detail?.stationCode?.let { code ->
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("station_code_chip")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.station_code_prefix),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = PersianUtils.toPersianDigits(code),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // One-line address
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = detail?.fullAddress ?: stringResource(id = R.string.address_loading),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isExpanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("stop_address")
            )
        }
    }
}

@Composable
fun ExpandedStopDetails(
    stop: Stop,
    detail: CachedStopDetail?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (detail?.hasPanorama == true) {
            Button(
                onClick = { /* Panorama view action */ },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("panorama_button")
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_panorama),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.panorama),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun NearbyStopRow(
    stop: Stop,
    distanceMeters: Double,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("nearby_stop_row_${stop.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stop.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(id = R.string.bus_station),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Distance rendered in Persian
        Text(
            text = PersianUtils.formatDistance(distanceMeters),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("nearby_stop_distance_${stop.id}")
        )
    }
}
