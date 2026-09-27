package com.example.ui.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BusRepository
import com.example.data.repository.CachedStopDetail
import com.example.model.ArrivalItem
import com.example.model.NoticeResponse
import com.example.model.Stop
import com.example.util.LocationHelper
import com.example.util.NetworkMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SheetPosition {
    COLLAPSED, // 22%
    HALF,      // 55%
    EXPANDED   // 90%
}

data class BusUiState(
    val isOffline: Boolean = false,
    val isCheckingInternet: Boolean = false,
    val stops: List<Stop> = emptyList(),
    val selectedStop: Stop? = null,
    val selectedStopDetail: CachedStopDetail? = null,
    val arrivals: List<ArrivalItem> = emptyList(),
    val isArrivalsLoading: Boolean = false,
    val arrivalsError: Boolean = false,
    val secondsSinceUpdate: Int = 0,
    val nearbyStops: List<Pair<Stop, Double>> = emptyList(),
    val nearestStop: Stop? = null,
    val sheetPosition: SheetPosition = SheetPosition.COLLAPSED,
    val searchQuery: String = "",
    val searchResults: List<Stop> = emptyList(),
    val isSearchActive: Boolean = false,
    val showLocationRationale: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val userLocation: Location? = null,
    val cameraCenter: Pair<Double, Double> = 32.6480 to 51.6673, // Isfahan center: lat, lng
    val isDarkTheme: Boolean = false,
    val notice: NoticeResponse? = null,
    // One-shot camera request (lat, lng). focusToken increments so an identical
    // target still triggers a new move — the map only ever reacts to the token.
    val focusTarget: Pair<Double, Double>? = null,
    val focusToken: Int = 0
)

class BusViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BusRepository(application)
    private val networkMonitor = NetworkMonitor(application)
    private val locationHelper = LocationHelper(application)

    private val _uiState = MutableStateFlow(BusUiState())
    val uiState: StateFlow<BusUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var freshnessTimerJob: Job? = null
    private var consecutiveNetworkFailures = 0
    private var hasMadeFirstSuccessfulCall = false
    private var isAppResumed = true

    init {
        // Monitor network state
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                if (!isOnline) {
                    _uiState.value = _uiState.value.copy(isOffline = true)
                    stopArrivalsPolling()
                } else if (_uiState.value.isOffline) {
                    // Back online
                    _uiState.value = _uiState.value.copy(isOffline = false)
                    consecutiveNetworkFailures = 0
                    if (_uiState.value.stops.isEmpty()) {
                        initData()
                    }
                }
            }
        }

        // Initial connection check
        if (!networkMonitor.checkInitialConnectivity()) {
            _uiState.value = _uiState.value.copy(isOffline = true)
        } else {
            initData()
        }

        // Start freshness counter
        startFreshnessTimer()
    }

    fun retryConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingInternet = true)
            delay(600)
            val isOnline = networkMonitor.checkInitialConnectivity()
            if (isOnline) {
                consecutiveNetworkFailures = 0
                _uiState.value = _uiState.value.copy(isOffline = false, isCheckingInternet = false)
                initData()
            } else {
                _uiState.value = _uiState.value.copy(isOffline = true, isCheckingInternet = false)
            }
        }
    }

    private fun initData() {
        viewModelScope.launch {
            val stops = repository.loadStops()
            _uiState.value = _uiState.value.copy(
                stops = stops,
                hasLocationPermission = locationHelper.hasLocationPermission()
            )

            // Try fetching initial notice
            val notice = repository.getNotice()
            if (notice != null) {
                onNetworkSuccess()
                _uiState.value = _uiState.value.copy(notice = notice)
            }

            // Update user location or fallback
            refreshLocationAndNearby()
        }
    }

    fun getStopById(id: Long): Stop? = repository.getStopById(id)

    private fun onNetworkSuccess() {
        consecutiveNetworkFailures = 0
        if (!hasMadeFirstSuccessfulCall) {
            hasMadeFirstSuccessfulCall = true
            // If location permission not yet granted, show rationale card
            if (!locationHelper.hasLocationPermission()) {
                _uiState.value = _uiState.value.copy(showLocationRationale = true)
            }
        }
    }

    private fun onNetworkFailure() {
        consecutiveNetworkFailures++
        if (consecutiveNetworkFailures >= 2) {
            _uiState.value = _uiState.value.copy(isOffline = true)
            stopArrivalsPolling()
        }
    }

    fun dismissLocationRationale() {
        _uiState.value = _uiState.value.copy(showLocationRationale = false)
    }

    fun onLocationPermissionGranted() {
        _uiState.value = _uiState.value.copy(
            hasLocationPermission = true,
            showLocationRationale = false
        )
        refreshLocationAndNearby()
    }

    fun updateCameraCenter(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(cameraCenter = lat to lng)
        // If no GPS permission, nearby stops are sorted based on camera center
        if (!_uiState.value.hasLocationPermission || _uiState.value.userLocation == null) {
            updateNearbyStops(lat, lng)
        }
    }

    fun refreshLocationAndNearby() {
        viewModelScope.launch {
            if (locationHelper.hasLocationPermission()) {
                val loc = locationHelper.getCurrentLocation()
                if (loc != null) {
                    _uiState.value = _uiState.value.copy(userLocation = loc)
                    updateNearbyStops(loc.latitude, loc.longitude)
                    // Emit only now: the location is resolved, and this is what
                    // actually moves the camera (the old call read state too early
                    // and updated a field no camera code ever read).
                    emitFocus(loc.latitude, loc.longitude)
                    return@launch
                }
            }
            // Fallback to camera center
            val (lat, lng) = _uiState.value.cameraCenter
            updateNearbyStops(lat, lng)
        }
    }

    private fun emitFocus(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(
            focusTarget = lat to lng,
            focusToken = _uiState.value.focusToken + 1
        )
    }

    private fun updateNearbyStops(lat: Double, lng: Double) {
        val nearby = repository.getNearbyStops(lat, lng, limit = 10)
        val nearest = nearby.firstOrNull()?.first
        _uiState.value = _uiState.value.copy(
            nearbyStops = nearby,
            nearestStop = nearest
        )
    }

    fun selectStop(stop: Stop, fromUserAction: Boolean = true) {
        _uiState.value = _uiState.value.copy(
            selectedStop = stop,
            sheetPosition = if (fromUserAction) SheetPosition.HALF else _uiState.value.sheetPosition,
            isSearchActive = false,
            searchQuery = "",
            searchResults = emptyList(),
            arrivalsError = false,
            secondsSinceUpdate = 0
        )

        // Load cached/online stop details (address, station code, panorama)
        viewModelScope.launch {
            val details = repository.getStopDetails(stop)
            _uiState.value = _uiState.value.copy(selectedStopDetail = details)
            onNetworkSuccess()
        }

        // Fetch arrivals immediately and start polling
        fetchArrivals(stop)
        restartArrivalsPolling(stop)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearchActive = false)
        } else {
            val results = repository.searchStops(query, limit = 20)
            _uiState.value = _uiState.value.copy(searchResults = results, isSearchActive = true)
        }
    }

    fun closeSearch() {
        _uiState.value = _uiState.value.copy(
            isSearchActive = false,
            searchQuery = "",
            searchResults = emptyList()
        )
    }

    fun setSheetPosition(position: SheetPosition) {
        _uiState.value = _uiState.value.copy(sheetPosition = position)
        val selected = _uiState.value.selectedStop
        if (position == SheetPosition.COLLAPSED) {
            stopArrivalsPolling()
        } else if (selected != null) {
            restartArrivalsPolling(selected)
        }
    }

    /**
     * Back navigation handling inside sheet:
     * EXPANDED -> HALF -> COLLAPSED -> returns false (can exit app)
     */
    fun handleBackPressed(): Boolean {
        return when (_uiState.value.sheetPosition) {
            SheetPosition.EXPANDED -> {
                setSheetPosition(SheetPosition.HALF)
                true
            }
            SheetPosition.HALF -> {
                setSheetPosition(SheetPosition.COLLAPSED)
                true
            }
            SheetPosition.COLLAPSED -> {
                if (_uiState.value.isSearchActive) {
                    closeSearch()
                    true
                } else {
                    false
                }
            }
        }
    }

    fun toggleTheme() {
        _uiState.value = _uiState.value.copy(isDarkTheme = !_uiState.value.isDarkTheme)
    }

    fun setAppResumed(resumed: Boolean) {
        isAppResumed = resumed
        val selected = _uiState.value.selectedStop
        if (resumed && _uiState.value.sheetPosition != SheetPosition.COLLAPSED && selected != null) {
            restartArrivalsPolling(selected)
        } else {
            stopArrivalsPolling()
        }
    }

    private fun fetchArrivals(stop: Stop) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isArrivalsLoading = true)
            val result = repository.getArrivals(stop)
            result.onSuccess { arrivals ->
                onNetworkSuccess()
                _uiState.value = _uiState.value.copy(
                    arrivals = arrivals,
                    isArrivalsLoading = false,
                    arrivalsError = false,
                    secondsSinceUpdate = 0
                )
            }.onFailure {
                onNetworkFailure()
                _uiState.value = _uiState.value.copy(
                    isArrivalsLoading = false,
                    arrivalsError = true
                )
            }
        }
    }

    private fun restartArrivalsPolling(stop: Stop) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive && isAppResumed && _uiState.value.sheetPosition != SheetPosition.COLLAPSED) {
                delay(20_000) // 20 seconds polling
                if (!isActive || !isAppResumed || _uiState.value.sheetPosition == SheetPosition.COLLAPSED) break

                val result = repository.getArrivals(stop)
                result.onSuccess { arrivals ->
                    onNetworkSuccess()
                    _uiState.value = _uiState.value.copy(
                        arrivals = arrivals,
                        arrivalsError = false,
                        secondsSinceUpdate = 0
                    )
                }.onFailure {
                    onNetworkFailure()
                    _uiState.value = _uiState.value.copy(arrivalsError = true)
                }
            }
        }
    }

    private fun stopArrivalsPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun startFreshnessTimer() {
        freshnessTimerJob?.cancel()
        freshnessTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.value = _uiState.value.copy(
                    secondsSinceUpdate = _uiState.value.secondsSinceUpdate + 1
                )
            }
        }
    }

    fun getGeoJsonData(): String {
        return repository.buildGeoJson(
            selectedStopId = _uiState.value.selectedStop?.id,
            nearestStopId = _uiState.value.nearestStop?.id
        )
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        freshnessTimerJob?.cancel()
    }
}
