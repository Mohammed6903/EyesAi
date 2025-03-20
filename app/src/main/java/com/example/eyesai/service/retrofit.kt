package com.example.eyesai.service

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
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

/**
 * Response model for market survey data
 */
data class MarketSurveyResponse(
    val product_info: ProductInfo,
    val market_survey: MarketSurvey,
    val purchase_details: PurchaseDetails,
    val accessibility: Accessibility
)

data class ProductInfo(
    val name: String,
    val description: String,
    val features: List<String>,
    val specifications: Map<String, String>
)

data class MarketSurvey(
    val average_price_in_inr: Int,
    val price_range: PriceRange,
    val competitor_products: List<CompetitorProduct>,
    val customer_reviews: List<CustomerReview>
)

data class PriceRange(
    val min: Int,
    val max: Int
)

data class CompetitorProduct(
    val name: String,
    val vendor: String,
    val price_in_inr: Int,
    val url: String
)

data class CustomerReview(
    val review_summary: String,
    val rating: Double
)

data class PurchaseDetails(
    val vendor_details: List<VendorDetail>,
    val booking_sites: List<BookingSite>
)

data class VendorDetail(
    val vendor_name: String,
    val price_in_inr: Int,
    val offer_details: String,
    val purchase_url: String
)

data class BookingSite(
    val site: String,
    val link: String,
    val price_range_description: String
)

data class Accessibility(
    val image_alt_text: String,
    val detected_text: String,
    val additional_info: String
)

/**
 * States for WebSocket connection
 */
sealed class WebSocketState {
    object Idle : WebSocketState()
    object Connecting : WebSocketState()
    object Connected : WebSocketState()
    object Sending : WebSocketState()
    object Processing : WebSocketState()
    data class Success(val response: MarketSurveyResponse) : WebSocketState()
    data class Error(val message: String) : WebSocketState()
    object Disconnected : WebSocketState()
}

/**
 * WebSocket service for image analysis and market survey
 */
class WebSocketImageService(private val serverUrl: String = "wss://rbd6wn7l-8000.inc1.devtunnels.ms/ws") {
    private val TAG = "WebSocketImageService"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val _state = MutableStateFlow<WebSocketState>(WebSocketState.Idle)
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    /**
     * Connect to the WebSocket server
     */
    fun connect() {
        if (_state.value == WebSocketState.Connected) {
            Log.d(TAG, "Already connected")
            return
        }

        _state.value = WebSocketState.Connecting
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _state.value = WebSocketState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                processResponse(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code, $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code, $reason")
                _state.value = WebSocketState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                _state.value = WebSocketState.Error("Connection error: ${t.message}")
            }
        })
    }

    /**
     * Send image for analysis
     */
    fun sendImage(bitmap: Bitmap) {
        coroutineScope.launch {
            try {
                if (_state.value != WebSocketState.Connected) {
                    connect()
                    // Wait for connection to establish
                    var attempts = 0
                    while (_state.value != WebSocketState.Connected && attempts < 10) {
                        withContext(Dispatchers.IO) {
                            Thread.sleep(500)
                        }
                        attempts++
                    }

                    if (_state.value != WebSocketState.Connected) {
                        _state.value = WebSocketState.Error("Failed to connect to server")
                        return@launch
                    }
                }

                _state.value = WebSocketState.Sending
                val imageBytes = withContext(Dispatchers.IO) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    stream.toByteArray()
                }

                val success = webSocket?.send(ByteString.of(*imageBytes))
                if (success == true) {
                    _state.value = WebSocketState.Processing
                    Log.d(TAG, "Image sent successfully")
                } else {
                    _state.value = WebSocketState.Error("Failed to send image")
                    Log.e(TAG, "Failed to send image")
                }
            } catch (e: Exception) {
                _state.value = WebSocketState.Error("Error sending image: ${e.message}")
                Log.e(TAG, "Error sending image", e)
            }
        }
    }

    /**
     * Process the server response
     */
    private fun processResponse(text: String) {
        coroutineScope.launch {
            try {
                val response = gson.fromJson(text, MarketSurveyResponse::class.java)
                _state.value = WebSocketState.Success(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing response", e)
                _state.value = WebSocketState.Error("Error processing response: ${e.message}")
            }
        }
    }

    /**
     * Disconnect from the WebSocket server
     */
    fun disconnect() {
        webSocket?.close(1000, "Closing connection")
        webSocket = null
        _state.value = WebSocketState.Disconnected
    }
}