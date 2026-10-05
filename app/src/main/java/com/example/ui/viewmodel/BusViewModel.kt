package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppUpdate
import com.example.data.FavoritesStore
import com.example.data.UpdateChecker
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

private const val PREFS_NAME = "app_settings"
private const val KEY_DARK_THEME = "dark_theme"

/**
 * The stored theme, falling back to the system setting on first launch (when the
 * key has never been written). Without this the app always opened light.
 */
private fun savedTheme(app: Application): Boolean {
    val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.contains(KEY_DARK_THEME)) return prefs.getBoolean(KEY_DARK_THEME, false)
    val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return night == Configuration.UI_MODE_NIGHT_YES
}

/** Seconds between arrivals refreshes — also drives the countdown shown to the user. */
const val POLL_INTERVAL_SECONDS = 20

enum class SheetPosition {
    HIDDEN,    // only the drag handle peeks above the bottom edge
    COLLAPSED, // 22% — the nearby list preview
    EXPANDED;  // 90% — full screen, where long lists actually scroll

    /** True while the sheet actually shows content — this is what gates polling. */
    val isOpen: Boolean get() = this != HIDDEN && this != COLLAPSED
}

/** The two top-level screens. No navigation library: this is one field. */
enum class AppScreen { FAVORITES, MAP }

/** A row of the home grid: the bundled stop plus the label the user chose for it. */
data class FavoriteTile(val stop: Stop, val label: String)

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
    /** Stop sitting under the fixed map pin, if any — drives the hint card. */
    val centerStop: Stop? = null,
    val centerStopDistance: Double? = null,
    val cameraCenter: Pair<Double, Double> = 32.6480 to 51.6673, // Isfahan center: lat, lng
    val isDarkTheme: Boolean = false,
    val notice: NoticeResponse? = null,
    // One-shot camera request (lat, lng). focusToken increments so an identical
    // target still triggers a new move — the map only ever reacts to the token.
    val focusTarget: Pair<Double, Double>? = null,
    val focusToken: Int = 0,
    // Landing screen is Favorites; the map is entered from it.
    val appScreen: AppScreen = AppScreen.FAVORITES,
    val favoriteTiles: List<FavoriteTile> = emptyList(),
    // Stop behind the home-screen popup, plus its cached detail for the header.
    val popupStop: Stop? = null,
    val popupStopDetail: CachedStopDetail? = null,
    // A newer release exists on GitHub; null when there is nothing to offer.
    val updateAvailable: AppUpdate? = null
)

class BusViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BusRepository(application)
    private val networkMonitor = NetworkMonitor(application)
    private val locationHelper = LocationHelper(application)
    private val favoritesStore = FavoritesStore(application)
    private val updateChecker = UpdateChecker(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(BusUiState(isDarkTheme = savedTheme(application)))
    val uiState: StateFlow<BusUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var pollingStopId: Long? = null
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
            // The grid can only be built once the bundled stop list is in memory.
            refreshFavorites()
            checkForUpdates()

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

    // ------------------------------------------------------------- favourites

    /**
     * Rebuilds the home grid from the store. A saved id whose stop is no longer in
     * the bundled list is dropped rather than rendered as an orphan tile.
     */
    private fun refreshFavorites() {
        val tiles = favoritesStore.list().mapNotNull { favorite ->
            val stop = getStopById(favorite.id) ?: return@mapNotNull null
            FavoriteTile(stop, favoritesStore.labelFor(favorite.id, stop.name))
        }
        _uiState.value = _uiState.value.copy(favoriteTiles = tiles)
    }

    fun isFavorite(stop: Stop): Boolean = favoritesStore.isFavorite(stop.id)

    fun toggleFavorite(stop: Stop) {
        if (favoritesStore.isFavorite(stop.id)) favoritesStore.remove(stop.id)
        else favoritesStore.add(stop.id)
        refreshFavorites()
    }

    fun removeFavorite(stop: Stop) {
        favoritesStore.remove(stop.id)
        refreshFavorites()
    }

    // ------------------------------------------------------------- update check

    /**
     * Asks for a newer release in the background. The checker is what makes this
     * safe: cached for [UpdateChecker.CHECK_INTERVAL_MS], silent on every failure,
     * and debug builds are skipped entirely, so no launch ever waits on GitHub.
     */
    private fun checkForUpdates() {
        viewModelScope.launch {
            val update = updateChecker.check() ?: return@launch
            if (_uiState.value.updateAvailable?.versionCode != update.versionCode) {
                _uiState.value = _uiState.value.copy(updateAvailable = update)
            }
        }
    }

    /** Remembered per version: the banner will not come back until the next release. */
    fun dismissUpdate() {
        val update = _uiState.value.updateAvailable ?: return
        updateChecker.markDismissed(update.versionCode)
        _uiState.value = _uiState.value.copy(updateAvailable = null)
    }

    /**
     * Hands the ABI-matched URL to the browser. No DownloadManager and no
     * REQUEST_INSTALL_PACKAGES: installing stays the user's own long-standing path.
     */
    fun openUpdate() {
        val url = _uiState.value.updateAvailable?.apkUrl ?: return
        runCatching { getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** @return false when the label was refused (blank or too long); state is untouched. */
    fun renameFavorite(stop: Stop, label: String): Boolean {
        if (!favoritesStore.rename(stop.id, label)) return false
        refreshFavorites()
        return true
    }

    // ------------------------------------------------------- screen and popup

    fun setAppScreen(screen: AppScreen) {
        if (screen == _uiState.value.appScreen) return
        // Switching screens must never leave a poller running behind us.
        _uiState.value = _uiState.value.copy(
            appScreen = screen,
            popupStop = null,
            popupStopDetail = null
        )
        // The map panel's list was cleared while the home screen owned the poller,
        // so refresh it on arrival instead of leaving it blank for up to 20 seconds.
        activeArrivalStop()?.let { fetchArrivals(it) }
        syncArrivalsPolling()
    }

    /**
     * The one stop allowed to poll right now: the popup on the home screen, or the
     * open panel on the map — never both. The API has no batch call, so several
     * live stops at once would mean several requests, which the app forbids.
     */
    private fun activeArrivalStop(): Stop? {
        val state = _uiState.value
        return when (state.appScreen) {
            AppScreen.FAVORITES -> state.popupStop
            AppScreen.MAP -> if (state.sheetPosition.isOpen) state.selectedStop else null
        }
    }

    /** Points the single poller at [activeArrivalStop()], or stops it when there is none. */
    private fun syncArrivalsPolling() {
        val target = activeArrivalStop()
        when {
            target == null -> stopArrivalsPolling()
            target.id != pollingStopId -> restartArrivalsPolling(target)
        }
    }

    fun openArrivalPopup(stop: Stop) {
        _uiState.value = _uiState.value.copy(
            popupStop = stop,
            popupStopDetail = null,
            // Never show another stop's arrivals while this one loads.
            arrivals = emptyList(),
            arrivalsError = false,
            secondsSinceUpdate = 0
        )
        // Address and station code are fetched once and cached forever upstream.
        viewModelScope.launch {
            val details = repository.getStopDetails(stop)
            _uiState.value = _uiState.value.copy(popupStopDetail = details)
            onNetworkSuccess()
        }
        fetchArrivals(stop)
        restartArrivalsPolling(stop)
    }

    fun closeArrivalPopup() {
        if (_uiState.value.popupStop == null) return
        _uiState.value = _uiState.value.copy(popupStop = null, popupStopDetail = null)
        syncArrivalsPolling()
    }

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

    /**
     * Which stop sits under the fixed centre pin after the camera settles. Reported as
     * an id so the map never has to know about Stop instances, and kept out of the
     * selection path on purpose — the hint card is only a preview.
     */
    fun onCenterStopChanged(id: Long?) {
        val stop = id?.let { getStopById(it) }
        _uiState.value = _uiState.value.copy(
            centerStop = stop,
            centerStopDistance = stop?.let { s ->
                _uiState.value.userLocation?.let { s.distanceTo(it.latitude, it.longitude) }
            }
        )
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
            sheetPosition = if (fromUserAction) SheetPosition.EXPANDED else _uiState.value.sheetPosition,
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
        if (!position.isOpen) {
            stopArrivalsPolling()
            // Collapsing means "back to browsing nearby". The stop has to go with it:
            // while it was kept, the next drag upward re-opened the previous stop's
            // arrivals instead of the nearby list.
            _uiState.value = _uiState.value.copy(
                selectedStop = null,
                selectedStopDetail = null,
                arrivals = emptyList(),
                arrivalsError = false
            )
        } else {
            syncArrivalsPolling()
        }
    }

    /**
     * Back navigation handling inside sheet:
     * EXPANDED -> COLLAPSED -> HIDDEN -> returns false (can exit app)
     */
    fun handleBackPressed(): Boolean {
        return when (_uiState.value.sheetPosition) {
            SheetPosition.EXPANDED -> {
                setSheetPosition(SheetPosition.COLLAPSED)
                true
            }
            SheetPosition.COLLAPSED -> {
                if (_uiState.value.isSearchActive) {
                    closeSearch()
                    true
                } else {
                    // one more level before leaving the app: fully close the sheet
                    setSheetPosition(SheetPosition.HIDDEN)
                    true
                }
            }
            // already fully closed — let the system handle back (exit the app)
            SheetPosition.HIDDEN -> false
        }
    }

    fun toggleTheme() {
        val next = !_uiState.value.isDarkTheme
        _uiState.value = _uiState.value.copy(isDarkTheme = next)
        // Survive process death: the choice is read back on the next launch.
        prefs.edit().putBoolean(KEY_DARK_THEME, next).apply()
    }

    fun setAppResumed(resumed: Boolean) {
        isAppResumed = resumed
        if (resumed) syncArrivalsPolling() else stopArrivalsPolling()
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
        pollingStopId = stop.id
        // Reset here and tick inside the loop below. The old free-running freshness timer
        // kept counting while polling was paused (collapsed sheet, app in the background),
        // which is how the label reached 50s and 100s instead of staying inside 0-20s.
        _uiState.value = _uiState.value.copy(secondsSinceUpdate = 0)
        // The gate is "I am still the active target" — not "the sheet is open": the same
        // loop now serves the map panel and the home-screen popup, one at a time.
        pollingJob = viewModelScope.launch {
            while (isActive && isAppResumed && activeArrivalStop()?.id == stop.id) {
                for (second in 1..POLL_INTERVAL_SECONDS) {
                    delay(1000)
                    if (!isActive || !isAppResumed || activeArrivalStop()?.id != stop.id) {
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(secondsSinceUpdate = second)
                }
                if (!isAppResumed || activeArrivalStop()?.id != stop.id) return@launch

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
        // Cleared so the next target is never mistaken for the one just stopped.
        pollingStopId = null
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
    }
}
