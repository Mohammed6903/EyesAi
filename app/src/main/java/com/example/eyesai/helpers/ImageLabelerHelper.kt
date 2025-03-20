package com.example.eyesai.helpers

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ImageLabelerHelper(
    var confidenceThreshold: Float = 0.7f,
    var maxResults: Int = 5,
    val context: Context,
    val imageLabelerListener: LabelerListener?
) {
    private var imageLabeler: ImageLabeler? = null

    init {
        setupImageLabeler()
    }

    fun clearImageLabeler() {
        imageLabeler?.close()
        imageLabeler = null
    }

    fun setupImageLabeler() {
        try {
            // Create labeler options with specified confidence threshold and max results
            val options = ImageLabelerOptions.Builder()
                .setConfidenceThreshold(confidenceThreshold)
                .build()

            // Create the image labeler with the options
            imageLabeler = ImageLabeling.getClient(options)

            Log.d("ImageLabelerHelper", "Image labeler set up successfully")
        } catch (e: Exception) {
            imageLabelerListener?.onError("Image labeler failed to initialize: ${e.message}")
            Log.e("ImageLabelerHelper", "Failed to set up image labeler", e)
        }
    }

    fun processImage(bitmap: Bitmap, imageRotation: Int) {
        // Start inference time measurement
        val inferenceTime = SystemClock.uptimeMillis()

        // Create input image from bitmap
        val inputImage = InputImage.fromBitmap(bitmap, imageRotation)

        imageLabeler?.process(inputImage)
            ?.addOnSuccessListener { labels ->
                val filteredLabels = labels.filter { it.confidence >= confidenceThreshold }
                val processingTime = SystemClock.uptimeMillis() - inferenceTime

                imageLabelerListener?.onLabelResults(
                    filteredLabels,
                    processingTime,
                    bitmap.height,
                    bitmap.width
                )

                Log.d("ImageLabelerHelper", "Labels detected: ${filteredLabels.size}")
            }
            ?.addOnFailureListener { e ->
                imageLabelerListener?.onError("Image labeling failed: ${e.message}")
                Log.e("ImageLabelerHelper", "Error processing image", e)
            }
    }

    // Coroutine-friendly version for use with suspend functions
    suspend fun labelImageAsync(bitmap: Bitmap, imageRotation: Int): List<ImageLabel> =
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, imageRotation)

            imageLabeler?.process(inputImage)
                ?.addOnSuccessListener { labels ->
                    if (continuation.isActive) {
                        continuation.resume(labels.filter { it.confidence >= confidenceThreshold })
                    }
                }
                ?.addOnFailureListener { e ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

            continuation.invokeOnCancellation {
                Log.d("ImageLabelerHelper", "Image labeling operation cancelled")
            }
        }

    interface LabelerListener {
        fun onError(error: String)
        fun onLabelResults(
            labels: List<ImageLabel>,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }

    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.7f
        const val DEFAULT_MAX_RESULTS = 5
    }
}