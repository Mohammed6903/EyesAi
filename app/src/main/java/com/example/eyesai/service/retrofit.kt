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
import com.google.gson.JsonParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException

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
class WebSocketImageService(private val serverUrl: String = "wss://jznpl7x6-8000.inc1.devtunnels.ms/api/v1/product/ws/upload-image") {
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
class ReceiptAnalysisService(private val serverUrl: String = "wss://jznpl7x6-8000.inc1.devtunnels.ms/api/v1/receipt/ws/analyze-receipt") {
    private val TAG = "ReceiptAnalysisService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // Keep connection alive
        .retryOnConnectionFailure(true)     // Auto-retry on connection failures
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // SupervisorJob prevents cancellation propagation

    private val _state = MutableStateFlow<ReceiptWebSocketState>(ReceiptWebSocketState.Idle)
    val state: StateFlow<ReceiptWebSocketState> = _state.asStateFlow()

    // Connection timeout variables
    private var connectionJob: Job? = null
    private val CONNECTION_TIMEOUT_MS = 15000L  // 15 seconds connection timeout

    /**
     * Connect to the WebSocket server with timeout
     */
    fun connect() {
        // Check if we're already connected or connecting
        when (_state.value) {
            is ReceiptWebSocketState.Connected -> {
                Log.d(TAG, "Already connected to receipt analysis service")
                return
            }
            is ReceiptWebSocketState.Connecting -> {
                Log.d(TAG, "Already attempting to connect to receipt analysis service")
                return
            }
            else -> {
                // Continue with connection attempt
            }
        }

        _state.value = ReceiptWebSocketState.Connecting

        // Cancel any existing connection job
        connectionJob?.cancel()

        // Start a new timeout job
        connectionJob = coroutineScope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            // If we're still connecting after timeout, mark as error
            if (_state.value == ReceiptWebSocketState.Connecting) {
                _state.value = ReceiptWebSocketState.Error("Connection timeout after ${CONNECTION_TIMEOUT_MS/1000} seconds")
                webSocket?.cancel()
                webSocket = null
            }
        }

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Receipt analysis WebSocket connected")
                connectionJob?.cancel() // Cancel the timeout job
                _state.value = ReceiptWebSocketState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received receipt analysis response: $text")
                processResponse(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message from receipt analysis service")
                // If we receive binary message, we likely shouldn't process it
                // But if needed, we could handle binary responses here
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
                val errorMessage = "Receipt analysis WebSocket error: ${t.message}"
                Log.e(TAG, errorMessage, t)

                // If socket fails while connecting, cancel the timeout job
                connectionJob?.cancel()

                // Check if we have a response with additional error information
                val responseError = response?.let {
                    " HTTP ${it.code}: ${it.message}"
                } ?: ""

                _state.value = ReceiptWebSocketState.Error("Connection error: ${t.message}$responseError")
            }
        })
    }

    /**
     * Send receipt image for analysis with improved error handling
     */
    fun analyzeReceipt(bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
            _state.value = ReceiptWebSocketState.Error("Invalid image: The image appears to be empty or corrupted")
            return
        }

        coroutineScope.launch {
            try {
                ensureConnected()

                if (_state.value !is ReceiptWebSocketState.Connected) {
                    // If not connected after ensureConnected, there was an error
                    // The error state has already been set in ensureConnected
                    return@launch
                }

                _state.value = ReceiptWebSocketState.Sending

                val imageBytes = withContext(Dispatchers.IO) {
                    try {
                        val stream = ByteArrayOutputStream()
                        val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                        if (!compressed) {
                            throw IOException("Failed to compress bitmap")
                        }
                        stream.toByteArray()
                    } catch (e: Exception) {
                        throw IOException("Failed to convert image: ${e.message}", e)
                    }
                }

                if (imageBytes.isEmpty()) {
                    _state.value = ReceiptWebSocketState.Error("Failed to convert image: Empty byte array")
                    return@launch
                }

                val success = webSocket?.send(ByteString.of(*imageBytes))
                if (success == true) {
                    _state.value = ReceiptWebSocketState.Processing
                    Log.d(TAG, "Receipt image sent successfully (${imageBytes.size} bytes)")

                    // Set timeout for processing
                    launch {
                        delay(30000) // 30 seconds timeout
                        if (_state.value == ReceiptWebSocketState.Processing) {
                            _state.value = ReceiptWebSocketState.Error("Analysis timeout: Server took too long to respond")
                        }
                    }
                } else {
                    _state.value = ReceiptWebSocketState.Error("Failed to send receipt image")
                    Log.e(TAG, "Failed to send receipt image")
                }
            } catch (e: Exception) {
                val errorMessage = "Error sending receipt image: ${e.message ?: "Unknown error"}"
                _state.value = ReceiptWebSocketState.Error(errorMessage)
                Log.e(TAG, errorMessage, e)
            }
        }
    }

    /**
     * Ensures that a connection is established before proceeding
     */
    private suspend fun ensureConnected() {
        if (_state.value !is ReceiptWebSocketState.Connected) {
            connect()

            // Wait for connection to establish with timeout
            var timeWaited = 0
            val checkInterval = 500 // 500ms
            val maxWaitTime = 10000 // 10 seconds

            while (_state.value !is ReceiptWebSocketState.Connected &&
                _state.value !is ReceiptWebSocketState.Error &&
                timeWaited < maxWaitTime) {
                delay(checkInterval.toLong())
                timeWaited += checkInterval
            }

            // Check the final state
            when (_state.value) {
                is ReceiptWebSocketState.Connected -> {
                    // Successfully connected
                    Log.d(TAG, "Connection established after ${timeWaited}ms")
                }
                is ReceiptWebSocketState.Error -> {
                    // Error occurred during connection (already handled)
                    Log.d(TAG, "Connection failed with error")
                }
                else -> {
                    // Timed out waiting for connection
                    _state.value = ReceiptWebSocketState.Error("Timed out while establishing connection")
                    disconnect()
                }
            }
        }
    }

    /**
     * Process the receipt analysis response with improved error handling
     */
    private fun processResponse(text: String) {
        if (text.isBlank()) {
            _state.value = ReceiptWebSocketState.Error("Empty response received from server")
            return
        }

        coroutineScope.launch {
            try {
                // First, check if the response is valid JSON
                if (!isValidJson(text)) {
                    _state.value = ReceiptWebSocketState.Error("Invalid JSON response from server")
                    return@launch
                }

                // Check if the response is an error message
                if (text.contains("\"error\":")) {
                    val errorResponse = safeJsonParse<ErrorResponse>(text)

                    _state.value = if (errorResponse != null && !errorResponse.error.isNullOrEmpty()) {
                        ReceiptWebSocketState.Error("Server error: ${errorResponse.error}")
                    } else {
                        ReceiptWebSocketState.Error("Unknown server error")
                    }
                    return@launch
                }

                // Try parsing the actual response
                val response = safeJsonParse<ReceiptAnalysisResponse>(text)

                if (response == null) {
                    _state.value = ReceiptWebSocketState.Error("Failed to parse response")
                    return@launch
                }

                // Validate key response fields
                val validationError = validateResponseFields(response)
                if (validationError != null) {
                    _state.value = ReceiptWebSocketState.Error(validationError)
                    return@launch
                }

                // Update state with success
                _state.value = ReceiptWebSocketState.Success(response)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing receipt analysis response", e)
                _state.value = ReceiptWebSocketState.Error("Error processing response: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Validate that all required fields in the response are present and valid
     */
    private fun validateResponseFields(response: ReceiptAnalysisResponse): String? {
        // Check if receipt_info is null
        if (response.receipt_info == null) {
            return "Missing receipt information"
        }

        // Check merchant info
        val merchant = response.receipt_info.merchant
        if (merchant == null) {
            return "Missing merchant information"
        }

        // Check payment details
        val payment = response.receipt_info.payment_details
        if (payment == null) {
            return "Missing payment details"
        }

        // Check if items is null
        if (response.receipt_info.items == null) {
            return "Missing receipt items"
        }

        // Check if receipt_summary is null
        if (response.receipt_summary == null) {
            return "Missing receipt summary"
        }

        // Check if high_value_items is null

        // Check if accessibility_details is null
        if (response.accessibility_details == null) {
            return "Missing accessibility details"
        }

        // All checks passed
        return null
    }

    /**
     * Check if a string is valid JSON
     */
    private fun isValidJson(text: String): Boolean {
        return try {
            JsonParser.parseString(text)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON: ${e.message}")
            false
        }
    }

    /**
     * Safely parse JSON into a specified type with error handling
     */
    private inline fun <reified T> safeJsonParse(json: String): T? {
        return try {
            gson.fromJson(json, T::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON to ${T::class.java.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Disconnect from the WebSocket server
     */
    fun disconnect() {
        connectionJob?.cancel()
        webSocket?.close(1000, "Closing receipt analysis connection")
        webSocket = null
        _state.value = ReceiptWebSocketState.Disconnected
    }

    /**
     * Simple error response model
     */
    private data class ErrorResponse(val error: String?)

    /**
     * Get a summarized version of the receipt for TTS or display with null safety
     */
    fun getAccessibleSummary(response: ReceiptAnalysisResponse): String {
        val merchant = response.receipt_info?.merchant
        val payment = response.receipt_info?.payment_details
        val accessibility = response.accessibility_details ?: return "Receipt information is unavailable."
        val highValueItems = response.receipt_summary?.high_value_items

        return buildString {
            // Merchant Info
            merchant?.let { m ->
                if (m.name.isNotEmpty()) {
                    append("Receipt from ${m.name}")
                } else {
                    append("Receipt")
                }

                if (m.timestamp.isNotEmpty()) {
                    append(" on ${m.timestamp}. ")
                } else {
                    append(". ")
                }
            } ?: append("Receipt. ")

            // Payment Info
            payment?.let { p ->
                val currency = p.currency.orEmpty()
                val total = p.total.takeIf { !it.isNaN() }?.toString() ?: ""
                val method = p.payment_method.orEmpty()

                if (total.isNotEmpty()) {
                    append("Total amount: ")
                    if (currency.isNotEmpty()) append("$currency ")
                    append("$total")
                    if (method.isNotEmpty()) append(" paid via $method")
                    append(". ")
                }
            }

            // Accessibility: Brief Overview
            if (accessibility.brief_overview.isNotEmpty()) {
                append("${accessibility.brief_overview} ")
            }

            // Accessibility: Critical Information
            if (!accessibility.critical_information.isNullOrEmpty()) {
                append("Important information: ${accessibility.critical_information.joinToString(", ")}. ")
            }

            // Accessibility: Audio Indicators
            accessibility.audio_indicators?.let {
                if (it.payment_alert.isNotEmpty()) {
                    append(it.payment_alert)
                }
            }

            // Receipt Summary: High Value Items
            if (!highValueItems.isNullOrEmpty()) {
                val topItem = highValueItems.firstOrNull()
                topItem?.let {
                    if (it.item_name.isNotEmpty() && !it.item_total.isNaN()) {
                        append("Highest priced item: ${it.item_name} at ${it.item_total}. ")
                    }
                }
            }

            // If summary is empty, provide a fallback message
            if (length == 0) {
                append("Receipt information processed but details are limited.")
            }
        }
    }

    /**
     * Reset to idle state
     */
    fun reset() {
        _state.value = ReceiptWebSocketState.Idle
    }
}