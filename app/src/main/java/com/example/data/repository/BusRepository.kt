package com.example.data.repository

import android.content.Context
import com.example.data.api.ApiClient
import com.example.data.api.TourismApi
import com.example.data.cache.AppDatabase
import com.example.data.cache.StopDetailEntity
import com.example.model.AddressRequest
import com.example.model.ArrivalItem
import com.example.model.ArrivalRequest
import com.example.model.ArrivalsResponse
import com.example.model.NoticeResponse
import com.example.model.Stop
import com.example.util.PersianUtils
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

data class CachedStopDetail(
    val stopId: Long,
    val stationCode: String?,
    val addressName: String?,
    val fullAddress: String?,
    val hasPanorama: Boolean = false
)

class BusRepository(
    private val context: Context,
    private val api: TourismApi = ApiClient.api
) {
    private val database = AppDatabase.getDatabase(context)
    private val stopDetailDao = database.stopDetailDao()

    private var stopsList: List<Stop> = emptyList()
    // Pre-indexed normalized names for instant search
    private var normalizedIndex: List<Pair<Stop, String>> = emptyList()

    // In-memory cache for stop details
    private val memoryCache = ConcurrentHashMap<Long, CachedStopDetail>()

    // Serializer to guarantee no concurrent burst requests
    private val networkMutex = Mutex()

    suspend fun loadStops(): List<Stop> = withContext(Dispatchers.IO) {
        if (stopsList.isNotEmpty()) return@withContext stopsList

        try {
            val inputStream = context.assets.open("stops.json")
            val reader = InputStreamReader(inputStream, Charsets.UTF_8)
            val listType = Types.newParameterizedType(List::class.java, Stop::class.java)
            val adapter = ApiClient.moshi.adapter<List<Stop>>(listType)
            val loaded = adapter.fromJson(reader.readText()) ?: emptyList()
            reader.close()
            inputStream.close()

            stopsList = loaded
            normalizedIndex = loaded.map { stop ->
                stop to PersianUtils.normalizeForSearch(stop.name)
            }
            stopsList
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getAllStops(): List<Stop> = stopsList

    fun getStopById(id: Long): Stop? = stopsList.firstOrNull { it.id == id }

    fun searchStops(query: String, limit: Int = 20): List<Stop> {
        val normQuery = PersianUtils.normalizeForSearch(query)
        if (normQuery.isBlank()) return emptyList()

        return normalizedIndex
            .filter { (_, normName) -> normName.contains(normQuery) }
            .take(limit)
            .map { it.first }
    }

    fun getNearbyStops(lat: Double, lng: Double, limit: Int = 10): List<Pair<Stop, Double>> {
        if (stopsList.isEmpty()) return emptyList()
        return stopsList.map { stop ->
            stop to stop.distanceTo(lat, lng)
        }.sortedBy { it.second }.take(limit)
    }

    suspend fun getStopDetails(stop: Stop): CachedStopDetail = withContext(Dispatchers.IO) {
        // 1. Memory cache check
        memoryCache[stop.id]?.let { return@withContext it }

        // 2. Disk cache check
        val diskEntity = stopDetailDao.getStopDetail(stop.id)
        if (diskEntity != null) {
            val cached = CachedStopDetail(
                stopId = diskEntity.stopId,
                stationCode = diskEntity.stationCode,
                addressName = diskEntity.addressName,
                fullAddress = diskEntity.fullAddress,
                hasPanorama = diskEntity.hasPanorama
            )
            memoryCache[stop.id] = cached
            return@withContext cached
        }

        // 3. Fetch from API (once per id)
        var stationCode: String? = null
        var addressName: String? = null
        var fullAddress: String? = null
        var hasPanorama = false

        networkMutex.withLock {
            // Address call: POST with lowercase x, y
            try {
                val addressResp = api.getAddress(
                    AddressRequest(
                        x = stop.lng.toString(),
                        y = stop.lat.toString()
                    )
                )
                if (addressResp.isSuccess && addressResp.success != null) {
                    addressName = addressResp.success.name
                    fullAddress = addressResp.success.address
                }
            } catch (e: Exception) {
                // Non-fatal, use fallback address
            }

            // SearchLocation call to get station code inside pname
            try {
                // centerPoint is "<lng> <lat>" separated by space
                val centerPoint = "${stop.lng} ${stop.lat}"
                val responseBody = api.searchLocation(
                    searchText = stop.name,
                    centerPoint = centerPoint,
                    gid = stop.id,
                    locationType = 1,
                    subtype = 39
                )
                val jsonStr = responseBody.string()
                // Can be single object or array
                if (jsonStr.trim().startsWith("[")) {
                    val arr = JSONArray(jsonStr)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        val pname = obj.optString("pname")
                        stationCode = PersianUtils.extractStationCode(pname)
                    }
                } else if (jsonStr.trim().startsWith("{")) {
                    val obj = JSONObject(jsonStr)
                    val pname = obj.optString("pname")
                    stationCode = PersianUtils.extractStationCode(pname)
                }
            } catch (e: Exception) {
                // Ignore failure
            }

            // Panorama check: GET Point/GetPanoramaImages
            try {
                val panoResp = api.getPanoramaImages(lng = stop.lng, lat = stop.lat)
                hasPanorama = panoResp.success
            } catch (_: Exception) {
                hasPanorama = false
            }

            // Fallback for station code if API didn't return one: use last 4 digits of id
            if (stationCode == null) {
                val idStr = stop.id.toString()
                stationCode = if (idStr.length >= 4) idStr.takeLast(4) else idStr
            }

            val result = CachedStopDetail(
                stopId = stop.id,
                stationCode = stationCode,
                addressName = addressName ?: stop.name,
                fullAddress = fullAddress ?: "اصفهان، ${stop.name}",
                hasPanorama = hasPanorama
            )

            // Save to memory cache
            memoryCache[stop.id] = result

            // Save to disk cache forever
            stopDetailDao.insert(
                StopDetailEntity(
                    stopId = stop.id,
                    stationCode = result.stationCode,
                    addressName = result.addressName,
                    fullAddress = result.fullAddress,
                    hasPanorama = result.hasPanorama
                )
            )

            result
        }
    }

    suspend fun getArrivals(stop: Stop): Result<List<ArrivalItem>> = withContext(Dispatchers.IO) {
        networkMutex.withLock {
            try {
                val resp = api.getAllArrivalsTime(
                    ArrivalRequest(
                        X = stop.lng.toString(),
                        Y = stop.lat.toString()
                    )
                )
                if (resp.isSuccess) {
                    Result.success(resp.data ?: emptyList())
                } else {
                    Result.failure(Exception(resp.message ?: "Failed to get arrivals"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getNotice(): NoticeResponse? = withContext(Dispatchers.IO) {
        try {
            api.getNotice()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Builds GeoJSON FeatureCollection string representation for MapLibre GeoJSONSource
     */
    fun buildGeoJson(selectedStopId: Long?, nearestStopId: Long?): String {
        val sb = StringBuilder(stopsList.size * 120 + 100)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (stop in stopsList) {
            if (!first) sb.append(",")
            first = false

            val isSelected = stop.id == selectedStopId
            val isNearest = stop.id == nearestStopId

            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            sb.append(stop.lng).append(",").append(stop.lat)
            sb.append("]},\"properties\":{\"id\":").append(stop.id)
            sb.append(",\"name\":\"").append(stop.name.replace("\"", "\\\"")).append("\"")
            sb.append(",\"selected\":").append(isSelected)
            sb.append(",\"isNearest\":").append(isNearest)
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }
}
