
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.eyesai.viewModel.NavigationViewModel
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
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val isRotation90or270 = rotationDegrees % 180 == 90
                    val frameWidth = if (isRotation90or270) imageProxy.height else imageProxy.width
                    val frameHeight = if (isRotation90or270) imageProxy.width else imageProxy.height
                    cameraFrameSize = IntSize(frameWidth, frameHeight)
                    navViewModel.processFrame(imageProxy)
                }
            )
            when (val state = objectState.value) {
                is NavigationViewModel.ObjectDetectionState.Success -> {
                    if (previewSize.width > 0 && previewSize.height > 0 && cameraFrameSize.width > 0 && cameraFrameSize.height > 0) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val scaleFactor = max(
                                previewSize.width / cameraFrameSize.width.toFloat(),
                                previewSize.height / cameraFrameSize.height.toFloat()
                            )

                            val scaledWidth = cameraFrameSize.width * scaleFactor
                            val scaledHeight = cameraFrameSize.height * scaleFactor

                            val offsetX = (scaledWidth - previewSize.width) / 2
                            val offsetY = (scaledHeight - previewSize.height) / 2

                            state.boxes.forEach { box ->
                                val scaledLeft = box.box.left * scaleFactor - offsetX
                                val scaledTop = box.box.top * scaleFactor - offsetY
                                val scaledRight = box.box.right * scaleFactor - offsetX
                                val scaledBottom = box.box.bottom * scaleFactor - offsetY

                                val clampedLeft = scaledLeft.coerceIn(0f, previewSize.width.toFloat())
                                val clampedTop = scaledTop.coerceIn(0f, previewSize.height.toFloat())
                                val clampedRight = scaledRight.coerceIn(0f, previewSize.width.toFloat())
                                val clampedBottom = scaledBottom.coerceIn(0f, previewSize.height.toFloat())

                                val scaledBox = RectF(clampedLeft, clampedTop, clampedRight, clampedBottom)

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

                                // Draw text background and label
                                val drawableText = box.text
                                val textPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.RED
                                    textSize = 30f
                                }
                                val textBounds = android.graphics.Rect()
                                textPaint.getTextBounds(drawableText, 0, drawableText.length, textBounds)

                                drawRect(
                                    color = Color.Black.copy(alpha = 0.7f),
                                    topLeft = Offset(scaledBox.left, scaledBox.top - textBounds.height() - 8f),
                                    size = androidx.compose.ui.geometry.Size(textBounds.width() + 16f, textBounds.height() + 16f)
                                )

                                drawContext.canvas.nativeCanvas.drawText(
                                    drawableText,
                                    scaledBox.left + 8f,
                                    scaledBox.top - 8f,
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