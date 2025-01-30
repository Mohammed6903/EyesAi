package com.example.eyesai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.eyesai.ui.AppState
import com.example.eyesai.ui.GeminiState
import com.example.eyesai.ui.MainViewModel
import com.example.eyesai.ui.ScreenType
import com.example.eyesai.ui.components.Navigator
import com.example.eyesai.ui.theme.EyesAiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStream

enum class AppScreen(@StringRes val title: Int) {
    Home(title = R.string.home),
    Navigation(title = R.string.Navigation),
    Shop(title = R.string.Shop),
    Describe(title = R.string.Describe),
    Notes(title = R.string.notes)
}

class MainActivity : ComponentActivity(), RecognitionListener {
    private val viewModel: MainViewModel by viewModels()
    private var capturedImageUri: Uri? = null
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.INTERNET,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permission ->
        val allPermissionGranted = permission.entries.all { it.value }
        if (!allPermissionGranted) {
            Log.e(
                "MainActivity",
                "Some permissions were denied: ${permission.filterValues { !it }}"
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    private val TAG = "VoiceRecognitionActivity"

    private var speech: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent

    private var recognizedText by mutableStateOf("")
    private var errorText by mutableStateOf("")
    private var progressValue by mutableFloatStateOf(0f)
    private var isListening by mutableStateOf(false)

    private var navController: NavHostController? = null

    private var shouldCaptureImage by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestPermissions()

        setContent {
            EyesAiTheme {
                val navControllerInstance = rememberNavController()
                navController = navControllerInstance

                val appState by viewModel.state.collectAsState()
                val geminiState by viewModel.geminiState.collectAsState()
                val voiceCommandState by viewModel.isVoiceCommandActive.collectAsState()
                val shouldCapture by viewModel.shouldCapture.collectAsState()

                val updateScreenType: (ScreenType) -> Unit = { screenType ->
                    viewModel.updateScreenType(screenType)
                }
                LaunchedEffect(appState) {
                    when (appState) {
                        is AppState.Error -> {
                            // Handle error (e.g., show toast or speak error message)
                            (appState as AppState.Error).error.let { error ->
                                Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                            }
                        }

                        is AppState.Success -> {
                            // Handle success (e.g., speak success message)
                            (appState as AppState.Success).message.let { message ->
                                // You could implement TTS here
                            }
                        }

                        else -> {} // Handle other states if needed
                    }
                }
                LaunchedEffect(geminiState) {
                    when (geminiState) {
                        is GeminiState.Success -> {
                            // Handle successful image description
                            (geminiState as GeminiState.Success).response.let { response ->
                                // You could implement TTS here for the description
                                Log.d("VisionOutput", (geminiState as GeminiState.Success).response)
                            }
                        }

                        is GeminiState.Error -> {
                            // Handle error
                            Toast.makeText(
                                this@MainActivity,
                                (geminiState as GeminiState.Error).error,
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d("ErrorVision", (geminiState as GeminiState.Error).error)
                        }

                        else -> {} // Handle other states if needed
                    }
                }
                Navigator(
                    navController = navController!!,
                    onImageCaptured = { uri ->
                        capturedImageUri = uri
                        viewModel.storeUri(uri)
                    },
                    updateScreenType = updateScreenType,
                    isVoiceCommandActive = shouldCapture
                )
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            resetSpeechRecognizer()
            setRecognizerIntent()
            startListening()
        } else {
            Log.e(TAG, "Speech recognition is not available")
        }
    }

//    private fun resetSpeechRecognizer() {
//        speech?.destroy()
//        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
//            if (SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
//                setRecognitionListener(this@MainActivity)
//            } else {
//                Log.e(TAG, "Speech recognition is not available")
//                finish()
//            }
//        }
//    }
    private fun resetSpeechRecognizer() {
        if (speech == null) {
            speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
                if (SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    setRecognitionListener(this@MainActivity)
                } else {
                    Log.e(TAG, "Speech recognition is not available")
                    finish()
                }
            }
        }
    }

    private fun setRecognizerIntent() {
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en")
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    private fun startListening() {
        speech?.startListening(recognizerIntent)
        isListening = true
        viewModel.updateVoicStatus(true)
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateVoicStatus(true)
        initializeSpeechRecognizer()
    }

    override fun onPause() {
        super.onPause()
        speech?.stopListening()
        isListening = false
        viewModel.updateVoicStatus(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.destroy()
        viewModel.updateVoicStatus(false)
    }

    override fun onBeginningOfSpeech() {
        Log.i(TAG, "onBeginningOfSpeech")
        progressValue = 0f
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        Log.i(TAG, "onBufferReceived: $buffer")
    }

    override fun onEndOfSpeech() {
        Log.i(TAG, "onEndOfSpeech")
        progressValue = 0f
    }

    override fun onError(errorCode: Int) {
        val errorMessage = getErrorText(errorCode)
        Log.i(TAG, "FAILED $errorMessage")
        errorText = errorMessage
        when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                lifecycleScope.launch {
                    delay(500)
                    startListening()
                }
            }
            else -> {
                resetSpeechRecognizer()
                startListening()
            }
        }
        viewModel.updateVoicStatus(false)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        Log.i(TAG, "onEvent")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        Log.i(TAG, "onPartialResults")
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.i(TAG, "onReadyForSpeech")
        errorText = ""
    }

    override fun onResults(results: Bundle?) {
        Log.i(TAG, "onResults")
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { matches ->
            recognizedText = matches.joinToString("\n")
            Log.i("Recognized Text", recognizedText)

            navController?.let { viewModel.handleVoiceCommand(recognizedText, it) }
        }
        lifecycleScope.launch {
            delay(300)
            startListening()
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        progressValue = rmsdB / 10f
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            SpeechRecognizer.ERROR_SERVER -> "Error from server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Didn't understand, please try again."
        }
    }
}

@Composable
fun ShoppingScreen(navController: NavHostController) {
    ScreenTemplate(
        title = "Shopping Screen",
        description = "This is the shopping screen. Add items to your shopping list or check out.",
        actions = listOf(
            "Go to Home" to { navController.navigate(AppScreen.Home.name) },
            "Go to Camera" to { navController.navigate(AppScreen.Describe.name) }
        )
    )
}

@Composable
fun NavigationScreen(navController: NavHostController) {
    ScreenTemplate(
        title = "Navigation Screen",
        description = "This is the navigation screen. Start or stop navigation.",
        actions = listOf(
            "Go to Home" to { navController.navigate(AppScreen.Home.name) }
        )
    )
}

@Composable
fun NotesScreen(navController: NavHostController) {
    ScreenTemplate(
        title = "Notes Screen",
        description = "This is the notes screen. Write and manage your notes here.",
        actions = listOf(
            "Go to Home" to { navController.navigate(AppScreen.Home.name) }
        )
    )
}

@Composable
fun ScreenTemplate(
    title: String,
    description: String,
    actions: List<Pair<String, () -> Unit>>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, modifier = Modifier.padding(bottom = 8.dp))
        Text(text = description, modifier = Modifier.padding(bottom = 16.dp))
        actions.forEach { (actionLabel, action) ->
            Button(
                onClick = action,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(text = actionLabel)
            }
        }
    }
}