package com.example.eyesai.viewModel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.example.eyesai.AppScreen
import com.example.eyesai.BuildConfig
import com.example.eyesai.R
import com.example.eyesai.service.MarketSurveyResponse
import com.example.eyesai.service.ReceiptAnalysisResponse
import com.example.eyesai.service.ReceiptAnalysisService
import com.example.eyesai.service.ReceiptWebSocketState
import com.example.eyesai.service.WebSocketImageService
import com.example.eyesai.service.WebSocketState
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
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

data class PdfDocument(
    val id: Long,
    val uri: Uri,
    val fileName: String,
    val filePath: String,
    val fileSize: String,
    val lastModified: String,
    val pageCount: Int = -1,
)

data class BookmarkItem(
    val title: String,
    val pageNumber: Int,
    val level: Int = 0,
    val children: List<BookmarkItem> = emptyList()
)

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
    val isVoiceCommandActive: StateFlow<Boolean> = _isVoiceCommandActive.asStateFlow()

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

    private val _pdfDocuments = MutableLiveData<List<PdfDocument>>()
    val pdfDocuments: LiveData<List<PdfDocument>> = _pdfDocuments

    private val _searchQuery = MutableLiveData<String>()
    val searchQuery: LiveData<String> = _searchQuery

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _selectedDocument = MutableLiveData<PdfDocument?>()
    val selectedDocument: LiveData<PdfDocument?> = _selectedDocument

    private val _shouldCaptureForMarketSurvey = MutableStateFlow(false)
    val shouldCaptureForMarketSurvey: StateFlow<Boolean> = _shouldCaptureForMarketSurvey.asStateFlow()

    // Initialize WebSocket service
    val webSocketService = WebSocketImageService()

    // LiveData for market survey results
    val _marketSurveyData = MutableLiveData<MarketSurveyResponse?>()
    val marketSurveyData: LiveData<MarketSurveyResponse?> = _marketSurveyData

    // LiveData for WebSocket state
    val _webSocketState = MutableLiveData<WebSocketState>(WebSocketState.Idle)
    val webSocketState: LiveData<WebSocketState> = _webSocketState

    init {
        setupCommandHandlers()
        searchDocuments()
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
                    val parameterizedCommands = listOf("add note", "read notes", "delete note", "hey", "search for")

                    if (parameterizedCommands.any { lowerCaseCommand.startsWith(it) }) {
                        handler.handleCommand(command, navController)
                        speak("Executing command.")
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
                            speak("I didn't understand that command.")
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
        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }
            if (lowercaseCommand.startsWith("hey", ignoreCase = true)) {
                describeScenery(command, AppScreen.Describe.name)
            }

            when {
                lowercaseCommand.startsWith("search for", ignoreCase = true) -> {
                    val query = command.substringAfter("search for").trim()
                    if (query.isNotEmpty()) {
                        searchDocuments(query)
                    } else {
                        speak("Please specify what you're searching for.")
                    }
                }

                lowercaseCommand.startsWith("select", ignoreCase = true) ||
                        lowercaseCommand.startsWith("open", ignoreCase = true) -> {
                        handleDocumentSelection(command)
                }

                lowercaseCommand == "describe document" ||
                        lowercaseCommand == "describe pdf" ||
                        lowercaseCommand == "document details" -> {
                        describeCurrentDocument()
                }

                lowercaseCommand == "list documents" ||
                        lowercaseCommand == "show documents" -> {
                        listAvailableDocuments()
                }
            }
        }

        override fun getCommands(): List<String> {
            return listOf(
                "hey",
                "search for [query]",
                "select [document name]",
                "open [document name]",
                "describe document",
                "list documents"
            ) + navigationCommands
        }
    }

    private inner class ShopCommandHandler : CommandHandler {
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
                lowercaseCommand.contains("show market survey", true) ||
                        lowercaseCommand.contains("display survey results", true) -> {
                    displayMarketSurveyResults()
                }
                lowercaseCommand.contains("show receipt details", true) ||
                        lowercaseCommand.contains("display bill analysis", true) -> {
                    displayReceiptDetails()
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
                "display survey results",
                "show bill details",
                "display bill analysis",
                "cancel market survey",
                "cancel bill analysis",
                "help"
            ) + navigationCommands
        }
    }

    private fun initiateProductAnalysis() {
        speak("Please point your camera at the product you want to analyze.")
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

    /**
     * Function to close WebSocket connection when no longer needed
     */
    fun closeWebSocketConnection() {
        webSocketService.disconnect()
    }

    fun onWebSocketCleared() {
        webSocketService.disconnect()
    }

    // This function should be called when the "capture" command is recognized
    fun captureForMarketSurvey(bitmap: Bitmap) {
        if (_shouldCaptureForMarketSurvey.value) {
            _shouldCaptureForMarketSurvey.value = false
            speak("Image captured. Analyzing product for market survey.")
            _state.value = AppState.Loading
            captureAndAnalyzeProduct(bitmap)
        }
    }

    private val _shouldCaptureForReceiptAnalysis = MutableStateFlow(false)
    private val _receiptAnalysisData = MutableStateFlow<ReceiptAnalysisResponse?>(null)
    val receiptAnalysisData: StateFlow<ReceiptAnalysisResponse?> = _receiptAnalysisData.asStateFlow()

    // Receipt analysis service
    private val receiptAnalysisService = ReceiptAnalysisService()

    private fun initiateReceiptAnalysis() {
        speak("Please point your camera at the bill you want to analyze.")
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
                        else -> {} // Handle other states if needed
                    }
                }



            } catch (e: Exception) {
                _state.value = AppState.Error("Error capturing bill: ${e.message}")
                speak("Error capturing bill. Please try again.")
            }
        }
    }

    // This function should be called when the "capture" command is recognized
    fun captureForReceiptAnalysis(bitmap: Bitmap) {
        if (_shouldCaptureForReceiptAnalysis.value) {
            _shouldCaptureForReceiptAnalysis.value = false
            speak("Image captured. Analyzing bill.")
            _state.value = AppState.Loading
            captureAndAnalyzeReceipt(bitmap)
        }
    }

    private inner class NavigationCommandHandler : CommandHandler {
        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }
            when (lowercaseCommand) {
                "start navigation", "begin route" -> startNavigation()
                "stop navigation", "end navigation" -> stopNavigation()
                else -> _state.value = AppState.Success("Command executed: $command")
            }
        }

        override fun getCommands(): List<String> {
            return listOf("start navigation", "stop navigation") + navigationCommands
        }
    }

    private inner class NotesCommandHandler : CommandHandler {
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

    private fun describeScenery(command: String, route: String) {
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

    private fun describeCurrentScreen(screen: ScreenType) {
        viewModelScope.launch {
            try {
                val description = when (screen) {
                    ScreenType.Home -> "You are on the Home Screen. Commands available: Navigate to camera, Describe current screen."
                    ScreenType.Describe -> "You are on the Camera Screen. Commands available: Describe scenery, Capture photo."
                    ScreenType.Shop -> "You are on the Shopping Screen. Commands available: Add item to list, View shopping cart."
                    ScreenType.Navigation -> "You are on the Navigation Screen. Commands available: Start navigation, Stop navigation."
                    ScreenType.Notes -> "You are on Notes screen."
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

    fun searchDocuments(query: String = "") {
        _searchQuery.value = query
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // Fetch all PDF documents in the background
                val documents = withContext(Dispatchers.IO) {
//                    fetchPdfDocuments(query)
                    loadAllPdfDocuments()
                }
                _pdfDocuments.value = documents
                _isLoading.value = false

                // If no documents were loaded at all
                if (documents.isEmpty()) {
                    speak("No PDF documents found on your device.")
                    _state.value = AppState.Success("No PDF documents found")
                } else {
                    // If a query is provided, filter the documents by fileName
                    val matchingDocs = if (query.isNotEmpty()) {
                        documents.filter { it.fileName.contains(query, ignoreCase = true) }
                    } else {
                        documents
                    }

                    if (matchingDocs.isNullOrEmpty()) {
                        speak("No PDF documents found matching '$query'.")
                        _state.value = AppState.Success("No matching documents")
                    } else {
                        val message = if (query.isEmpty()) {
                            "Loaded ${matchingDocs.size} PDF documents."
                        } else {
                            "Found ${matchingDocs.size} PDF documents matching '$query'."
                        }
                        speak(message)
                        _state.value = AppState.Success(message)
                        listDocuments(matchingDocs)
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load documents: ${e.localizedMessage}"
                _isLoading.value = false
                speak("Sorry, there was an error searching for documents.")
            }
        }
    }

    // Function to get a specific PDF document by ID
    fun getPdfDocumentById(id: Long): PdfDocument? {
        return _pdfDocuments.value?.find { it.id == id }
    }

    // Function to select a document and load its metadata
    fun selectDocument(documentId: Long) {
        val document = getPdfDocumentById(documentId)
        if (document != null) {
            _selectedDocument.value = document
            loadDocumentMetadata(documentId)
        }
    }

    private suspend fun loadAllPdfDocuments(): List<PdfDocument> {
        return withContext(Dispatchers.IO) {
            val documents = mutableListOf<PdfDocument>()

            // Define the columns to retrieve
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )

            // Set up selection criteria for PDF files only
            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
            val selectionArgs = arrayOf("application/pdf")

            // Get application context
            val context = getApplication<Application>()

            // Query the MediaStore for all PDF documents
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val path = cursor.getString(pathColumn)
                    val size = cursor.getLong(sizeColumn)
                    val dateModified = cursor.getLong(dateColumn)

                    val uri = Uri.withAppendedPath(
                        MediaStore.Files.getContentUri("external"),
                        id.toString()
                    )

                    documents.add(
                        PdfDocument(
                            id = id,
                            uri = uri,
                            fileName = name,
                            filePath = path,
                            fileSize = formatFileSize(size),
                            lastModified = formatDate(dateModified)
                        )
                    )
                }
            }
            documents
        }
    }

    private suspend fun fetchPdfDocuments(query: String): List<PdfDocument> {
        return withContext(Dispatchers.IO) {
            val documents = mutableListOf<PdfDocument>()

            // Define the columns we want to retrieve
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )

            // Set up selection criteria - PDF files only
//            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
//            val selectionArgs = arrayOf("application/pdf")
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%.pdf")

            val finalSelection: String
            val finalSelectionArgs: Array<String>

            if (query.isNotEmpty()) {
                finalSelection = "$selection AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
                finalSelectionArgs = arrayOf("%$query%")
            } else {
                finalSelection = selection
                finalSelectionArgs = selectionArgs
            }


            // Get application context
            val context = getApplication<Application>()

            // Query the MediaStore
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                finalSelection,
                finalSelectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val path = cursor.getString(pathColumn)
                    val size = cursor.getLong(sizeColumn)
                    val dateModified = cursor.getLong(dateColumn)

                    val uri = Uri.withAppendedPath(
                        MediaStore.Files.getContentUri("external"),
                        id.toString()
                    )

                    documents.add(
                        PdfDocument(
                            id = id,
                            uri = uri,
                            fileName = name,
                            filePath = path,
                            fileSize = formatFileSize(size),
                            lastModified = formatDate(dateModified)
                        )
                    )
                    Log.d("Documents", documents.toString())
                }
            }
            documents
        }
    }

    // Helper function to format file size in a human-readable format
    private fun formatFileSize(sizeInBytes: Long): String {
        return when {
            sizeInBytes < 1024 -> "$sizeInBytes B"
            sizeInBytes < 1024 * 1024 -> "${sizeInBytes / 1024} KB"
            else -> String.format("%.1f MB", sizeInBytes / (1024.0 * 1024.0))
        }
    }

    // Helper function to format date in a readable format
    private fun formatDate(timestamp: Long): String {
        val date = Date(timestamp * 1000)
        val formatter = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        return formatter.format(date)
    }

    // Function to get document metadata including bookmarks and page numbering
    fun loadDocumentMetadata(documentId: Long) {
        viewModelScope.launch {
            try {
                val document = getPdfDocumentById(documentId) ?: return@launch
                _isLoading.value = true

                val enhancedDocument = withContext(Dispatchers.IO) {
                    // Use iText to extract metadata
                    extractPdfMetadata(document)
                }

                // Update the document with its metadata
                val updatedDocuments = _pdfDocuments.value?.map {
                    if (it.id == documentId) enhancedDocument else it
                } ?: emptyList()

                _pdfDocuments.value = updatedDocuments
                _selectedDocument.value = enhancedDocument
                _isLoading.value = false
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load document metadata: ${e.localizedMessage}"
                _isLoading.value = false
            }
        }
    }

    // Function to extract PDF metadata using iText
    private fun extractPdfMetadata(document: PdfDocument): PdfDocument {
        try {
            val pdfReader = PdfReader(document.filePath)
            val pdfDocument = ITextPdfDocument(pdfReader)

            // Get basic metadata
            val pageCount = pdfDocument.numberOfPages

            pdfReader.close()

            return document.copy(
                pageCount = pageCount
            )
        } catch (e: Exception) {
            // If something goes wrong, return the original document
            return document
        }
    }

    // Function to provide accessibility-friendly description of a document
    fun getAccessibleDescription(document: PdfDocument): String {
        val pageInfo = if (document.pageCount > 0) {
            "${document.pageCount} pages"
        } else {
            "Unknown page count"
        }

        return "${document.fileName}, ${document.fileSize}, Last modified on ${document.lastModified}, $pageInfo."
    }

    // Helper function to format a bookmark for display
    private fun formatBookmarkForDisplay(bookmark: BookmarkItem): String {
        val indent = "  ".repeat(bookmark.level)
        return "$indent${bookmark.title} (Page ${bookmark.pageNumber})"
    }

    private fun handleDocumentSelection(command: String) {
        val documentName = command.substringAfter("select").trim().takeIf { it.isNotEmpty() }
            ?: command.substringAfter("open").trim()

        if (documentName.isEmpty()) {
            speak("Please specify which document you want to select.")
            return
        }

        val documents = _pdfDocuments.value
        if (documents.isNullOrEmpty()) {
            speak("No documents are available. Please search for documents first.")
            return
        }

        // Find the document by name (partial matching)
        val matchingDocs = documents.filter {
            it.fileName.contains(documentName, ignoreCase = true)
        }

        when {
            matchingDocs.isEmpty() -> {
                speak("No document found with name containing '$documentName'.")
                _state.value = AppState.Success("No matching document")
            }
            matchingDocs.size == 1 -> {
                // Found exactly one matching document
                val doc = matchingDocs[0]
                selectDocument(doc.id)
                speak("Selected document: ${doc.fileName}")
                describeCurrentDocument()
            }
            else -> {
                // Multiple matching documents found
                speak("Found ${matchingDocs.size} documents matching '$documentName'. Please be more specific.")
                listDocuments(matchingDocs)
            }
        }
    }

    private fun describeCurrentDocument() {
        val document = _selectedDocument.value
        if (document == null) {
            speak("No document is currently selected. Please select a document first.")
            return
        }

        val description = getAccessibleDescription(document)
        speak(description)
        _state.value = AppState.Success(description)
    }

    private fun listAvailableDocuments() {
        val documents = _pdfDocuments.value
        if (documents.isNullOrEmpty()) {
            speak("No documents are available. Please search for documents first.")
            return
        }

        listDocuments(documents)
    }

    private fun listDocuments(documents: List<PdfDocument>) {
        val message = StringBuilder("Available documents:\n")
        documents.forEachIndexed { index, doc ->
            message.append("${index + 1}. ${doc.fileName} (${doc.fileSize})\n")
        }

        speak("Found ${documents.size} documents. I'll list them for you.")
        _state.value = AppState.Success(message.toString())
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
                    speak("Market survey analysis complete. Ready to display results.")
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