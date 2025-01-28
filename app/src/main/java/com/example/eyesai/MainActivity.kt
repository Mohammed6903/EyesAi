package com.example.eyesai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.eyesai.ui.theme.EyesAiTheme

enum class AppScreen(@StringRes val title: Int) {
    Home(title = R.string.home),
    Navigation(title = R.string.Navigation),
    Shop(title = R.string.Shop),
    Describe(title = R.string.Describe),
    Notes(title = R.string.notes)
}

class MainActivity : ComponentActivity(), RecognitionListener {
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.INTERNET,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECORD_AUDIO,
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
            permission ->
        val allPermissionGranted = permission.entries.all { it.value }
        if (!allPermissionGranted) {
            Log.e("MainActivity", "Some permissions were denied: ${permission.filterValues { !it }}")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestPermissions()

        setContent {
            EyesAiTheme {
                val navControllerInstance = rememberNavController()
                navController = navControllerInstance
                Navigator(navController = navController!!)
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        resetSpeechRecognizer()
        setRecognizerIntent()
        startListening()
    }

    private fun resetSpeechRecognizer() {
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            if (SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                setRecognitionListener(this@MainActivity)
            } else {
                Log.e(TAG, "Speech recognition is not available")
                finish()
            }
        }
    }

    private fun setRecognizerIntent() {
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    private fun startListening() {
        speech?.startListening(recognizerIntent)
        isListening = true
    }

    override fun onResume() {
        super.onResume()
        initializeSpeechRecognizer()
    }

    override fun onPause() {
        super.onPause()
        speech?.stopListening()
        isListening = false
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.destroy()
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
        resetSpeechRecognizer()
        startListening()
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

            // Handle navigation commands
            when {
                recognizedText.startsWith("navigate to", ignoreCase = true) -> {
                    val command = recognizedText.lowercase()
                    handleNavigation(command)
                }
                recognizedText.startsWith("go to", ignoreCase = true) -> {
                    val command = recognizedText.lowercase()
                    handleNavigation(command)
                }
                recognizedText.startsWith("open", ignoreCase = true) -> {
                    val command = recognizedText.lowercase()
                    handleNavigation(command)
                }
            }
        }
        startListening()
    }

    override fun onRmsChanged(rmsdB: Float) {
        progressValue = rmsdB / 10f // Normalize the value for the progress indicator
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

    private fun handleNavigation(command: String) {
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
            navController?.navigate(screen.name) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    modifier: Modifier = Modifier,
    currentScreen: AppScreen = AppScreen.Home,
    onNavigateBack: () -> Unit = {}
) {
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary
        ),
        title = {
            Text(text = stringResource(currentScreen.title))
        },
        navigationIcon = {
            if (currentScreen != AppScreen.Home) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go Back"
                    )
                }
            }
        }
    )
}

@Composable
fun Navigator(
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentScreen = AppScreen.valueOf(
        backStackEntry?.destination?.route ?: AppScreen.Home.name
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AppBar(
                currentScreen = currentScreen,
                onNavigateBack = { navController.navigateUp() }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Home,
                    onClick = { navController.navigate(AppScreen.Home.name) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text(text = stringResource(AppScreen.Home.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Navigation,
                    onClick = { navController.navigate(AppScreen.Navigation.name) },
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = "Navigation") },
                    label = { Text(text = stringResource(AppScreen.Navigation.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Shop,
                    onClick = { navController.navigate(AppScreen.Shop.name) },
                    icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = "Shop") },
                    label = { Text(text = stringResource(AppScreen.Shop.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Describe,
                    onClick = { navController.navigate(AppScreen.Describe.name) },
                    icon = { Icon(Icons.Default.Face, contentDescription = "Describe") },
                    label = { Text(text = stringResource(AppScreen.Describe.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Notes,
                    onClick = { navController.navigate(AppScreen.Notes.name) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Notes") },
                    label = { Text(text = stringResource(AppScreen.Notes.title)) }
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = AppScreen.Home.name,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(
                AppScreen.Home.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                HomeScreen(navController)
            }
            composable(
                AppScreen.Shop.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                ShoppingScreen(navController)
            }
            composable(
                AppScreen.Describe.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                CameraScreen(navController)
            }
            composable(
                AppScreen.Navigation.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                NavigationScreen(navController)
            }
            composable(
                AppScreen.Notes.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                NotesScreen(navController)
            }
        }
    }
}

@Composable
fun HomeScreen(navController: NavHostController) {
    ScreenTemplate(
        title = "Home Screen",
        description = "This is the home screen. Navigate to other features using the buttons.",
        actions = listOf(
            "Go to Camera" to { navController.navigate(AppScreen.Describe.name) },
            "Go to Shopping" to { navController.navigate(AppScreen.Shop.name) },
            "Go to Navigation" to { navController.navigate(AppScreen.Navigation.name) }
        )
    )
}

@Composable
fun CameraScreen(navController: NavHostController) {
    ScreenTemplate(
        title = "Camera Screen",
        description = "This is the camera screen. Use it to describe the scenery or take photos.",
        actions = listOf(
            "Go to Home" to { navController.navigate(AppScreen.Home.name) },
            "Go to Shopping" to { navController.navigate(AppScreen.Shop.name) }
        )
    )
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