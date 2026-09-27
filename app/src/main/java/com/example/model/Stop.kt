package com.example.model

import com.squareup.moshi.JsonClass
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@JsonClass(generateAdapter = true)
data class Stop(
    val id: Long,
    val name: String,
    val lng: Double,
    val lat: Double
) {
    /**
     * Haversine distance in meters to a given lat/lng
     */
    fun distanceTo(targetLat: Double, targetLng: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(targetLat - lat)
        val dLng = Math.toRadians(targetLng - lng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat)) * cos(Math.toRadians(targetLat)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
