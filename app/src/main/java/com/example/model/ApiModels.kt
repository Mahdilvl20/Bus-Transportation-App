package com.example.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ArrivalRequest(
    @Json(name = "X") val X: String,
    @Json(name = "Y") val Y: String
)

@JsonClass(generateAdapter = true)
data class ArrivalsResponse(
    @Json(name = "isSuccess") val isSuccess: Boolean = false,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: List<ArrivalItem>? = null
)

@JsonClass(generateAdapter = true)
data class ArrivalItem(
    @Json(name = "eta") val eta: String? = null,
    @Json(name = "busCode") val busCode: Int? = null,
    @Json(name = "tripCode") val tripCode: Int? = null,
    @Json(name = "tripName") val tripName: String? = null,
    @Json(name = "destination") val destination: String? = null,
    @Json(name = "lineId") val lineId: Int? = null
)

@JsonClass(generateAdapter = true)
data class AddressRequest(
    @Json(name = "x") val x: String,
    @Json(name = "y") val y: String
)

@JsonClass(generateAdapter = true)
data class AddressResponse(
    @Json(name = "isSuccess") val isSuccess: Boolean = false,
    @Json(name = "success") val success: AddressData? = null
)

@JsonClass(generateAdapter = true)
data class AddressData(
    @Json(name = "name") val name: String? = null,
    @Json(name = "address") val address: String? = null
)

@JsonClass(generateAdapter = true)
data class SearchLocationResponse(
    @Json(name = "gid") val gid: Long? = null,
    @Json(name = "pname") val pname: String? = null,
    @Json(name = "search_result") val searchResult: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "location_type_name") val locationTypeName: String? = null
)

@JsonClass(generateAdapter = true)
data class PanoramaResponse(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class NoticeResponse(
    @Json(name = "isActive") val isActive: Boolean = false,
    @Json(name = "notice") val notice: String? = null
)
