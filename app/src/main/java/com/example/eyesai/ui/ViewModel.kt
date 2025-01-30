package com.example.eyesai.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.example.eyesai.AppScreen
import com.example.eyesai.BuildConfig
import com.example.eyesai.R
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.Locale

// Define UI states for different features
sealed class AppState {
    object Idle : AppState()
    object Loading : AppState()
    data class Success(val message: String) : AppState()
    data class Error(val error: String) : AppState()
}

// Define UI states for Gemini Vision task
sealed class GeminiState {
    object Idle : GeminiState()
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

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    // StateFlow for general UI state
    private val _state = MutableStateFlow<AppState>(AppState.Idle)
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

    // Voice command configurations for each screen
    private val screenCommands = mutableMapOf<ScreenType, List<String>>()

    // Gemini Model
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.0-flash-exp",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val contentResolver = application.contentResolver

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

    init {
        setupDefaultVoiceCommands()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onCleared() {
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }

    // Function to setup default voice commands for each screen
    private fun setupDefaultVoiceCommands() {
        screenCommands[ScreenType.Home] = listOf("Navigate to camera", "Describe current screen", "Go to shopping")
        screenCommands[ScreenType.Describe] = listOf("Describe scenery", "Capture photo", "Navigate to home")
        screenCommands[ScreenType.Shop] = listOf("Add item to list", "View shopping cart", "Go to navigation")
        screenCommands[ScreenType.Navigation] = listOf("Start navigation", "Stop navigation", "Return to home")
    }

    // Function to handle voice commands
    fun handleVoiceCommand(command: String, navController: NavHostController) {
        viewModelScope.launch {
            _state.value = AppState.Loading
            _isVoiceCommandActive.value = true
            try {
                val validCommands = screenCommands[screenType.value].orEmpty()
                if (true) {
                    when {
                        command.startsWith("navigate to", ignoreCase = true) -> {
                            handleNavigation(command, navController)
                        }
                        command.startsWith("go to", ignoreCase = true) -> {
                            handleNavigation(command, navController)
                        }
                        command.startsWith("open", ignoreCase = true) -> {
                            handleNavigation(command, navController)
                        }
                        command.startsWith("please describe", ignoreCase = true) -> {
                            navController.currentDestination?.route?.let {
                                describeScenery(command,
                                    it
                                )
                            }
                        }
                        command.equals("describe current screen", true) -> describeCurrentScreen(screenType.value)
                        else -> _state.value = AppState.Success("Command executed: $command")
                    }
                } else {
                    _state.value = AppState.Error("Invalid command: $command")
                }
            } catch (e: Exception) {
                _state.value = AppState.Error("Command handling failed: ${e.message}")
            }
        }
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
                        // Pop up to the start destination of the graph to
                        // avoid building up a large stack of destinations
                        popUpTo(AppScreen.Home.name) {
                            saveState = true
                        }
                        // Avoid multiple copies of the same destination
                        launchSingleTop = true
                        // Restore state when reselecting a previously selected item
                        restoreState = true
                    }
                }
                lateinit var message: String;
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

    // Function to describe the scenery (from camera)
    private fun describeScenery(command: String, route: String) {
        val prompt = command.substringAfter("please describe", "").trim()
        Log.d("Prompt", prompt)
        if (route == AppScreen.Describe.name) {
            updateCaptureState(true)
            viewModelScope.launch {
                // Wait for the image to be captured and stored
                while (_geminiState.value !is GeminiState.File) {
                    delay(500) // Polling interval
                }
                updateCaptureState(false)
                val instruction = """
                    Describe this image for a blind person. Focus on essential details and spatial relationships.
                    Keep in mind that your output should be directly usable for tts and avoid using markdown or any other syntax.
                    Use only those symbols and punctuations which will likely improve the tts.
                    Context from user: $prompt
                """.trimIndent()

                // Process the captured image
                val uri = (_geminiState.value as GeminiState.File).uri
                val bitmap = uriToBitmap(uri)
                if (bitmap != null) {
                    sendPrompt(bitmap, instruction)
                }
                val successMsg = "Captured image. Please wait for 2-3 minutes."
                speak(successMsg)
                _state.value = AppState.Success(successMsg)
            }
        }   else if (_geminiState.value is GeminiState.Error)   {
            val errorMsg = "No image captured. Please try again."
            speak(errorMsg)
            Log.i("GEMINIError", (_geminiState.value as GeminiState.Error).error)
            _state.value = AppState.Error(errorMsg)
        }
    }

    // Function to describe the current screen
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

    // Function to send an image and prompt to Gemini AI
    fun sendPrompt(bitmap: Bitmap, prompt: String) {
        _geminiState.value = GeminiState.Loading
        Log.i("prompt", "In prompt function")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
                response.text?.let { outputContent ->
                    _geminiState.value = GeminiState.Success(outputContent)
                    speak(outputContent)
                    Log.d("VisionOutput", outputContent)
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

    fun storeUri(uri: Uri){
        _geminiState.value = GeminiState.File(uri)
    }

    fun updateVoicStatus(active: Boolean){
        _isVoiceCommandActive.value = active
    }

    fun updateCaptureState(active: Boolean){
        _shouldCapture.value = active
    }

    fun updateScreenType(screen: ScreenType) {
        _screenType.value = screen
    }

    // Function to add a new voice command for a specific screen
    fun addVoiceCommand(screen: ScreenType, command: String) {
        screenCommands[screen] = screenCommands[screen].orEmpty() + command
    }

    // Function to remove a voice command for a specific screen
    fun removeVoiceCommand(screen: ScreenType, command: String) {
        screenCommands[screen] = screenCommands[screen].orEmpty().filterNot { it == command }
    }
}