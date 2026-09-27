package com.example.data.api

import com.example.model.AddressRequest
import com.example.model.AddressResponse
import com.example.model.ArrivalRequest
import com.example.model.ArrivalsResponse
import com.example.model.NoticeResponse
import com.example.model.PanoramaResponse
import com.example.model.SearchLocationResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface TourismApi {

    @POST("Routing/GetAllArrivalsTime")
    suspend fun getAllArrivalsTime(
        @Body request: ArrivalRequest
    ): ArrivalsResponse

    @POST("Routing/GetAddress")
    suspend fun getAddress(
        @Body request: AddressRequest
    ): AddressResponse

    @GET("Search/SearchLocation")
    suspend fun searchLocation(
        @Query("SearchText") searchText: String,
        @Query("centerPoint", encoded = true) centerPoint: String,
        @Query("gid") gid: Long,
        @Query("locationType") locationType: Int = 1,
        @Query("subtype") subtype: Int = 39
    ): okhttp3.ResponseBody

    @GET("Point/GetPanoramaImages")
    suspend fun getPanoramaImages(
        @Query("lng") lng: Double,
        @Query("lat") lat: Double
    ): PanoramaResponse

    @GET("Notice/Get")
    suspend fun getNotice(): NoticeResponse
}

object ApiClient {
    private const val BASE_URL = "https://tourismappapi.isfahan.ir/api/tourism/"

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val headerInterceptor = Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("Referer", "https://tourismapp.isfahan.ir/")
            .header("Origin", "https://tourismapp.isfahan.ir")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) IsfahanBus/1.0")
            .method(original.method, original.body)
            .build()
        chain.proceed(request)
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    val api: TourismApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TourismApi::class.java)
}
