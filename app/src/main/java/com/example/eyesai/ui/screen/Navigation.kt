
package com.example.eyesai.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.eyesai.ui.BoxWithText
import com.example.eyesai.ui.FaceRecognitionViewModel
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalGetImage::class)
@Composable
fun NavigationScreen() {
    val viewModel: FaceRecognitionViewModel = hiltViewModel()
    var detectedObjects by remember { mutableStateOf<List<BoxWithText>>(emptyList()) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            NavigationPreview(onObjectsDetected = { detectedObjects = it }, viewModel = viewModel)
            detectedObjects.forEach { detection ->
                ObjectDetectionBox(detection.box, detection.text)
            }
        }
    }
}

@Composable
fun ObjectDetectionBox(
    rect: Rect,
    label: String
) {
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                canvasSize = androidx.compose.ui.geometry.Size(
                    size.width.toFloat(),
                    size.height.toFloat()
                )
            }
    ) {
        if (canvasSize.width > 0 && canvasSize.height > 0 && rect.width() > 0 && rect.height() > 0) {
            // Calculate scaling factors based on the original image dimensions
            val scaleX = canvasSize.width / 640f  // YOLOv11 input size
            val scaleY = canvasSize.height / 640f // YOLOv11 input size

            // Calculate scaled coordinates
            val scaledLeft = rect.left * scaleX
            val scaledTop = rect.top * scaleY
            val scaledWidth = rect.width() * scaleX
            val scaledHeight = rect.height() * scaleY

            // Draw detection box
            drawRect(
                color = Color.Red,
                topLeft = Offset(scaledLeft, scaledTop),
                size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight),
                style = Stroke(width = 4f)
            )

            // Calculate text background dimensions
            val textBackgroundHeight = 40f
            val textBackgroundWidth = (label.length * 20f).coerceAtLeast(100f)

            // Draw label background
            drawRect(
                color = Color.Red.copy(alpha = 0.3f),
                topLeft = Offset(
                    scaledLeft,
                    (scaledTop - textBackgroundHeight).coerceAtLeast(0f)
                ),
                size = androidx.compose.ui.geometry.Size(
                    textBackgroundWidth,
                    textBackgroundHeight
                )
            )

            // Draw label text
            drawContext.canvas.nativeCanvas.apply {
                drawText(
                    label,
                    scaledLeft + 10f,
                    (scaledTop - textBackgroundHeight/4f).coerceAtLeast(textBackgroundHeight),
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 32f
                        textAlign = android.graphics.Paint.Align.LEFT
                        isFakeBoldText = true
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun NavigationPreview(
    onObjectsDetected: (List<BoxWithText>) -> Unit,
    viewModel: FaceRecognitionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val bitmap = imageProxy.toBitmapCustom(imageProxy.imageInfo.rotationDegrees)
                    val detections = viewModel.detectObjectsYOLO(bitmap)
                    onObjectsDetected(detections)
                } else {
                    Log.e("NavigationPreview", "MediaImage is null")
                }
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("NavigationPreview", "Camera binding failed: ${e.message}", e)
            }

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

fun ImageProxy.toBitmapCustom(rotationDegrees: Int): Bitmap {
    val planes = this.planes
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val yuvImage = YuvImage(bytes, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 100, out)
    val yuvBytes = out.toByteArray()
    val yuvBitmap = BitmapFactory.decodeByteArray(yuvBytes, 0, yuvBytes.size)
    return rotateBitmap(yuvBitmap, rotationDegrees)
}

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}