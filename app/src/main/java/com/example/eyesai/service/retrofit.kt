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

data class ReceiptAnalysisResponse(
    val receipt_info: ReceiptInfo,
    val receipt_summary: ReceiptSummary,
    val accessibility_details: AccessibilityDetails
)

data class ReceiptInfo(
    val merchant: Merchant,
    val payment_details: PaymentDetails,
    val items: List<ReceiptItem>
)

data class Merchant(
    val name: String,
    val address: String,
    val contact: String,
    val timestamp: String
)

data class PaymentDetails(
    val subtotal: Double,
    val tax: Double,
    val tip: Double,
    val total: Double,
    val payment_method: String,
    val currency: String,
    val card_last_digits: String?
)

data class ReceiptItem(
    val item_name: String,
    val quantity: Double,
    val unit_price: Double,
    val item_total: Double,
    val discount: Double?,
    val description: String?
)

data class ReceiptSummary(
    val total_items: Int,
    val high_value_items: List<HighValueItem>,
    val special_notices: List<String>
)

data class HighValueItem(
    val item_name: String,
    val item_total: Double
)

data class AccessibilityDetails(
    val receipt_type: String,
    val brief_overview: String,
    val critical_information: List<String>,
    val audio_indicators: AudioIndicators?
)

data class AudioIndicators(
    val highest_item: String,
    val payment_alert: String
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

/**
 * States for WebSocket connection specifically for receipt analysis
 */
sealed class ReceiptWebSocketState {
    object Idle : ReceiptWebSocketState()
    object Connecting : ReceiptWebSocketState()
    object Connected : ReceiptWebSocketState()
    object Sending : ReceiptWebSocketState()
    object Processing : ReceiptWebSocketState()
    data class Success(val response: ReceiptAnalysisResponse) : ReceiptWebSocketState()
    data class Error(val message: String) : ReceiptWebSocketState()
    object Disconnected : ReceiptWebSocketState()
}

/**
 * WebSocket service for receipt image analysis
 */
class ReceiptAnalysisService(private val serverUrl: String = "wss://rbd6wn7l-8000.inc1.devtunnels.ms/receipt") {
    private val TAG = "ReceiptAnalysisService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val _state = MutableStateFlow<ReceiptWebSocketState>(ReceiptWebSocketState.Idle)
    val state: StateFlow<ReceiptWebSocketState> = _state.asStateFlow()

    /**
     * Connect to the WebSocket server
     */
    fun connect() {
        if (_state.value == ReceiptWebSocketState.Connected) {
            Log.d(TAG, "Already connected to receipt analysis service")
            return
        }

        _state.value = ReceiptWebSocketState.Connecting
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Receipt analysis WebSocket connected")
                _state.value = ReceiptWebSocketState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received receipt analysis response")
                processResponse(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message from receipt analysis service")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Receipt analysis WebSocket closing: $code, $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Receipt analysis WebSocket closed: $code, $reason")
                _state.value = ReceiptWebSocketState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Receipt analysis WebSocket error: ${t.message}", t)
                _state.value = ReceiptWebSocketState.Error("Connection error: ${t.message}")
            }
        })
    }

    /**
     * Send receipt image for analysis
     */
    fun analyzeReceipt(bitmap: Bitmap) {
        coroutineScope.launch {
            try {
                if (_state.value != ReceiptWebSocketState.Connected) {
                    connect()
                    // Wait for connection to establish
                    var attempts = 0
                    while (_state.value != ReceiptWebSocketState.Connected && attempts < 10) {
                        withContext(Dispatchers.IO) {
                            Thread.sleep(500)
                        }
                        attempts++
                    }

                    if (_state.value != ReceiptWebSocketState.Connected) {
                        _state.value = ReceiptWebSocketState.Error("Failed to connect to receipt analysis server")
                        return@launch
                    }
                }

                _state.value = ReceiptWebSocketState.Sending

                val imageBytes = withContext(Dispatchers.IO) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    stream.toByteArray()
                }

                val success = webSocket?.send(ByteString.of(*imageBytes))
                if (success == true) {
                    _state.value = ReceiptWebSocketState.Processing
                    Log.d(TAG, "Receipt image sent successfully")
                } else {
                    _state.value = ReceiptWebSocketState.Error("Failed to send receipt image")
                    Log.e(TAG, "Failed to send receipt image")
                }
            } catch (e: Exception) {
                _state.value = ReceiptWebSocketState.Error("Error sending receipt image: ${e.message}")
                Log.e(TAG, "Error sending receipt image", e)
            }
        }
    }

    /**
     * Process the receipt analysis response
     */
    private fun processResponse(text: String) {
        coroutineScope.launch {
            try {
                // Check if the response is an error message
                if (text.contains("\"error\":")) {
                    val errorResponse = gson.fromJson(text, ErrorResponse::class.java)
                    _state.value = ReceiptWebSocketState.Error(errorResponse.error)
                    return@launch
                }

                val response = gson.fromJson(text, ReceiptAnalysisResponse::class.java)
                _state.value = ReceiptWebSocketState.Success(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing receipt analysis response", e)
                _state.value = ReceiptWebSocketState.Error("Error processing response: ${e.message}")
            }
        }
    }

    /**
     * Disconnect from the WebSocket server
     */
    fun disconnect() {
        webSocket?.close(1000, "Closing receipt analysis connection")
        webSocket = null
        _state.value = ReceiptWebSocketState.Disconnected
    }

    /**
     * Simple error response model
     */
    private data class ErrorResponse(val error: String)

    /**
     * Get a summarized version of the receipt for TTS or display
     */
    fun getAccessibleSummary(response: ReceiptAnalysisResponse): String {
        val merchant = response.receipt_info.merchant
        val payment = response.receipt_info.payment_details
        val accessibility = response.accessibility_details

        return buildString {
            append("Receipt from ${merchant.name} on ${merchant.timestamp}. ")
            append("Total amount: ${payment.currency} ${payment.total} paid via ${payment.payment_method}. ")

            // Add brief overview from accessibility section
            if (accessibility.brief_overview.isNotEmpty()) {
                append("${accessibility.brief_overview} ")
            }

            // Add high value items
            val highValueItems = response.receipt_summary.high_value_items
            if (highValueItems.isNotEmpty()) {
                append("Highest priced item: ${highValueItems[0].item_name} at ${highValueItems[0].item_total}. ")
            }

            // Add critical information if available
            if (accessibility.critical_information.isNotEmpty()) {
                append("Important information: ${accessibility.critical_information.joinToString(", ")}. ")
            }

            // Audio indicators if available
            accessibility.audio_indicators?.let {
                append(it.payment_alert)
            }
        }
    }
}