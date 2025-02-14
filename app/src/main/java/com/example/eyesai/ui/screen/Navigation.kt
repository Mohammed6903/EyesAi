
package com.example.eyesai.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.eyesai.ui.BoxWithText
import com.example.eyesai.ui.NavigationViewModel
import java.io.ByteArrayOutputStream
import kotlin.math.max

@Composable
fun NavigationScreen() {
    val navViewModel: NavigationViewModel = hiltViewModel()
    val objectState = navViewModel.objectDetectionState.collectAsState()

    // State to track the size of the PreviewView
    var previewSize by remember { mutableStateOf(IntSize(0, 0)) }

    // State to track the camera frame dimensions
    var cameraFrameSize by remember { mutableStateOf(IntSize(0, 0)) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    previewSize = IntSize(size.width, size.height)
                }
        ) {
            NavigationPreview(
                onFrameCaptured = { imageProxy ->
                    // Update the camera frame dimensions dynamically
                    cameraFrameSize = IntSize(imageProxy.width, imageProxy.height)
                    navViewModel.processFrame(imageProxy)
                }
            )
            when (val state = objectState.value) {
                is NavigationViewModel.ObjectDetectionState.Success -> {
                    if (previewSize.width > 0 && previewSize.height > 0 && cameraFrameSize.width > 0 && cameraFrameSize.height > 0) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Calculate the scaling factor
                            val scaleFactor = max(
                                previewSize.width / cameraFrameSize.width.toFloat(),
                                previewSize.height / cameraFrameSize.height.toFloat()
                            )

                            state.boxes.forEach { box ->
                                // Scale the bounding box to match the PreviewView dimensions
                                val scaledBox = RectF(
                                    box.box.left * scaleFactor,
                                    box.box.top * scaleFactor,
                                    box.box.right * scaleFactor,
                                    box.box.bottom * scaleFactor
                                )

                                // Draw the bounding box
                                drawRect(
                                    color = Color.Red,
                                    topLeft = Offset(scaledBox.left, scaledBox.top),
                                    size = androidx.compose.ui.geometry.Size(
                                        scaledBox.width(),
                                        scaledBox.height()
                                    ),
                                    style = Stroke(width = 4f)
                                )

                                // Create text to display alongside detected objects
                                val drawableText = box.text

                                // Measure the text dimensions
                                val textPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.RED
                                    textSize = 30f
                                }
                                val bounds = android.graphics.Rect()
                                textPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
                                val textWidth = bounds.width()
                                val textHeight = bounds.height()

                                // Draw rect behind display text
                                drawRect(
                                    color = Color.Black.copy(alpha = 0.7f),
                                    topLeft = Offset(scaledBox.left, scaledBox.top - textHeight - 8),
                                    size = androidx.compose.ui.geometry.Size(
                                        textWidth + 16f,
                                        textHeight + 16f
                                    )
                                )

                                // Draw the label text
                                drawContext.canvas.nativeCanvas.drawText(
                                    drawableText,
                                    scaledBox.left + 8,
                                    scaledBox.top - 8,
                                    textPaint
                                )
                            }
                        }
                    }
                }
                is NavigationViewModel.ObjectDetectionState.Error -> {
                    Log.e("Error", "Error in object detection")
                }
                NavigationViewModel.ObjectDetectionState.Loading -> {
                    Log.e("Loading", "Loading in object detection")
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun NavigationPreview(
    onFrameCaptured: (ImageProxy) -> Unit
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
                onFrameCaptured(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
                Log.d("NavigationPreview", "Camera bound successfully")
            } catch (e: Exception) {
                Log.e("NavigationPreview", "Camera binding failed: ${e.message}", e)
            }

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

fun ImageProxy.toBitmapCustom(rotationDegrees: Int): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    // U and V are swapped in ImageFormat.YUV_420_888
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}