package com.example.eyesai.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eyesai.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Define states for different features
sealed class AppState {
    object Idle : AppState()
    object Loading : AppState()
    data class Success(val message: String) : AppState()
    data class Error(val error: String) : AppState()
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

    // StateFlow to manage UI states for each feature
    private val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> get() = _state

    // Voice command configurations for each screen
    private val screenCommands: MutableMap<ScreenType, List<String>> = mutableMapOf()

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
            try {
                if (command in screenCommands[screen].orEmpty()) {
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
                // Simulate navigation logic
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
                // Simulate camera-based scene description logic
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
                // Simulate current screen description logic
                val description = when (screen) {
                    ScreenType.Home -> "You are on the Home Screen. Commands available: Navigate to camera, Describe current screen."
                    ScreenType.Describe -> "You are on the Camera Screen. Commands available: Describe scenery, Capture photo."
                    ScreenType.Shop -> "You are on the Shopping Screen. Commands available: Add item to list, View shopping cart."
                    ScreenType.Navigation -> "You are on the Navigation Screen. Commands available: Start navigation, Stop navigation."
                    ScreenType.Notes -> "You are on notes screen."
                }
                _state.value = AppState.Success(description)
            } catch (e: Exception) {
                _state.value = AppState.Error("Screen description failed: ${e.message}")
            }
        }
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
