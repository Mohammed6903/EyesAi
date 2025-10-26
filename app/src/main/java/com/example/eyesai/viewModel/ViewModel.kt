package com.example.eyesai.viewModel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.example.eyesai.AppScreen
import com.example.eyesai.R
import com.example.eyesai.service.MarketSurveyResponse
import com.example.eyesai.service.ReceiptAnalysisResponse
import com.example.eyesai.service.ReceiptAnalysisService
import com.example.eyesai.service.ReceiptWebSocketState
import com.example.eyesai.service.WebSocketImageService
import com.example.eyesai.service.WebSocketState
import com.example.eyesai.viewModel.NavigationViewModel.ImageLabelsState
import com.example.eyesai.viewModel.NavigationViewModel.ObjectDetectionState
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.mlkit.vision.label.ImageLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.Locale

// Define UI states
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

// Define screen types
enum class ScreenType(@StringRes val title: Int) {
    Home(title = R.string.home),
    Navigation(title = R.string.Navigation),
    Shop(title = R.string.Shop),
    Describe(title = R.string.Describe),
    Notes(title = R.string.notes)
}

// Command processing results
sealed class CommandProcessingResult {
    data class Success(
        val message: String,
        val executedFunctions: List<String>,
        val didNavigate: Boolean = false
    ) : CommandProcessingResult()
    data class NeedsClarification(val message: String) : CommandProcessingResult()
    data class Error(val message: String) : CommandProcessingResult()
}

// Gemini Command Processor with Function Calling
class GeminiCommandProcessor(
    private val viewModel: MainViewModel,
    private val application: Application
) {

    // Define function declarations for all available commands
    private fun createFunctionDeclarations(): List<FunctionDeclaration> {
        return listOf(
            // Navigation functions
            FunctionDeclaration(
                "navigate_to_screen",
                "Navigate to a specific screen in the app. Use this when user wants to go to, open, or access a different screen.",
                mapOf(
                    "screen" to Schema.string(
                        "The screen to navigate to. Valid values: home, shop, describe, navigation, notes"
                    )
                )
            ),

            // Describe screen functions
            FunctionDeclaration(
                "describe_scene",
                "Capture and describe what the camera sees. Use when user asks to describe, see, or tell about something. For visually impaired users.",
                mapOf(
                    "query" to Schema.string("The user's specific query or what they want to know about the scene")
                )
            ),

            // Shop screen functions
            FunctionDeclaration(
                "analyze_product",
                "Analyze a product using market survey. Captures image and provides price comparison, features, and vendor details.",
                mapOf()
            ),

            FunctionDeclaration(
                "analyze_receipt",
                "Analyze a bill or receipt. Extracts items, prices, and provides accessible summary for visually impaired users.",
                mapOf()
            ),

            FunctionDeclaration(
                "show_market_survey_results",
                "Display market survey results for a previously analyzed product. Shows price, features, and competitors.",
                mapOf(
                    "detailed" to Schema.boolean("Whether to show detailed results including reviews and specifications")
                )
            ),

            FunctionDeclaration(
                "show_receipt_details",
                "Display details of a previously analyzed receipt/bill in an accessible format.",
                mapOf()
            ),

            // Navigation screen functions
            FunctionDeclaration(
                "check_object_presence",
                "Check if a specific object is present in the current view. For visually impaired navigation assistance.",
                mapOf(
                    "object_name" to Schema.string("The name of the object to look for")
                )
            ),

            FunctionDeclaration(
                "describe_environment",
                "Describe the surrounding environment and objects visible. For visually impaired users to understand their surroundings.",
                mapOf()
            ),

            FunctionDeclaration(
                "count_objects",
                "Count and list all detected objects in the current view.",
                mapOf()
            ),

            FunctionDeclaration(
                "estimate_distance",
                "Estimate distance to a specific object. Helps visually impaired users understand spatial relationships.",
                mapOf(
                    "object_name" to Schema.string("The object to estimate distance to")
                )
            ),

            FunctionDeclaration(
                "start_navigation",
                "Start navigation mode for real-time guidance.",
                mapOf()
            ),

            FunctionDeclaration(
                "stop_navigation",
                "Stop navigation mode.",
                mapOf()
            ),

            // Notes screen functions
            FunctionDeclaration(
                "add_note",
                "Add a new note. For visually impaired users to save voice notes.",
                mapOf(
                    "content" to Schema.string("The content of the note to add")
                )
            ),

            FunctionDeclaration(
                "read_notes",
                "Read all saved notes aloud.",
                mapOf()
            ),

            FunctionDeclaration(
                "delete_note",
                "Delete a specific note by its number.",
                mapOf(
                    "note_number" to Schema.integer("The number of the note to delete (1-based index)")
                )
            ),

            FunctionDeclaration(
                "clear_all_notes",
                "Delete all saved notes.",
                mapOf()
            ),

            // Help function
            FunctionDeclaration(
                "provide_help",
                "Provide help information about available commands and features. Tailored for visually impaired users.",
                mapOf(
                    "topic" to Schema.string("Specific topic to get help about, or empty for general help", nullable = true)
                )
            )
        )
    }

    // Create the Gemini model for command processing
    private val commandModel = Firebase.ai(
        backend = GenerativeBackend.googleAI()
    ).generativeModel(
        modelName = "gemini-2.5-flash",
        tools = listOf(Tool.functionDeclarations(createFunctionDeclarations())),
        systemInstruction = content {
            text("""
                You are an AI assistant for a mobile app designed for visually impaired users.
                Your role is to interpret voice commands and determine which functions to call.
                
                IMPORTANT GUIDELINES:
                1. The user is visually impaired - prioritize their convenience and accessibility
                2. Recognize commands in multiple languages (English, Hindi, Spanish, French, German, etc.)
                3. Be context-aware: understand which screen the user is currently on
                4. If a command requires a different screen, automatically navigate there first
                5. Chain multiple functions when needed (e.g., navigate then execute)
                6. Be forgiving with command variations and natural language
                7. Prioritize safety and clear audio feedback
                
                CURRENT SCREEN AWARENESS:
                - If user asks to do something not available on current screen, call navigate_to_screen first
                - Then call the actual function they requested
                - Example: User on Home wants to analyze product → navigate to shop first, then analyze
                
                MULTILINGUAL SUPPORT:
                - Recognize commands in English, Hindi, Spanish, French, German, etc.
                - Examples: "दुकान में जाओ" (go to shop), "describe scene", "¿qué ves?" (what do you see)
                - "नोट जोड़ें" (add note), "बिल का विश्लेषण करें" (analyze bill)
                
                HELP REQUESTS:
                - General help: Provide overview of app features
                - Specific help: Explain what user can do with current screen or specific feature
                - Always be encouraging and supportive
                
                FUNCTION SELECTION:
                - Choose the most appropriate function(s) for user's intent
                - Prefer chaining functions for seamless experience
                - If ambiguous, choose the safest/most helpful option
                - Also, for describe related tasks, send the user's query in the user's own language in a concise manner to the respective function
            """.trimIndent())
        }
    )

    // Process voice command using Gemini
    suspend fun processCommand(
        command: String,
        currentScreen: ScreenType,
        navController: NavHostController
    ): CommandProcessingResult {
        return withContext(Dispatchers.IO) {
            try {
                val chat = commandModel.startChat()

                // Send command with context
                val prompt = """
                    Current screen: ${currentScreen.name}
                    User command: "$command"
                    
                    Determine which function(s) to call to fulfill this request.
                    If the command requires a screen the user is not on, navigate first.
                """.trimIndent()

                val response = chat.sendMessage(prompt)

                // Process function calls
                val functionCalls = response.functionCalls

                if (functionCalls.isEmpty()) {
                    // No function call - likely needs clarification
                    val textResponse = response.text ?: "I didn't understand that command."
                    return@withContext CommandProcessingResult.NeedsClarification(textResponse)
                }

                // Execute function calls sequentially
                val results = mutableListOf<String>()
                var didNavigate = false

                for (functionCall in functionCalls) {
                    val result = executeFunctionCall(functionCall, navController, currentScreen)
                    results.add(result.message)
                    if (result.isNavigation) {
                        didNavigate = true
                    }

                    // Send function response back to model
                    val responseJsonObject = buildJsonObject {
                        put("result", JsonPrimitive(result.message))
                    }
                    val functionResponse = content("function") {
                        part(FunctionResponsePart(functionCall.name, responseJsonObject))
                    }
                    chat.sendMessage(functionResponse)
                }

                // Get final response from model
                val finalResponse = chat.sendMessage("Summarize what was done in one clear sentence for a visually impaired user.")
                val summary = finalResponse.text ?: "Commands executed successfully."

                CommandProcessingResult.Success(summary, results, didNavigate)

            } catch (e: Exception) {
                Log.e("GeminiCommandProcessor", "Error processing command", e)
                CommandProcessingResult.Error("Failed to process command: ${e.message}")
            }
        }
    }

    // Execution result to track navigation
    private data class ExecutionResult(val message: String, val isNavigation: Boolean = false)

    // Execute individual function calls
    private suspend fun executeFunctionCall(
        functionCall: FunctionCallPart,
        navController: NavHostController,
        currentScreen: ScreenType
    ): ExecutionResult {
        return withContext(Dispatchers.Main) {
            try {
                when (functionCall.name) {
                    "navigate_to_screen" -> {
                        val screen = functionCall.args["screen"]?.jsonPrimitive?.content ?: "home"
                        val screenType = when (screen.lowercase()) {
                            "home" -> ScreenType.Home
                            "shop" -> ScreenType.Shop
                            "describe" -> ScreenType.Describe
                            "navigation" -> ScreenType.Navigation
                            "notes" -> ScreenType.Notes
                            else -> ScreenType.Home
                        }
                        viewModel.handleNavigation(screenType, navController, speakFeedback = true)
                        ExecutionResult("Navigated to $screen screen", isNavigation = true)
                    }

                    "describe_scene" -> {
                        val query = functionCall.args["query"]?.jsonPrimitive?.content ?: ""
                        viewModel.describeScenery("hey $query")
                        ExecutionResult("Capturing and analyzing scene")
                    }

                    "analyze_product" -> {
                        viewModel.initiateProductAnalysis()
                        ExecutionResult("Ready to capture product for analysis")
                    }

                    "analyze_receipt" -> {
                        viewModel.initiateReceiptAnalysis()
                        ExecutionResult("Ready to capture receipt for analysis")
                    }

                    "show_market_survey_results" -> {
                        val detailed = functionCall.args["detailed"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        if (detailed) {
                            viewModel.displayDetailedMarketSurveyResults()
                        } else {
                            viewModel.displayMarketSurveyResults()
                        }
                        ExecutionResult("Displaying market survey results")
                    }

                    "show_receipt_details" -> {
                        viewModel.displayReceiptDetails()
                        ExecutionResult("Displaying receipt details")
                    }

                    "check_object_presence" -> {
                        val objectName = functionCall.args["object_name"]?.jsonPrimitive?.content ?: ""
                        viewModel.handleObjectQuery("is there a $objectName")
                        ExecutionResult("Checked for $objectName")
                    }

                    "describe_environment" -> {
                        viewModel.describeEnvironment()
                        ExecutionResult("Describing environment")
                    }

                    "count_objects" -> {
                        viewModel.countObjects()
                        ExecutionResult("Counting objects")
                    }

                    "estimate_distance" -> {
                        val objectName = functionCall.args["object_name"]?.jsonPrimitive?.content ?: ""
                        viewModel.estimateDistance("how far is the $objectName")
                        ExecutionResult("Estimating distance to $objectName")
                    }

                    "start_navigation" -> {
                        viewModel.startNavigation()
                        ExecutionResult("Navigation started")
                    }

                    "stop_navigation" -> {
                        viewModel.stopNavigation()
                        ExecutionResult("Navigation stopped")
                    }

                    "add_note" -> {
                        val content = functionCall.args["content"]?.jsonPrimitive?.content ?: ""
                        viewModel.addNote(content)
                        ExecutionResult("Note added")
                    }

                    "read_notes" -> {
                        viewModel.readNotes()
                        ExecutionResult("Reading notes")
                    }

                    "delete_note" -> {
                        val noteNumber = functionCall.args["note_number"]?.jsonPrimitive?.content ?: "1"
                        viewModel.handleDeleteNote(noteNumber)
                        ExecutionResult("Note deleted")
                    }

                    "clear_all_notes" -> {
                        viewModel.clearAllNotes()
                        ExecutionResult("All notes cleared")
                    }

                    "provide_help" -> {
                        val topic = functionCall.args["topic"]?.jsonPrimitive?.content
                        val helpText = provideHelp(topic)
                        viewModel.speak(helpText)
                        ExecutionResult(helpText)
                    }

                    else -> ExecutionResult("Unknown function: ${functionCall.name}")
                }
            } catch (e: Exception) {
                Log.e("GeminiCommandProcessor", "Error executing function ${functionCall.name}", e)
                ExecutionResult("Error executing ${functionCall.name}: ${e.message}")
            }
        }
    }

    private fun provideHelp(topic: String?): String {
        val currentScreen = viewModel.screenType.value

        return if (topic.isNullOrBlank()) {
            // General help
            """
                Welcome to EyesAI, an accessibility app for visually impaired users.
                
                Main features:
                Home: Central hub for navigation
                Describe: Capture and describe scenes using AI
                Shop: Analyze products and receipts
                Navigation: Real-time object detection and guidance
                Notes: Voice-based note taking
                
                You can say commands like:
                "Go to shop", "Describe what you see", "Is there a chair?", "Add a note"
                
                I understand multiple languages. Speak naturally!
            """.trimIndent()
        } else {
            // Topic-specific help
            when (topic.lowercase()) {
                "describe", "camera" -> """
                    On the Describe screen, you can:
                    Say "hey describe" followed by your question to capture and analyze scenes
                    Ask specific questions like "hey what color is this?" or "hey read this text"
                    Get detailed descriptions of your surroundings
                """.trimIndent()

                "shop", "shopping" -> """
                    On the Shop screen, you can:
                    Say "analyze product" to get market survey with prices and features
                    Say "analyze bill" to scan receipts
                    Say "show market survey results" for detailed product information
                    Say "show receipt details" for bill breakdown
                """.trimIndent()

                "navigation", "objects" -> """
                    On the Navigation screen, you can:
                    Ask "is there a" followed by object name to check for objects
                    Say "describe environment" for surroundings overview
                    Ask "how far is the" followed by object name for distance estimation
                    Say "count objects" to know what's around you
                    Use "start navigation" for real-time guidance
                """.trimIndent()

                "notes" -> """
                    On the Notes screen, you can:
                    Say "add note" followed by your content to save voice notes
                    Say "read notes" to hear all your notes
                    Say "delete note" followed by number to remove a specific note
                    Say "clear all notes" to start fresh
                """.trimIndent()

                else -> """
                    Current screen: ${currentScreen.name}
                    
                    You can navigate to other screens by saying:
                    "Go to home", "Go to shop", "Go to describe", "Go to navigation", or "Go to notes"
                    
                    Say "help" followed by a feature name for specific guidance.
                """.trimIndent()
            }
        }
    }
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

    // Gemini Model for scene description
    private val generativeModel = Firebase.ai(
        backend = GenerativeBackend.googleAI()
    ).generativeModel("gemini-2.5-flash", systemInstruction = content {
        text(
            "Answer the following question directly and concisely. Avoid any introductory phrases or filler words. \n" +
                    "Provide the information in a way that is easy to understand when read aloud by a screen reader."
        )
    })

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

    private lateinit var geminiCommandProcessor: GeminiCommandProcessor

    init {
        initializeWebSocketService()
        geminiCommandProcessor = GeminiCommandProcessor(this, application)
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

    // Main voice command handler - uses Gemini for all processing
    fun handleVoiceCommand(command: String, navController: NavHostController) {
        viewModelScope.launch {
            _state.value = AppState.Loading
            _isVoiceCommandActive.value = true

            try {
                val result = geminiCommandProcessor.processCommand(
                    command = command,
                    currentScreen = screenType.value,
                    navController = navController
                )

                when (result) {
                    is CommandProcessingResult.Success -> {
                        _state.value = AppState.Success(result.message)
                        // Only speak if navigation occurred
                        if (!result.didNavigate) {
                            // No voice feedback for normal commands
                        }
                        Log.d("GeminiCommand", "Executed: ${result.executedFunctions}")
                    }

                    is CommandProcessingResult.NeedsClarification -> {
                        _state.value = AppState.Success(result.message)
                        speak(result.message)
                    }

                    is CommandProcessingResult.Error -> {
                        _state.value = AppState.Error(result.message)
                        speak(result.message)
                    }
                }

            } catch (e: Exception) {
                val errorMsg = "Command processing failed: ${e.message}"
                _state.value = AppState.Error(errorMsg)
                speak(errorMsg)
                Log.e("GeminiCommand", "Error", e)
            } finally {
                delay(1000)
                _isVoiceCommandActive.value = false
            }
        }
    }

    // Navigation handling with optional voice feedback
    fun handleNavigation(destination: ScreenType, navController: NavHostController, speakFeedback: Boolean = false) {
        viewModelScope.launch {
            try {
                val screen = when (destination) {
                    ScreenType.Home -> AppScreen.Home
                    ScreenType.Shop -> AppScreen.Shop
                    ScreenType.Describe -> AppScreen.Describe
                    ScreenType.Navigation -> AppScreen.Navigation
                    ScreenType.Notes -> AppScreen.Notes
                }

                navController.navigate(screen.name) {
                    popUpTo(AppScreen.Home.name) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }

                updateScreenType(destination)
                val message = "Navigated to ${destination.name}"

                // Only speak if explicitly requested (when navigating for another command)
                if (speakFeedback) {
                    speak(message)
                }

                _state.value = AppState.Success(message)
            } catch (e: Exception) {
                val errorMsg = "Navigation failed: ${e.message}"
                _state.value = AppState.Error(errorMsg)
                speak(errorMsg)
            }
        }
    }

    // Describe scene functionality
    fun describeScenery(command: String) {
        val prompt = command.substringAfter("hey", "").trim()
        Log.d("Prompt", prompt)
        updateCaptureState(true)

        viewModelScope.launch {
            while (_geminiState.value !is GeminiState.File) {
                delay(500)
            }
            updateCaptureState(false)

            val instruction = "User Query: $prompt"
            val uri = (_geminiState.value as GeminiState.File).uri
            val bitmap = uriToBitmap(uri)

            if (bitmap != null) {
                sendPrompt(bitmap, instruction)
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
                        generationConfig { }
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

    // Shop functionality
    fun initiateProductAnalysis() {
        _state.value = AppState.Loading
        _shouldCaptureForMarketSurvey.value = true
        speak("Ready to capture product image for market survey analysis.")
    }

    fun initiateReceiptAnalysis() {
        _state.value = AppState.Loading
        _shouldCaptureForReceiptAnalysis.value = true
        speak("Ready to capture receipt image for analysis.")
    }

    fun displayMarketSurveyResults() {
        val marketSurveyData = marketSurveyData.value
        if (marketSurveyData != null) {
            val productName = marketSurveyData.product_info.name
            val avgPrice = marketSurveyData.market_survey.average_price_in_inr
            val priceRange = "₹${marketSurveyData.market_survey.price_range.min} to ₹${marketSurveyData.market_survey.price_range.max}"
            val speechText = "Market survey for $productName. Average price is ₹$avgPrice. Price range is $priceRange."
            speak(speechText)
            _state.value = AppState.Success("Market survey results displayed")
        } else {
            speak("No market survey data available. Please analyze a product first.")
            _state.value = AppState.Error("No market survey data available")
        }
    }

    fun displayDetailedMarketSurveyResults() {
        val marketSurveyData = marketSurveyData.value
        if (marketSurveyData != null) {
            val productName = marketSurveyData.product_info.name
            val productDescription = marketSurveyData.product_info.description
            val avgPrice = marketSurveyData.market_survey.average_price_in_inr
            val priceRange = "₹${marketSurveyData.market_survey.price_range.min} to ₹${marketSurveyData.market_survey.price_range.max}"

            val speechText = """
                Market Survey for $productName.
                Description: $productDescription.
                Average Price: ₹$avgPrice.
                Price Range: $priceRange.
            """.trimIndent()

            speak(speechText)
            _state.value = AppState.Success("Detailed market survey results displayed")
        } else {
            speak("No market survey data available. Please analyze a product first.")
            _state.value = AppState.Error("No market survey data available")
        }
    }

    fun displayReceiptDetails() {
        val receiptData = _receiptAnalysisData.value
        if (receiptData != null) {
            val receiptAnalysisService = ReceiptAnalysisService()
            val summary = receiptAnalysisService.getAccessibleSummary(receiptData)
            speak(summary)
            _state.value = AppState.Success("Receipt analysis displayed")
        } else {
            speak("No bill analysis data available. Please scan a bill first.")
            _state.value = AppState.Error("No bill analysis data available")
        }
    }

    fun captureForMarketSurvey(bitmap: Bitmap) {
        if (_shouldCaptureForMarketSurvey.value) {
            _shouldCaptureForMarketSurvey.value = false
            speak("Image captured. Analyzing product for market survey.")
            _state.value = AppState.Loading
            captureAndAnalyzeProduct(bitmap)
        }
    }

    fun captureAndAnalyzeProduct(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                _state.value = AppState.Loading
                webSocketService.connect()
                webSocketService.sendImage(bitmap)
            } catch (e: Exception) {
                _state.value = AppState.Error("Error capturing image: ${e.message}")
                speak("Error capturing image. Please try again.")
            }
        }
    }

    private val receiptAnalysisService = ReceiptAnalysisService()

    fun captureAndAnalyzeReceipt(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                _state.value = AppState.Loading
                receiptAnalysisService.connect()
                receiptAnalysisService.analyzeReceipt(bitmap)

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
                speak("Error capturing bill. Please try again.")
            }
        }
    }

    fun captureForReceiptAnalysis(bitmap: Bitmap) {
        if (_shouldCaptureForReceiptAnalysis.value) {
            _shouldCaptureForReceiptAnalysis.value = false
            speak("Image captured. Analyzing bill.")
            _state.value = AppState.Loading
            captureAndAnalyzeReceipt(bitmap)
        }
    }

    // Navigation functionality
    fun handleObjectQuery(command: String) {
        val objectName = extractObjectName(command)
        Log.d("ObjectName", objectName)

        if (objectName.isEmpty()) {
            val message = "I didn't understand which object you're looking for."
            _state.value = AppState.Success(message)
            speak(message)
            return
        }

        val detections = getObjectDetections()
        val matchingObjects = detections.filter { it.text.contains(objectName, ignoreCase = true) }

        if (matchingObjects.isNotEmpty()) {
            val location = determineObjectLocation(matchingObjects.first().box)
            val confidence = matchingObjects.first().confidence * 100
            val message = "Yes, I see a $objectName $location with ${confidence.toInt()}% confidence."
            _state.value = AppState.Success(message)
            speak(message)
        } else {
            val labels = getImageLabels()
            val matchingLabels = labels.filter { it.text.contains(objectName, ignoreCase = true) }

            if (matchingLabels.isNotEmpty()) {
                val confidence = matchingLabels.first().confidence * 100
                val message = "Yes, this scene contains a $objectName with ${confidence.toInt()}% confidence."
                _state.value = AppState.Success(message)
                speak(message)
            } else {
                val message = "No, I don't see any $objectName."
                _state.value = AppState.Success(message)
                speak(message)
            }
        }
    }

    private fun extractObjectName(command: String): String {
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

    fun describeEnvironment() {
        val detections = getObjectDetections()
        val labels = getImageLabels()

        if (detections.isEmpty() && labels.isEmpty()) {
            val message = "I don't detect any objects or scene elements at the moment."
            _state.value = AppState.Success(message)
            speak(message)
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

        val message = "$objectsText. $sceneText".trim()
        _state.value = AppState.Success(message)
        speak(message)
    }

    fun countObjects() {
        val detections = getObjectDetections()

        if (detections.isEmpty()) {
            val message = "I don't detect any objects right now."
            _state.value = AppState.Success(message)
            speak(message)
            return
        }

        val objectCounts = detections
            .groupBy { it.text }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }

        val totalCount = detections.size
        val countText = objectCounts.joinToString(", ") { "${it.first}: ${it.second}" }
        val message = "I can see $totalCount objects: $countText"

        _state.value = AppState.Success(message)
        speak(message)
    }

    fun estimateDistance(command: String) {
        val objectName = extractObjectForDistance(command)

        if (objectName.isEmpty()) {
            val message = "I didn't understand which object you're asking about."
            _state.value = AppState.Success(message)
            speak(message)
            return
        }

        val detections = getObjectDetections()
        val matchingObjects = detections.filter { it.text.contains(objectName, ignoreCase = true) }

        if (matchingObjects.isEmpty()) {
            val message = "I don't see any $objectName to estimate distance."
            _state.value = AppState.Success(message)
            speak(message)
            return
        }

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

        val message = "The $objectName is $distanceEstimate."
        _state.value = AppState.Success(message)
        speak(message)
    }

    private fun extractObjectForDistance(command: String): String {
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

        val objectSize = (rect.width() * rect.height()).toFloat() / (screenWidth * screenHeight)
        val sizeDescription = when {
            objectSize > 0.25 -> "very close"
            objectSize > 0.1 -> "nearby"
            objectSize > 0.05 -> "at medium distance"
            else -> "far away"
        }

        return "$horizontalPosition, $verticalPosition, $sizeDescription"
    }

    private fun getScreenWidth(): Int {
        return getApplication<Application>().resources.displayMetrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        return getApplication<Application>().resources.displayMetrics.heightPixels
    }

    fun startNavigation() {
        val message = "Navigation started"
        speak(message)
        _state.value = AppState.Success(message)
    }

    fun stopNavigation() {
        val message = "Navigation stopped"
        speak(message)
        _state.value = AppState.Success(message)
    }

    // Notes functionality
    fun addNote(noteContent: String) {
        if (noteContent.isNotEmpty()) {
            _notes.value += noteContent
            val message = "Note added: $noteContent"
            speak(message)
            _state.value = AppState.Success(message)
        } else {
            val message = "Please provide note content."
            speak(message)
            _state.value = AppState.Error(message)
        }
    }

    fun readNotes() {
        if (_notes.value.isEmpty()) {
            val message = "You have no notes."
            speak(message)
            _state.value = AppState.Success(message)
        } else {
            val notesText = _notes.value.mapIndexed { index, note ->
                "Note ${index + 1}: $note"
            }.joinToString(". ")
            speak("Your notes: $notesText")
            _state.value = AppState.Success("Notes read successfully")
        }
    }

    fun handleDeleteNote(parameter: String) {
        val noteIndex = parameter.toIntOrNull()?.minus(1)

        if (noteIndex != null && noteIndex >= 0 && noteIndex < _notes.value.size) {
            val deletedNote = _notes.value[noteIndex]
            _notes.value = _notes.value.toMutableList().apply { removeAt(noteIndex) }
            val message = "Note deleted: $deletedNote"
            speak(message)
            _state.value = AppState.Success(message)
        } else {
            val message = "Invalid note number. Please provide a valid note number."
            speak(message)
            _state.value = AppState.Error(message)
        }
    }

    fun clearAllNotes() {
        _notes.value = emptyList()
        val message = "All notes cleared."
        speak(message)
        _state.value = AppState.Success(message)
    }

    // Object detection and image labeling support
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
                    box = Rect(detection.box),
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

    // Helper functions
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

    fun closeWebSocketConnection() {
        webSocketService.disconnect()
    }

    fun onWebSocketCleared() {
        webSocketService.disconnect()
    }
}

/**
 * Extension function to add WebSocket functionality to MainViewModel
 */
fun MainViewModel.initializeWebSocketService() {
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
                }
                else -> { /* Handle other states as needed */ }
            }
        }
    }
}