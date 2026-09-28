package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.viewmodel.BusViewModel
import com.example.ui.viewmodel.SheetPosition

@Composable
fun MapScreen(
    viewModel: BusViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.onLocationPermissionGranted()
        } else {
            viewModel.dismissLocationRationale()
        }
    }

    // Back button handling: EXPANDED -> COLLAPSED -> HIDDEN -> exit app
    BackHandler(enabled = true) {
        val handled = viewModel.handleBackPressed()
        if (!handled) {
            // Exit app
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("map_screen_root")
    ) {
        // 1. MapLibre Map View Layer
        MapLibreContainer(
            isDarkTheme = uiState.isDarkTheme,
            geoJsonData = viewModel.getGeoJsonData(),
            selectedStop = uiState.selectedStop,
            focusTarget = uiState.focusTarget,
            focusToken = uiState.focusToken,
            sheetPosition = uiState.sheetPosition,
            onStopClicked = { stopId ->
                val stop = viewModel.getStopById(stopId)
                if (stop != null) {
                    viewModel.selectStop(stop, fromUserAction = true)
                }
            },
            onCameraCenterChanged = { lat, lng ->
                viewModel.updateCameraCenter(lat, lng)
            }
        )

        // 2. Top Floating Controls: Search Bar, Notice, Location Rationale
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .align(Alignment.TopCenter)
        ) {
            // Notice banner (if active)
            if (uiState.notice?.isActive == true && !uiState.notice?.notice.isNullOrBlank()) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("notice_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.notice?.notice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }

            // Floating Search Pill
            FloatingSearchBar(
                query = uiState.searchQuery,
                onQueryChange = { viewModel.onSearchQueryChanged(it) },
                isActive = uiState.isSearchActive,
                searchResults = uiState.searchResults,
                onStopSelected = { stop ->
                    viewModel.selectStop(stop, fromUserAction = true)
                },
                onClearSearch = { viewModel.closeSearch() }
            )

            // Non-blocking Location Rationale Card (shown after first network call if permission missing)
            AnimatedVisibility(
                visible = uiState.showLocationRationale,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                LocationRationaleCard(
                    onRequestPermission = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    onDismiss = { viewModel.dismissLocationRationale() }
                )
            }
        }

        // 3. Floating Quick Action Buttons (My Location & Theme Toggle)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            // Theme toggle
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = { viewModel.toggleTheme() },
                    modifier = Modifier.testTag("theme_toggle_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = stringResource(id = R.string.toggle_theme),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // My location button
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = {
                        if (uiState.hasLocationPermission) {
                            // Resolves the location, refreshes the nearby list and
                            // emits a one-shot camera move once it has the fix.
                            viewModel.refreshLocationAndNearby()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    modifier = Modifier.testTag("my_location_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = stringResource(id = R.string.my_location),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 4. Three-Anchor Bottom Sheet (22%, 55%, 90%)
        BusBottomSheet(
            sheetPosition = uiState.sheetPosition,
            onPositionChange = { newPos -> viewModel.setSheetPosition(newPos) },
            selectedStop = uiState.selectedStop,
            stopDetail = uiState.selectedStopDetail,
            arrivals = uiState.arrivals,
            isArrivalsLoading = uiState.isArrivalsLoading,
            arrivalsError = uiState.arrivalsError,
            secondsSinceUpdate = uiState.secondsSinceUpdate,
            nearbyStops = uiState.nearbyStops,
            onSelectStop = { stop -> viewModel.selectStop(stop, fromUserAction = true) }
        )
    }
}
