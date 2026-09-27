package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    onSelectStop: (Stop) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val totalHeightPx = with(density) { maxHeight.toPx() }

        // Three anchor offsets from top:
        // COLLAPSED: top is at 78% of screen (height is 22%)
        // HALF: top is at 45% of screen (height is 55%)
        // EXPANDED: top is at 10% of screen (height is 90%)
        val collapsedOffsetPx = totalHeightPx * (1f - 0.22f)
        val halfOffsetPx = totalHeightPx * (1f - 0.55f)
        val expandedOffsetPx = totalHeightPx * (1f - 0.90f)

        val targetOffsetPx = when (sheetPosition) {
            SheetPosition.COLLAPSED -> collapsedOffsetPx
            SheetPosition.HALF -> halfOffsetPx
            SheetPosition.EXPANDED -> expandedOffsetPx
        }

        val animOffsetY = remember { Animatable(collapsedOffsetPx) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(targetOffsetPx) {
            animOffsetY.animateTo(
                targetValue = targetOffsetPx,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
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
                .height(maxHeight)
                // Hardware layer translationY for smooth 60fps performance without per-frame relayout
                .graphicsLayer {
                    translationY = animOffsetY.value
                }
                .pointerInput(totalHeightPx) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            val currentY = animOffsetY.value
                            // Snap to closest anchor
                            val distCollapsed = kotlin.math.abs(currentY - collapsedOffsetPx)
                            val distHalf = kotlin.math.abs(currentY - halfOffsetPx)
                            val distExpanded = kotlin.math.abs(currentY - expandedOffsetPx)

                            val closest = when {
                                distExpanded < distHalf && distExpanded < distCollapsed -> SheetPosition.EXPANDED
                                distHalf < distCollapsed -> SheetPosition.HALF
                                else -> SheetPosition.COLLAPSED
                            }
                            onPositionChange(closest)
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val newY = (animOffsetY.value + dragAmount)
                                .coerceIn(expandedOffsetPx - 40f, collapsedOffsetPx + 40f)
                            scope.launch { animOffsetY.snapTo(newY) }
                        }
                    )
                }
                .testTag("bus_bottom_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
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
                                secondsSinceUpdate = secondsSinceUpdate,
                                maxItems = if (sheetPosition == SheetPosition.HALF) 3 else null
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
