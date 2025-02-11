package com.example.eyesai.service

import com.example.eyesai.ui.screen.BarcodeLookupResponse
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface BarcodeLookupService {
    @GET("products")
    fun getProductDetails(
        @Query("barcode") barcode: String,
        @Query("formatted") formatted: String = "y",
        @Query("key") apiKey: String = "4mlkh3dx20ffzxkqmnano7cramkqye"
    ): Call<BarcodeLookupResponse>
}

object RetrofitClient {
    private const val BASE_URL = "https://api.barcodelookup.com/v3/"

    val instance: BarcodeLookupService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BarcodeLookupService::class.java)
    }
}