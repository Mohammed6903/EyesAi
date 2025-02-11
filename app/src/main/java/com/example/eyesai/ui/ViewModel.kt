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
import org.apache.commons.text.similarity.LevenshteinDistance
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

    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes.asStateFlow()

    // Command handlers for each screen
    private val commandHandlers = mutableMapOf<ScreenType, CommandHandler>()

    // Gemini Model
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.0-flash-exp",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val contentResolver = application.contentResolver

    init {
        setupCommandHandlers()
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
                    val parameterizedCommands = listOf("add note", "read notes", "delete note", "hey")

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
                            speak("Executing: $closestMatch")
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
        }

        override fun getCommands(): List<String> {
            return listOf("hey") + navigationCommands
        }
    }

    private inner class ShopCommandHandler : CommandHandler {
        override fun handleCommand(command: String, navController: NavHostController) {
            val lowercaseCommand = command.lowercase()
            extractScreenName(lowercaseCommand)?.let {
                handleNavigation(it, navController)
                return
            }
            when (lowercaseCommand) {
                "add item to list", "add to shopping list" -> addItemToShoppingList()
                "view shopping cart", "show cart", "check cart" -> viewShoppingCart()
                else -> _state.value = AppState.Success("Command executed: $command")
            }
        }

        override fun getCommands(): List<String> {
            return listOf("add item to list", "view shopping cart") + navigationCommands
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
                    Answer the following question directly and concisely. Avoid any introductory phrases or filler words. 
                    Provide the information in a way that is easy to understand when read aloud by a screen reader.
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

    private fun addItemToShoppingList() {
        // Implement adding item to shopping list
    }

    private fun viewShoppingCart() {
        // Implement viewing shopping cart
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