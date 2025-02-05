package com.example.eyesai.ui.screen

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import com.example.eyesai.database.helpers.StoredFace
import com.example.eyesai.ui.FaceRecognitionViewModel

@OptIn(ExperimentalGetImage::class)
@Composable
fun NavigationScreen() {
    val viewModel: FaceRecognitionViewModel = hiltViewModel()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showCaptureUI by remember { mutableStateOf(false) }
    var faceName by remember { mutableStateOf("") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val recognizedFace by viewModel.recognizedFace.collectAsState()
    val recognizedName by viewModel.recognizedName.collectAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Camera Preview
        Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
            NavigationPreview(
                onImageCaptured = { imageProxy ->
                    val bitmap = imageProxy.toBitmap()
                    viewModel.processImage(bitmap)
                    capturedBitmap = bitmap
                    imageProxy.close()
                }
            )

            recognizedFace?.let { face ->
                Image(
                    bitmap = face.asImageBitmap(),
                    contentDescription = "Recognized Face",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(100.dp)
                )
            }
        }

        // Recognition Result
        Text(
            text = "Recognized: ${recognizedName}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )

        // Face Capture UI
        if (showCaptureUI) {
            TextField(
                value = faceName,
                onValueChange = { faceName = it },
                label = { Text("Enter Face Name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            Button(
                onClick = {
                    if (faceName.isNotBlank() && capturedBitmap != null) {
                        val scaledBitmap = Bitmap.createScaledBitmap(capturedBitmap!!, 112, 112, true)

                        // Save Face to Room Database
                        viewModel.insertFace(
                            StoredFace(
                                name = faceName,
                                embeddings = viewModel.extractEmbeddings(scaledBitmap).toList()
                            )
                        )

                        // Reset UI
                        showCaptureUI = false
                        faceName = ""
                        capturedBitmap = null
                    }
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Save Face")
            }
        } else {
            Button(
                onClick = { showCaptureUI = true },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Add New Face")
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun NavigationPreview(onImageCaptured: (ImageProxy) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val lastProcessedTimestamp = remember { mutableStateOf(0L) }
    val processingInterval = 500L

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(executor) { imageProxy ->
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProcessedTimestamp.value >= processingInterval) {
                                try {
                                    onImageCaptured(imageProxy)
                                    lastProcessedTimestamp.value = currentTime
                                } catch (e: Exception) {
                                    Log.e("ImageAnalysis", "Analysis error", e)
                                } finally {
                                    imageProxy.close()
                                }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}