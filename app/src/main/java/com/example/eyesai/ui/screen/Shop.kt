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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.net.Uri
import com.example.eyesai.service.RetrofitClient
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

data class BarcodeLookupResponse(
    val products: List<Product>
)

data class Product(
    val barcode_number: String,
    val title: String,
    val category: String,
    val manufacturer: String,
    val brand: String,
    val description: String,
    val images: List<String>,
    val features: List<String>,
    val stores: List<Store>,
    val reviews: List<Review>
)

data class Store(
    val name: String,
    val price: String,
    val currency: String,
    val link: String
)

data class Review(
    val name: String,
    val rating: String,
    val title: String,
    val review: String
)

@Composable
fun ShopScreen(onImageCaptured: (bitmap: Bitmap) -> Unit) {
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
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                onImageCaptured(bitmap)
            }
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

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        var textComplete = false
        var barcodeComplete = false

        fun tryCloseImageProxy() {
            if (textComplete && barcodeComplete) {
                imageProxy.close()
            }
        }

        // Text recognition
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val recognizedText = visionText.text
                if (recognizedText.isNotEmpty()) {
                    val mrpValue = extractMRPFromText(recognizedText)
                    if (mrpValue != "MRP not found") {
                        comparePriceWithBudget(mrpValue, context)
                    }
                    announceProductDescription(recognizedText, context)
                    detectExpiryDate(recognizedText, context)
                }
            }
            .addOnCompleteListener {
                textComplete = true
                tryCloseImageProxy()
            }

        // Barcode scanning
        val barcodeScanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrEmpty()) {
                        Log.e("BarCode", rawValue)
                        fetchProductDetails(rawValue, context)
                    }
                }
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

private fun announceProductDescription(text: String, context: android.content.Context) {
    val description = extractProductDescription(text)
//    if (description.isNotEmpty()) {
//        android.os.Handler(android.os.Looper.getMainLooper()).post {
//            android.widget.Toast.makeText(
//                context,
//                "Product Description: $description",
//                android.widget.Toast.LENGTH_LONG
//            ).show()
//        }
//    }
}

private fun extractProductDescription(text: String): String {
    val keywords = listOf("ingredients", "contains", "description", "details")
    val sentences = text.split(". ")
    val description = sentences.filter { sentence ->
        keywords.any { keyword ->
            sentence.contains(keyword, ignoreCase = true)
        }
    }.joinToString(". ")
    return if (description.isNotEmpty()) description else "No description found"
}

private fun comparePriceWithBudget(mrp: String, context: android.content.Context) {
    val budget = 100.0
    val price = mrp.replace("Rs", "").trim().toDoubleOrNull() ?: 0.0
    if (price > budget) {
//        android.os.Handler(android.os.Looper.getMainLooper()).post {
//            android.widget.Toast.makeText(
//                context,
//                "Price exceeds your budget of Rs $budget",
//                android.widget.Toast.LENGTH_LONG
//            ).show()
//        }
    } else {
//        android.os.Handler(android.os.Looper.getMainLooper()).post {
//            android.widget.Toast.makeText(
//                context,
//                "Price is within your budget",
//                android.widget.Toast.LENGTH_SHORT
//            ).show()
//        }
    }
}

private fun fetchProductDetails(barcode: String, context: android.content.Context) {
    val call = RetrofitClient.instance.getProductDetails(barcode)
    call.enqueue(object : Callback<BarcodeLookupResponse> {
        override fun onResponse(
            call: Call<BarcodeLookupResponse>,
            response: Response<BarcodeLookupResponse>
        ) {
            if (response.isSuccessful && response.body() != null) {
                val product = response.body()!!.products.firstOrNull()
                if (product != null) {
                    announceProductDetails(product, context)
                } else {
//                    android.widget.Toast.makeText(
//                        context,
//                        "No product details found",
//                        android.widget.Toast.LENGTH_SHORT
//                    ).show()
                }
            } else {
//                android.widget.Toast.makeText(
//                    context,
//                    "Failed to fetch product details",
//                    android.widget.Toast.LENGTH_SHORT
//                ).show()
            }
        }

        override fun onFailure(call: Call<BarcodeLookupResponse>, t: Throwable) {
            android.widget.Toast.makeText(
                context,
                "Network error: ${t.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    })
}

private fun announceProductDetails(product: Product, context: android.content.Context) {
    val message = """
        Product: ${product.title}
        Brand: ${product.brand}
        Description: ${product.description}
        Features: ${product.features.joinToString(", ")}
    """.trimIndent()

    Log.d("barcode", message);

    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(
            context,
            message,
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

private fun detectExpiryDate(text: String, context: android.content.Context) {
    val expiryDate = extractExpiryDate(text)
    if (expiryDate.isNotEmpty()) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context,
                "Expiry Date: $expiryDate",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    val mediaImage = imageProxy.image ?: return null

    try {
        // Create an InputImage from the ImageProxy
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        // Getting the YUV_420_888 image format
        val yBuffer = mediaImage.planes[0].buffer
        val uBuffer = mediaImage.planes[1].buffer
        val vBuffer = mediaImage.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            mediaImage.width,
            mediaImage.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, mediaImage.width, mediaImage.height),
            100,
            out
        )

        val imageBytes = out.toByteArray()
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Apply rotation if needed
        if (imageProxy.imageInfo.rotationDegrees != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            bitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
        }

        return bitmap
    } catch (e: Exception) {
        Log.e("ImageConversion", "Failed to convert ImageProxy to Bitmap", e)
        return null
    }
}

private fun extractExpiryDate(text: String): String {
    val datePattern = Regex("\\b(\\d{2}[\\-/.]\\d{2}[\\-/.]\\d{4})\\b")
    return datePattern.find(text)?.value ?: "Expiry date not found"
}