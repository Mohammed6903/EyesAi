package com.example.eyesai.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import android.graphics.Bitmap
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

@Composable
fun ShopScreen() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraPreview { imageProxy ->
            processImage(imageProxy, context)
        }
    } else {
        Text("Camera permission not granted")
    }
}

@Composable
fun CameraPreview(
    onImageCaptured: (ImageProxy) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Set up the preview use case
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Set up the image analysis use case
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { imageProxy ->
                            onImageCaptured(imageProxy)
                        }
                    }

                // Select the back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    // Unbind all use cases before rebinding
                    cameraProvider.unbindAll()

                    // Bind the camera to the lifecycle
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageAnalysis
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

@OptIn(ExperimentalGetImage::class)
private fun processImage(imageProxy: ImageProxy, context: android.content.Context) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            Log.e("TextRecognition", "Failed to get media image")
            imageProxy.close()
            return
        }

        // Create input image once for both processes
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        // Create a flag to track when both processes are complete
        var textComplete = false
        var barcodeComplete = false

        // Function to close image proxy when both processes are done
        fun tryCloseImageProxy() {
            if (textComplete && barcodeComplete) {
                try {
                    imageProxy.close()
                } catch (e: Exception) {
                    Log.e("ImageProcessing", "Error closing image proxy", e)
                }
            }
        }

        // Text recognition process
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                runCatching {
                    val recognizedText = visionText.text
                    if (recognizedText.isNotEmpty()) {
                        Log.d("TextRecognition", "Recognized text: $recognizedText")
                        // Extract MRP only if text is found
                        val mrpValue = extractMRPFromText(recognizedText)
                        if (mrpValue != "MRP not found") {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    context,
                                    "MRP: $mrpValue",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }.onFailure { e ->
                    Log.e("TextRecognition", "Error processing text result", e)
                }
            }
            .addOnFailureListener { e ->
                Log.e("TextRecognition", "Text recognition failed", e)
            }
            .addOnCompleteListener {
                textComplete = true
                tryCloseImageProxy()
            }

        // Barcode scanning process
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        val barcodeScanner = BarcodeScanning.getClient(options)

        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                runCatching {
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrEmpty()) {
                            Log.d("BarcodeScanner", "Barcode detected: $rawValue")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    context,
                                    "Barcode: $rawValue",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }.onFailure { e ->
                    Log.e("BarcodeScanner", "Error processing barcode result", e)
                }
            }
            .addOnFailureListener { e ->
                Log.e("BarcodeScanner", "Barcode scanning failed", e)
            }
            .addOnCompleteListener {
                barcodeComplete = true
                tryCloseImageProxy()
            }

    } catch (e: Exception) {
        Log.e("ImageProcessing", "Fatal error in image processing", e)
        imageProxy.close()
    }
}

private fun extractMRPFromText(text: String): String {
    val mrpPattern = Regex("Rs\\s*[:\\-]?\\s*(\\d+(?:\\.\\d{2})?)", RegexOption.IGNORE_CASE)
    val mrpPattern2 = Regex("MRP\\s*[:\\-]?\\s*(\\d+(?:\\.\\d{2})?)", RegexOption.IGNORE_CASE)
    val mrp: String?;
    when {
        mrpPattern.find(text)?.groupValues?.get(1) != null -> mrp = mrpPattern.find(text)?.groupValues?.get(1).toString()
        mrpPattern2.find(text)?.groupValues?.get(1) != null -> mrp = mrpPattern2.find(text)?.groupValues?.get(1).toString()
        else -> mrp = null
    }
    return mrp ?: "MRP not found"
}