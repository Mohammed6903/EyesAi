package com.example.eyesai.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eyesai.BuildConfig
import com.example.eyesai.R
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

class MainViewModel : ViewModel() {

    // StateFlow for general UI state
    private val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> = _state.asStateFlow()

    // StateFlow for Gemini Vision task
    private val _geminiState = MutableStateFlow<GeminiState>(GeminiState.Idle)
    val geminiState: StateFlow<GeminiState> = _geminiState.asStateFlow()

    private val _isVoiceCommandActive = MutableStateFlow(false)
    val isVoiceCommandActive: StateFlow<Boolean> = _isVoiceCommandActive.asStateFlow()
    private val _shouldCapture = MutableStateFlow(false)
    val shouldCapture: StateFlow<Boolean> = _shouldCapture.asStateFlow()

    // Voice command configurations for each screen
    private val screenCommands = mutableMapOf<ScreenType, List<String>>()

    // Gemini Model
    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash-8b",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    init {
        setupDefaultVoiceCommands()
    }

    // Function to setup default voice commands for each screen
    private fun setupDefaultVoiceCommands() {
        screenCommands[ScreenType.Home] = listOf("Navigate to camera", "Describe current screen", "Go to shopping")
        screenCommands[ScreenType.Describe] = listOf("Describe scenery", "Capture photo", "Navigate to home")
        screenCommands[ScreenType.Shop] = listOf("Add item to list", "View shopping cart", "Go to navigation")
        screenCommands[ScreenType.Navigation] = listOf("Start navigation", "Stop navigation", "Return to home")
    }

    // Function to handle voice commands
    fun handleVoiceCommand(screen: ScreenType, command: String) {
        viewModelScope.launch {
            _state.value = AppState.Loading
            _isVoiceCommandActive.value = true
            try {
                val validCommands = screenCommands[screen].orEmpty()
                if (command in validCommands) {
                    when {
                        command.contains("Navigate to", true) -> {
                            val destination = command.substringAfter("Navigate to").trim()
                            navigateToScreen(destination)
                        }
                        command.equals("Describe scenery", true) -> describeScenery()
                        command.equals("Describe current screen", true) -> describeCurrentScreen(screen)
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

    // Function to navigate between screens
    private fun navigateToScreen(destination: String) {
        viewModelScope.launch {
            try {
                _state.value = AppState.Success("Navigating to $destination")
            } catch (e: Exception) {
                _state.value = AppState.Error("Navigation failed: ${e.message}")
            }
        }
    }

    // Function to describe the scenery (from camera)
    private fun describeScenery() {
        viewModelScope.launch {
            try {
                val description = "Scenery description: Beautiful mountain with a clear sky"
                _state.value = AppState.Success(description)
            } catch (e: Exception) {
                _state.value = AppState.Error("Scenery description failed: ${e.message}")
            }
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
            } catch (e: Exception) {
                _state.value = AppState.Error("Screen description failed: ${e.message}")
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
                    Log.d("VisionOutput", outputContent)
                } ?: run {
                    _geminiState.value = GeminiState.Error("Empty response from Gemini AI")
                }
            } catch (e: Exception) {
                _geminiState.value = GeminiState.Error(e.localizedMessage ?: "Unknown error")
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

    // Function to add a new voice command for a specific screen
    fun addVoiceCommand(screen: ScreenType, command: String) {
        screenCommands[screen] = screenCommands[screen].orEmpty() + command
    }

    // Function to remove a voice command for a specific screen
    fun removeVoiceCommand(screen: ScreenType, command: String) {
        screenCommands[screen] = screenCommands[screen].orEmpty().filterNot { it == command }
    }
}