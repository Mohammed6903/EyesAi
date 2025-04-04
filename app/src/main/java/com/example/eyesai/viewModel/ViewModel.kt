package com.example.eyesai.viewModel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.example.eyesai.AppScreen
import com.example.eyesai.BuildConfig
import com.example.eyesai.R
import com.example.eyesai.helpers.ImageLabelerHelper
import com.example.eyesai.service.MarketSurveyResponse
import com.example.eyesai.service.ReceiptAnalysisResponse
import com.example.eyesai.service.ReceiptAnalysisService
import com.example.eyesai.service.ReceiptWebSocketState
import com.example.eyesai.service.WebSocketImageService
import com.example.eyesai.service.WebSocketState
import com.example.eyesai.viewModel.NavigationViewModel.ImageLabelsState
import com.example.eyesai.viewModel.NavigationViewModel.ObjectDetectionState
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.mlkit.vision.label.ImageLabel
import com.itextpdf.kernel.pdf.PdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.text.similarity.LevenshteinDistance
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument

// Define UI states for different features
sealed class AppState {
    object Idle : AppState()
    object Loading : AppState()
    data class Success(val message: String) : AppState()
    data class Error(val error: String) : AppState()
}

// Define UI states for Gemini Vision task
sealed class GeminiState {
    data object Idle : GeminiState()
    object Loading : GeminiState()
    data class Success(val response: String) : GeminiState()
    data class Error(val error: String) : GeminiState()
    data class File(val uri: Uri) : GeminiState()
}

// Define voice commands for different screens
enum class ScreenType(@StringRes val title: Int) {
    Home(title = R.string.home),
    Navigation(title = R.string.Navigation),
    Shop(title = R.string.Shop),
    Describe(title = R.string.Describe),
    Notes(title = R.string.notes)
}

interface CommandHandler {
    fun handleCommand(command: String, navController: NavHostController)
    fun getCommands(): List<String>
}

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    // StateFlow for general UI state
    val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> = _state.asStateFlow()

    // StateFlow for Gemini Vision task
    private val _geminiState = MutableStateFlow<GeminiState>(GeminiState.Idle)
    val geminiState: StateFlow<GeminiState> = _geminiState.asStateFlow()

    private val _screenType = MutableStateFlow(ScreenType.Home)
    val screenType: StateFlow<ScreenType> = _screenType.asStateFlow()

    private val _isVoiceCommandActive = MutableStateFlow(false)

    private val _shouldCapture = MutableStateFlow(false)
    val shouldCapture: StateFlow<Boolean> = _shouldCapture.asStateFlow()

    private var tts: TextToSpeech = TextToSpeech(application.applicationContext, this)

    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes.asStateFlow()

    // Command handlers for each screen
    private val commandHandlers = mutableMapOf<ScreenType, CommandHandler>()

    // Gemini Model
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = BuildConfig.GEMINI_API_KEY,
        systemInstruction = content(role = "System"){
            text(
                "Answer the following question directly and concisely. Avoid any introductory phrases or filler words. \n" +
                "Provide the information in a way that is easy to understand when read aloud by a screen reader."
            )
        }
    )

    private val contentResolver = application.contentResolver

    private val _shouldCaptureForMarketSurvey = MutableStateFlow(false)
    val shouldCaptureForMarketSurvey: StateFlow<Boolean> = _shouldCaptureForMarketSurvey.asStateFlow()

    private val _shouldCaptureForReceiptAnalysis = MutableStateFlow(false)
    private val _receiptAnalysisData = MutableStateFlow<ReceiptAnalysisResponse?>(null)
    val receiptAnalysisData: StateFlow<ReceiptAnalysisResponse?> = _receiptAnalysisData.asStateFlow()

    // Initialize WebSocket service
    val webSocketService = WebSocketImageService()

    // LiveData for market survey results
    val _marketSurveyData = MutableLiveData<MarketSurveyResponse?>()
    val marketSurveyData: LiveData<MarketSurveyResponse?> = _marketSurveyData

    // LiveData for WebSocket state
    val _webSocketState = MutableLiveData<WebSocketState>(WebSocketState.Idle)
    val webSocketState: LiveData<WebSocketState> = _webSocketState

    private val _objectDetectionState = MutableStateFlow<ObjectDetectionState>(ObjectDetectionState.Loading)
    val objectDetectionState = _objectDetectionState.asStateFlow()

    private val _imageLabelsState = MutableStateFlow<ImageLabelsState>(ImageLabelsState.Loading)
    val imageLabelsState = _imageLabelsState.asStateFlow()

    fun getObjectDetections(): List<BoxWithText> {
        return when (val state = _objectDetectionState.value) {
            is ObjectDetectionState.Success -> state.detections.toList()
            else -> emptyList()
        }
    }

    fun copyObjectDetections(detections: List<BoxWithText>, inferenceTime: Long = 0): ObjectDetectionState {
        return if (detections.isEmpty()) {
            ObjectDetectionState.NoObjectsDetected
        } else {
            val detectionsCopy = detections.map { detection ->
                BoxWithText(
                    box = Rect(detection.box), // Create a new Rect
                    text = detection.text,
                    confidence = detection.confidence
                )
            }
            ObjectDetectionState.Success(detectionsCopy, inferenceTime)
        }
    }

    fun getImageLabels(): List<ImageLabel> {
        return when (val state = _imageLabelsState.value) {
            is ImageLabelsState.Success -> state.labels.toList()
            else -> emptyList()
        }
    }

    fun copyImageLabels(labels: List<ImageLabel>, inferenceTime: Long = 0): ImageLabelsState {
        return if (labels.isEmpty()) {
            ImageLabelsState.NoLabelsDetected
        } else {
            val labelsCopy = labels.map { label ->
                ImageLabel(
                    label.text,
                    label.confidence,
                    label.index
                )
            }
            ImageLabelsState.Success(labelsCopy, inferenceTime)
        }
    }

    fun updateObjectDetectionState(detections: List<BoxWithText>, inferenceTime: Long = 0) {
        _objectDetectionState.value = copyObjectDetections(detections, inferenceTime)
    }

    fun updateImageLabelsState(labels: List<ImageLabel>, inferenceTime: Long = 0) {
        _imageLabelsState.value = copyImageLabels(labels, inferenceTime)
    }

    init {
        setupCommandHandlers()
        initializeWebSocketService()
    }

    private fun setupCommandHandlers() {
        commandHandlers[ScreenType.Home] = HomeCommandHandler()
        commandHandlers[ScreenType.Describe] = DescribeCommandHandler()
        commandHandlers[ScreenType.Shop] = ShopCommandHandler()
        commandHandlers[ScreenType.Navigation] = NavigationCommandHandler()
        commandHandlers[ScreenType.Notes] = NotesCommandHandler()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onCleared() {
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }

    // Trie Node for storing commands
    private class TrieNode {
        val children = mutableMapOf<Char, TrieNode>()
        var command: String? = null
    }

    // Build a Trie from the list of commands
    private fun buildTrie(commands: List<String>): TrieNode {
        val root = TrieNode()
        for (command in commands) {
            var node = root
            for (char in command) {
                node = node.children.getOrPut(char) { TrieNode() }
            }
            node.command = command
        }
        return root
    }

    // Search for the closest matching command using Trie and Levenshtein Distance
    private fun findClosestMatch(input: String, trie: TrieNode): String? {
        val levenshtein = LevenshteinDistance()
        var closestMatch: String? = null
        var minDistance = Int.MAX_VALUE

        fun dfs(node: TrieNode, current: String) {
            node.command?.let { command ->
                val distance = levenshtein.apply(input.lowercase(), command.lowercase())
                if (distance < minDistance) {
                    minDistance = distance
                    closestMatch = command
                }
            }
            for ((char, child) in node.children) {
                dfs(child, current + char)
            }
        }

        dfs(trie, "")
        return closestMatch
    }

    fun handleVoiceCommand(command: String, navController: NavHostController) {
        viewModelScope.launch {
            _state.value = AppState.Loading
            _isVoiceCommandActive.value = true
            try {
                val handler = commandHandlers[screenType.value]
                if (handler != null) {
                    val lowerCaseCommand = command.lowercase()
                    val parameterizedCommands = listOf(
                        "add note", "read notes", "delete note", "hey", "search for",
                        "how far", "is there", "can you see", "do you see"
                    )

                    if (parameterizedCommands.any { lowerCaseCommand.startsWith(it) }) {
                        handler.handleCommand(command, navController)
                    } else {
                        // Use Trie and Levenshtein for non-parameterized commands
                        val trie = buildTrie(handler.getCommands())
                        val closestMatch = findClosestMatch(command, trie)
                        val distanceThreshold = 3 // Adjust tolerance level

                        if (closestMatch != null &&
                            LevenshteinDistance().apply(command, closestMatch) <= distanceThreshold) {
                            handler.handleCommand(closestMatch, navController)
//                            speak("Executing: $closestMatch")
                        } else {
                            _state.value = AppState.Error("Invalid command: $command")
//                            speak("I didn't understand that command.")
                        }
                    }
                } else {
                    _state.value = AppState.Error("No handler found for current screen.")
                    speak("No handler found for current screen.")
                }
            } catch (e: Exception) {
                _state.value = AppState.Error("Command handling failed: ${e.message}")
                speak("Error processing command.")
            }
        }
    }

    val navigationCommands = listOf(
        "go to home", "go to shop", "go to describe", "go to notes", "go to navigation",
        "navigate to home", "navigate to shop", "navigate to notes", "navigate to navigation", "navigate to describe",
        "open home", "open shop", "open describe", "open notes", "open navigation"
    )

    private inner class HomeCommandHandler : CommandHandler {
        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }
            when (lowercaseCommand) {
                "help" -> describeCurrentScreen(ScreenType.Home)
                else -> _state.value = AppState.Success("Command executed: $command")
            }
        }

        override fun getCommands(): List<String> {
            return navigationCommands + listOf("help")
        }
    }

    private inner class DescribeCommandHandler : CommandHandler {
        fun describeScenery(command: String, route: String) {
            val prompt = command.substringAfter("hey", "").trim()
            Log.d("Prompt", prompt)
            if (route == AppScreen.Describe.name) {
                updateCaptureState(true)
                viewModelScope.launch {
                    while (_geminiState.value !is GeminiState.File) {
                        delay(500)
                    }
                    updateCaptureState(false)
                    val instruction = """
                    User Query: $prompt
                """.trimIndent()
                    val uri = (_geminiState.value as GeminiState.File).uri
                    val bitmap = uriToBitmap(uri)
                    if (bitmap != null) {
                        sendPrompt(bitmap, instruction)
                    }
                    val successMsg = "Captured image. Please wait for 2-3 minutes."
                    speak(successMsg)
                    _state.value = AppState.Success(successMsg)
                }
            } else if (_geminiState.value is GeminiState.Error) {
                val errorMsg = "No image captured. Please try again."
                speak(errorMsg)
                Log.i("GEMINIError", (_geminiState.value as GeminiState.Error).error)
                _state.value = AppState.Error(errorMsg)
            }
        }

        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }
            if (lowercaseCommand.startsWith("hey", ignoreCase = true)) {
                describeScenery(command, AppScreen.Describe.name)
            }
        }

        override fun getCommands(): List<String> {
            return listOf(
                "hey",
            ) + navigationCommands
        }
    }

    inner class ShopCommandHandler : CommandHandler {
        // This function should be called when the "capture" command is recognized
        fun captureForMarketSurvey(bitmap: Bitmap) {
            if (_shouldCaptureForMarketSurvey.value) {
                _shouldCaptureForMarketSurvey.value = false
                speak("Image captured. Analyzing product for market survey.")
                _state.value = AppState.Loading
                captureAndAnalyzeProduct(bitmap)
            }
        }

        private fun displayMarketSurveyResults() {
            val marketSurveyData = marketSurveyData.value
            if (marketSurveyData != null) {
                val productName = marketSurveyData.product_info.name
                val avgPrice = marketSurveyData.market_survey.average_price_in_inr
                val priceRange = "₹${marketSurveyData.market_survey.price_range.min} to ₹${marketSurveyData.market_survey.price_range.max}"

                val speechText = "Market survey for $productName. Average price is ₹$avgPrice. Price range is $priceRange."
                speak(speechText)
                Log.d("Results", speechText)
                _state.value = AppState.Success("Market survey results displayed")
            } else {
                speak("No market survey data available. Please analyze a product first.")
                _state.value = AppState.Error("No market survey data available")
            }
        }

        private fun displayDetailedMarketSurveyResults() {
            val marketSurveyData = marketSurveyData.value
            if (marketSurveyData != null) {
                val productName = marketSurveyData.product_info.name
                val productDescription = marketSurveyData.product_info.description
                val features = marketSurveyData.product_info.features.joinToString(", ")
                val specifications = marketSurveyData.product_info.specifications.entries.joinToString(", ") { "${it.key}: ${it.value}" }

                val avgPrice = marketSurveyData.market_survey.average_price_in_inr
                val priceRange = "₹${marketSurveyData.market_survey.price_range.min} to ₹${marketSurveyData.market_survey.price_range.max}"

                val competitorInfo = marketSurveyData.market_survey.competitor_products.joinToString("\n") {
                    "- ${it.name} by ${it.vendor} at ₹${it.price_in_inr}"
                }

                val customerReviews = marketSurveyData.market_survey.customer_reviews.joinToString("\n") {
                    "- \"${it.review_summary}\" rated ${it.rating} stars"
                }

                val vendorDetails = marketSurveyData.purchase_details.vendor_details.joinToString("\n") {
                    "- ${it.vendor_name}: ₹${it.price_in_inr}, Offer: ${it.offer_details}"
                }

                val bookingSites = marketSurveyData.purchase_details.booking_sites.joinToString("\n") {
                    "- ${it.site}: ${it.price_range_description}"
                }

                val accessibilityInfo = """
            Image Alt Text: ${marketSurveyData.accessibility.image_alt_text}
            Detected Text: ${marketSurveyData.accessibility.detected_text}
            Additional Info: ${marketSurveyData.accessibility.additional_info}
        """.trimIndent()

                val speechText = """
            Market Survey for $productName.
            Description: $productDescription.
            Features: $features.
            Specifications: $specifications.
            Average Price: ₹$avgPrice.
            Price Range: $priceRange.
            Competitor Products:
            $competitorInfo
            Customer Reviews:
            $customerReviews
            Vendor Details:
            $vendorDetails
            Booking Sites:
            $bookingSites
            Accessibility Information:
            $accessibilityInfo
        """.trimIndent()

                speak(speechText)
                Log.d("DetailedSurveyResults", speechText)
                _state.value = AppState.Success("Detailed market survey results displayed")
            } else {
                speak("No market survey data available. Please analyze a product first.")
                _state.value = AppState.Error("No market survey data available")
            }
        }


        private fun cancelMarketSurvey() {
            _shouldCaptureForMarketSurvey.value = false
            speak("Market survey canceled")
            _state.value = AppState.Idle
        }

        /**
         * Function to capture and analyze image for market survey
         */
        fun captureAndAnalyzeProduct(bitmap: Bitmap) {
            viewModelScope.launch {
                try {
                    speak("Capturing image for market survey analysis")
                    _state.value = AppState.Loading
                    webSocketService.connect()
                    webSocketService.sendImage(bitmap)
                } catch (e: Exception) {
                    _state.value = AppState.Error("Error capturing image: ${e.message}")
                    speak("Error capturing image. Please try again.")
                }
            }
        }

        private fun initiateProductAnalysis() {
            _state.value = AppState.Loading
            _shouldCaptureForMarketSurvey.value = true
        }

        private fun addItemToShoppingList() {
            speak("Item added to shopping list")
            _state.value = AppState.Success("Item added to shopping list")
        }

        private fun viewShoppingCart() {
            speak("Opening shopping cart")
            _state.value = AppState.Success("Shopping cart opened")
        }

        // Receipt analysis service
        private val receiptAnalysisService = ReceiptAnalysisService()

        private fun initiateReceiptAnalysis() {
            _state.value = AppState.Loading
            _shouldCaptureForReceiptAnalysis.value = true
        }

        private fun displayReceiptDetails() {
            val receiptData = _receiptAnalysisData.value
            if (receiptData != null) {
                val summary = receiptAnalysisService.getAccessibleSummary(receiptData)
                speak(summary)
                Log.d("Results", summary)
                _state.value = AppState.Success("Receipt analysis displayed")
            } else {
                speak("No bill analysis data available. Please scan a bill first.")
                _state.value = AppState.Error("No bill analysis data available")
            }
        }

        private fun cancelReceiptAnalysis() {
            _shouldCaptureForReceiptAnalysis.value = false
            speak("Receipt analysis canceled")
            _state.value = AppState.Idle
        }

        // Function to capture and analyze bill
        fun captureAndAnalyzeReceipt(bitmap: Bitmap) {
            viewModelScope.launch {
                try {
                    speak("Capturing image for bill analysis")
                    _state.value = AppState.Loading

                    // Connect to bill analysis service
                    receiptAnalysisService.connect()

                    // Send the bill image for analysis
                    receiptAnalysisService.analyzeReceipt(bitmap)

                    // Monitor the state of the bill analysis service
                    receiptAnalysisService.state.collect { state ->
                        when (state) {
                            is ReceiptWebSocketState.Success -> {
                                _receiptAnalysisData.value = state.response
                                val summary = receiptAnalysisService.getAccessibleSummary(state.response)
                                speak("Receipt analysis complete. $summary")
                                _state.value = AppState.Success("Receipt analysis complete")
                            }
                            is ReceiptWebSocketState.Error -> {
                                speak("Error analyzing bill: ${state.message}")
                                _state.value = AppState.Error("Error analyzing bill: ${state.message}")
                            }
                            is ReceiptWebSocketState.Processing -> {
                                speak("Processing bill, please wait...")
                                _state.value = AppState.Loading
                            }
                            is ReceiptWebSocketState.Disconnected -> {
                                _state.value = AppState.Idle
                            }
                            else -> {}
                        }
                    }

                } catch (e: Exception) {
                    _state.value = AppState.Error("Error capturing bill: ${e.message}")
                    Log.e("Bill", e.message.toString())
                    speak("Error capturing bill. Please try again.")
                }
            }
        }

        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()

            // Handle navigation commands
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }

            when {
                lowercaseCommand.contains("analyze product", true) ||
                lowercaseCommand == "scan product" ||
                lowercaseCommand == "market survey" -> {
                    initiateProductAnalysis()
                }
                lowercaseCommand.contains("analyze bill", true) ||
                lowercaseCommand == "scan receipt" ||
                lowercaseCommand == "bill analysis" -> {
                    initiateReceiptAnalysis()
                }
                lowercaseCommand == "add item to list" ||
                lowercaseCommand == "add to shopping list" -> {
                    addItemToShoppingList()
                }
                lowercaseCommand == "view shopping cart" ||
                lowercaseCommand == "show cart" ||
                        lowercaseCommand == "check cart" -> {
                    viewShoppingCart()
                }
                lowercaseCommand.contains("show receipt details", true) ||
                        lowercaseCommand.contains("display bill analysis", true) -> {
                    displayReceiptDetails()
                }
                lowercaseCommand.contains("show market survey", true) ||
                        lowercaseCommand.contains("display survey results", true) -> {
                    displayMarketSurveyResults()
                }
                lowercaseCommand.contains("show detailed market survey", true) ||
                        lowercaseCommand.contains("display detailed survey results", true) -> {
                    displayDetailedMarketSurveyResults()
                }
                lowercaseCommand == "cancel market survey" -> {
                    cancelMarketSurvey()
                }
                lowercaseCommand == "cancel receipt analysis" -> {
                    cancelReceiptAnalysis()
                }
                lowercaseCommand == "help" -> {
                    describeCurrentScreen(ScreenType.Shop)
                }
                else -> {
                    speak("Command not recognized in shop screen")
                    _state.value = AppState.Error("Command not recognized: $command")
                }
            }
        }

        override fun getCommands(): List<String> {
            return listOf(
                "analyze product",
                "scan product",
                "market survey",
                "analyze bill",
                "scan receipt",
                "bill analysis",
                "add item to list",
                "view shopping cart",
                "show market survey",
                "show detailed market survey",
                "display survey results",
                "show bill details",
                "display bill analysis",
                "cancel market survey",
                "cancel bill analysis",
                "help"
            ) + navigationCommands
        }
    }

    // This function should be called when the "capture" command is recognized
    fun captureForReceiptAnalysis(bitmap: Bitmap) {
        if (_shouldCaptureForReceiptAnalysis.value) {
            _shouldCaptureForReceiptAnalysis.value = false
            speak("Image captured. Analyzing bill.")
            _state.value = AppState.Loading
            ShopCommandHandler().captureAndAnalyzeReceipt(bitmap)
        }
    }

    /**
     * Function to close WebSocket connection when no longer needed
     */
    fun closeWebSocketConnection() {
        webSocketService.disconnect()
    }

    fun onWebSocketCleared() {
        webSocketService.disconnect()
    }

    private inner class NavigationCommandHandler : CommandHandler {
        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }

            // Object detection queries
            if (lowercaseCommand.startsWith("is there") || lowercaseCommand.startsWith("do you see")) {
                handleObjectQuery(lowercaseCommand)
                return
            }

            // List queries
            if (lowercaseCommand.startsWith("what") && (
                        lowercaseCommand.contains("object") ||
                                lowercaseCommand.contains("things") ||
                                lowercaseCommand.contains("around") ||
                                lowercaseCommand.contains("see")
                        )) {
                describeEnvironment()
                return
            }

            when {
                // Navigation commands
                lowercaseCommand in listOf("start navigation", "begin route") -> startNavigation()
                lowercaseCommand in listOf("stop navigation", "end navigation") -> stopNavigation()

                // Environment description commands
                lowercaseCommand in listOf("describe environment", "describe surroundings", "what's around me") -> describeEnvironment()
                lowercaseCommand in listOf("describe scene", "what scene is this") -> describeScene()
                lowercaseCommand in listOf("count objects", "how many objects") -> countObjects()

                // Location-based object queries
                lowercaseCommand.contains("on my left") -> describeObjectsInRegion("left")
                lowercaseCommand.contains("on my right") -> describeObjectsInRegion("right")
                lowercaseCommand.contains("in front of me") -> describeObjectsInRegion("front")
                lowercaseCommand.contains("above me") -> describeObjectsInRegion("top")
                lowercaseCommand.contains("below me") -> describeObjectsInRegion("bottom")

                // Distance queries
                lowercaseCommand.contains("how far") || lowercaseCommand.contains("distance to") -> estimateDistance(lowercaseCommand)

                // Default
                else -> _state.value = AppState.Success("Command executed: $command")
            }

            when (val currentState = _state.value) {
                is AppState.Success -> {
                    speak(currentState.message)
                }

                is AppState.Error -> {
                    speak(currentState.error)
                }

                AppState.Loading -> {
                    speak("Loading, please wait...")
                }

                AppState.Idle -> {
                    speak("App is idle.")
                }
            }

        }

        private fun handleObjectQuery(command: String) {
            // Extract the object name from the query
            val objectName = extractObjectName(command)
            Log.d("ObjectName", objectName)
            if (objectName.isEmpty()) {
                _state.value = AppState.Success("I didn't understand which object you're looking for.")
                return
            }

            // Check if the object is present
            val detections = getObjectDetections()
            val matchingObjects = detections.filter { it.text.contains(objectName, ignoreCase = true) }

            if (matchingObjects.isNotEmpty()) {
                // Describe where the object is
                val location = determineObjectLocation(matchingObjects.first().box)
                val confidence = matchingObjects.first().confidence * 100
                _state.value = AppState.Success(
                    "Yes, I see a $objectName $location with ${confidence.toInt()}% confidence."
                )
                speak("Yes, I see a $objectName $location with ${confidence.toInt()}% confidence.")
            } else {
                // Check scene labels too
                val labels = getImageLabels()
                val matchingLabels = labels.filter { it.text.contains(objectName, ignoreCase = true) }

                if (matchingLabels.isNotEmpty()) {
                    val confidence = matchingLabels.first().confidence * 100
                    _state.value = AppState.Success(
                        "Yes, this scene contains a $objectName with ${confidence.toInt()}% confidence."
                    )
                } else {
                    _state.value = AppState.Success("No, I don't see any $objectName.")
                }
            }

            Log.d("ObjectQuery", _state.value.toString())
        }

        private fun extractObjectName(command: String): String {
            // Extract object name from queries like "is there a chair" or "do you see a table"
            val patterns = listOf(
                "is there an? (.+)\\b".toRegex(),
                "do you see an? (.+)\\b".toRegex(),
                "can you find an? (.+)\\b".toRegex(),
                "is an? (.+) there\\b".toRegex(),
                "look for an? (.+)\\b".toRegex()
            )

            for (pattern in patterns) {
                val match = pattern.find(command)
                if (match != null) {
                    return match.groupValues[1].trim()
                }
            }

            return ""
        }

        private fun describeEnvironment() {
            val detections = getObjectDetections()
            val labels = getImageLabels()

            if (detections.isEmpty() && labels.isEmpty()) {
                _state.value = AppState.Success("I don't detect any objects or scene elements at the moment.")
                return
            }

            val objectNames = detections.map { it.text }.distinct()
            val sceneDescription = labels.map { it.text }.distinct()

            val objectsText = if (objectNames.isNotEmpty()) {
                "I can see: ${objectNames.joinToString(", ")}"
            } else {
                "I don't see any specific objects"
            }

            val sceneText = if (sceneDescription.isNotEmpty()) {
                "The scene appears to be: ${sceneDescription.joinToString(", ")}"
            } else {
                ""
            }


            _state.value = AppState.Success("$objectsText. $sceneText".trim())
        }

        private fun describeScene() {
            val labels = getImageLabels()

            if (labels.isEmpty()) {
                _state.value = AppState.Success("I can't identify the current scene.")
                return
            }

            val topLabels = labels.sortedByDescending { it.confidence }.take(3)
            val sceneDescription = topLabels.joinToString(", ") {
                "${it.text} (${(it.confidence * 100).toInt()}%)"
            }

            _state.value = AppState.Success("The scene appears to be: $sceneDescription")
        }

        private fun countObjects() {
            val detections = getObjectDetections()

            if (detections.isEmpty()) {
                _state.value = AppState.Success("I don't detect any objects right now.")
                return
            }

            val objectCounts = detections
                .groupBy { it.text }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }

            val totalCount = detections.size
            val countText = objectCounts.joinToString(", ") { "${it.first}: ${it.second}" }

            _state.value = AppState.Success("I can see $totalCount objects: $countText")
        }

        private fun describeObjectsInRegion(region: String) {
            val detections = getObjectDetections()

            if (detections.isEmpty()) {
                _state.value = AppState.Success("I don't detect any objects in the $region.")
                return
            }

            // Filter objects based on their position in the frame
            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()

            val filteredObjects = when (region) {
                "left" -> detections.filter { it.box.centerX() < screenWidth / 3 }
                "right" -> detections.filter { it.box.centerX() > 2 * screenWidth / 3 }
                "front" -> detections.filter { it.box.centerX() in (screenWidth / 3)..(2 * screenWidth / 3) }
                "top" -> detections.filter { it.box.centerY() < screenHeight / 3 }
                "bottom" -> detections.filter { it.box.centerY() > 2 * screenHeight / 3 }
                else -> emptyList()
            }

            if (filteredObjects.isEmpty()) {
                _state.value = AppState.Success("I don't see any objects in the $region.")
                return
            }

            val objectNames = filteredObjects.map { it.text }.distinct()
            _state.value = AppState.Success("On your $region, I can see: ${objectNames.joinToString(", ")}")
        }

        private fun determineObjectLocation(rect: Rect): String {
            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()

            val centerX = rect.centerX()
            val centerY = rect.centerY()

            val horizontalPosition = when {
                centerX < screenWidth / 3 -> "on your left"
                centerX > 2 * screenWidth / 3 -> "on your right"
                else -> "in front of you"
            }

            val verticalPosition = when {
                centerY < screenHeight / 3 -> "at the top"
                centerY > 2 * screenHeight / 3 -> "at the bottom"
                else -> "in the middle"
            }

            // Calculate approximate size
            val objectSize = (rect.width() * rect.height()).toFloat() / (screenWidth * screenHeight)
            val sizeDescription = when {
                objectSize > 0.25 -> "very close"
                objectSize > 0.1 -> "nearby"
                objectSize > 0.05 -> "at medium distance"
                else -> "far away"
            }

            return "$horizontalPosition, $verticalPosition, $sizeDescription"
        }

        private fun estimateDistance(command: String) {
            // Extract the object name from distance queries
            val objectName = extractObjectForDistance(command)
            if (objectName.isEmpty()) {
                _state.value = AppState.Success("I didn't understand which object you're asking about.")
                return
            }

            // Find the object
            val detections = getObjectDetections()
            val matchingObjects = detections.filter { it.text.contains(objectName, ignoreCase = true) }

            if (matchingObjects.isEmpty()) {
                _state.value = AppState.Success("I don't see any $objectName to estimate distance.")
                return
            }

            // Calculate approximate distance based on object size
            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()
            val objectRect = matchingObjects.first().box

            val objectSize = (objectRect.width() * objectRect.height()).toFloat() / (screenWidth * screenHeight)

            val distanceEstimate = when {
                objectSize > 0.25 -> "very close, about 1-2 meters away"
                objectSize > 0.1 -> "nearby, about 2-3 meters away"
                objectSize > 0.05 -> "at medium distance, about 3-5 meters away"
                objectSize > 0.02 -> "somewhat far, about 5-10 meters away"
                else -> "far away, more than 10 meters"
            }

            _state.value = AppState.Success("The $objectName is $distanceEstimate.")
        }

        private fun extractObjectForDistance(command: String): String {
            // Extract object name from distance queries
            val patterns = listOf(
                "how far is (?:the|a|an) (.+)\\b".toRegex(),
                "distance to (?:the|a|an) (.+)\\b".toRegex(),
                "how close is (?:the|a|an) (.+)\\b".toRegex()
            )

            for (pattern in patterns) {
                val match = pattern.find(command)
                if (match != null) {
                    return match.groupValues[1].trim()
                }
            }

            return ""
        }

        // Helper function to get screen dimensions
        private fun getScreenWidth(): Int {
            return getApplication<Application>().resources.displayMetrics.widthPixels
        }

        private fun getScreenHeight(): Int {
            return getApplication<Application>().resources.displayMetrics.heightPixels
        }

        override fun getCommands(): List<String> {
            return listOf(
                "start navigation", "begin route",
                "stop navigation", "end navigation",
                "describe environment", "describe surroundings", "what's around me",
                "describe scene", "what scene is this",
                "count objects", "how many objects",
                "is there", "do you see",
                "what's on my",
                "what's in front of me", "what's above me", "what's below me",
                "how far is", "distance to the"
            ) + navigationCommands
        }
    }

    inner class NotesCommandHandler : CommandHandler {
        fun addNote(note: String) {
            _notes.value += note
            speak("Note added: $note")
        }

        fun deleteNote(note: String) {
            _notes.value -= note
            speak("Note deleted: $note")
        }

        fun readNotes() {
            if (_notes.value.isEmpty()) {
                speak("You have no notes.")
            } else {
                val notesText = _notes.value.joinToString("\n")
                speak("Your notes: $notesText")
            }
        }

        fun handleNotesCommand(command: String) {
            when {
                command.startsWith("add note", ignoreCase = true) -> {
                    val noteContent = command.substringAfter("add note").trim()
                    if (noteContent.isNotEmpty()) {
                        addNote(noteContent)
                    } else {
                        speak("Please provide note content.")
                    }
                }
                command.startsWith("read notes", ignoreCase = true) -> {
                    readNotes()
                }
                command.startsWith("delete note", ignoreCase = true) -> {
                    val noteIndex = command.substringAfter("delete note").trim().toIntOrNull()
                    if (noteIndex != null && noteIndex < _notes.value.size) {
                        deleteNote(_notes.value[noteIndex])
                    } else {
                        speak("Invalid note index.")
                    }
                }
                else -> speak("Invalid command for notes.")
            }
        }

        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }
            handleNotesCommand(command)
        }

        override fun getCommands(): List<String> {
            return listOf("add note", "read notes", "delete note") + navigationCommands
        }
    }

    private val validScreens = listOf("describe", "shop", "notes", "home", "navigation")
    private fun extractScreenName(command: String): String? {
        if (command.startsWith("navigate to") ||
            command.startsWith("open") ||
            command.startsWith("go to")
        ) {
            val screenName = command.substringAfterLast(" ").trim()
            if (screenName in validScreens) {
                return screenName
            }
        }
        return null
    }

    private fun handleNavigation(command: String, navController: NavHostController) {
        viewModelScope.launch {
            try {
                val destination = when {
                    command.contains("home", ignoreCase = true) -> AppScreen.Home
                    command.contains("shop", ignoreCase = true) -> AppScreen.Shop
                    command.contains("camera", ignoreCase = true) ||
                            command.contains("describe", ignoreCase = true) -> AppScreen.Describe
                    command.contains("navigation", ignoreCase = true) -> AppScreen.Navigation
                    command.contains("notes", ignoreCase = true) -> AppScreen.Notes
                    else -> null
                }
                destination?.let { screen ->
                    navController.navigate(screen.name) {
                        popUpTo(AppScreen.Home.name) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                lateinit var message: String
                if (destination != null) {
                    message = "Navigating to $destination"
                    _state.value = AppState.Success(message)
                } else {
                    message = "Navigation Failed! No such screen present!"
                }
                speak(message)
            } catch (e: Exception) {
                val errorMsg = "Navigation failed: ${e.message}"
                _state.value = AppState.Error(errorMsg)
                speak(errorMsg)
            }
        }
    }

    private fun describeCurrentScreen(screen: ScreenType) {
        viewModelScope.launch {
            try {
                val description = when (screen) {
                    ScreenType.Home -> "You are on the Home Screen. Commands available: ${HomeCommandHandler().getCommands()}"
                    ScreenType.Describe -> "You are on the Camera Screen. Commands available: ${DescribeCommandHandler().getCommands()}"
                    ScreenType.Shop -> "You are on the Shopping Screen. Commands available: ${ShopCommandHandler().getCommands()}"
                    ScreenType.Navigation -> "You are on the Navigation Screen. Commands available: ${NavigationCommandHandler().getCommands()}"
                    ScreenType.Notes -> "You are on Notes screen. Commands available: ${NotesCommandHandler().getCommands()}"
                }
                _state.value = AppState.Success(description)
                speak(description)
            } catch (e: Exception) {
                val errorMsg = "Screen description failed: ${e.message}"
                speak(errorMsg)
                _state.value = AppState.Error(errorMsg)
            }
        }
    }

    fun sendPrompt(bitmap: Bitmap, prompt: String) {
        _geminiState.value = GeminiState.Loading
        Log.i("prompt", "In prompt function")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                        generationConfig {  }
                    }
                )
                response.text?.let { outputContent ->
                    _geminiState.value = GeminiState.Success(outputContent)
                    speak(outputContent)
                } ?: run {
                    val errorMsg = "Empty response from Gemini AI"
                    _geminiState.value = GeminiState.Error(errorMsg)
                    speak(errorMsg)
                }
            } catch (e: Exception) {
                _geminiState.value = GeminiState.Error(e.localizedMessage ?: "Unknown error")
                speak((_geminiState.value as GeminiState.Error).error)
            }
        }
    }

    fun storeUri(uri: Uri) {
        _geminiState.value = GeminiState.File(uri)
    }

    fun updateVoiceStatus(active: Boolean) {
        _isVoiceCommandActive.value = active
    }

    fun updateCaptureState(active: Boolean) {
        _shouldCapture.value = active
    }

    fun updateScreenType(screen: ScreenType) {
        _screenType.value = screen
    }

    private fun startNavigation() {
        // Implement starting navigation
    }

    private fun stopNavigation() {
        // Implement stopping navigation
    }

    fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            inputStream?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error converting URI to Bitmap: ${e.localizedMessage}")
            null
        }
    }
}


/**
 * Extension function to add WebSocket functionality to MainViewModel
 */
fun MainViewModel.initializeWebSocketService() {

    // Observe WebSocket state changes
    viewModelScope.launch {
        webSocketService.state.collectLatest { state ->
            _webSocketState.postValue(state)

            when (state) {
                is WebSocketState.Success -> {
                    _marketSurveyData.postValue(state.response)
                    _state.value = AppState.Success("Market survey data received")
                    speak("Market survey vey analysis complete. Ready to display results.")
                }
                is WebSocketState.Error -> {
                    _state.value = AppState.Error(state.message)
                    speak("Error in market survey: ${state.message}")
                }
                is WebSocketState.Processing -> {
                    _state.value = AppState.Loading
                    speak("Processing image for market survey. This may take a minute.")
                }
                else -> { /* Handle other states as needed */ }
            }
        }
    }
}