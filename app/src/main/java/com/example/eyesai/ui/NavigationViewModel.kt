package com.example.eyesai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eyesai.database.helpers.StoredFace
import com.example.eyesai.database.repository.FaceRepository
import com.example.eyesai.helpers.ObjectDetectorHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.task.vision.detector.Detection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val repository: FaceRepository,
    @ApplicationContext private val context: Context
) : ViewModel(), ObjectDetectorHelper.DetectorListener {

    sealed class RecognitionState {
        object Loading : RecognitionState()
        data class Success(
            val name: String,
            val confidence: Float,
            val faceRect: Rect? = null
        ) : RecognitionState()

        data class Error(val message: String) : RecognitionState()
        object NoFaceDetected : RecognitionState()
        data class MultipleFacesDetected(val count: Int) : RecognitionState()
    }

    sealed class ObjectDetectionState {
        object Loading : ObjectDetectionState()
        data class Success(val boxes: List<BoxWithText>) : ObjectDetectionState()
        data class Error(val message: String) : ObjectDetectionState()
    }

    private val _recognitionState = MutableStateFlow<RecognitionState>(RecognitionState.Loading)
    val recognitionState = _recognitionState.asStateFlow()

    private var tfLite: Interpreter? = null
    private val faceDetector by lazy { setupFaceDetector() }
    private val inferenceThread = HandlerThread("inference").apply { start() }
    private val inferenceScope = CoroutineScope(Dispatchers.Default + Job())

    private val _objectDetectionState = MutableStateFlow<ObjectDetectionState>(ObjectDetectionState.Loading)
    val objectDetectionState = _objectDetectionState.asStateFlow()

    private val detectorDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    init {
        loadTFLiteModel()
        setupObjectDetector()
    }

    suspend fun detectFaces(image: InputImage, imageProxy: ImageProxy): List<Rect> {
        return suspendCancellableCoroutine { continuation ->
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    continuation.resume(faces.map { it.boundingBox })
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
                .addOnCompleteListener {
                    if (!continuation.isCompleted) {
                        continuation.resumeWithException(Exception("Face detection failed"))
                    }
                    imageProxy.close()
                }
        }
    }

    companion object {
        private const val MODEL_INPUT_SIZE = 112
        private const val MODEL_CHANNELS = 3
        private const val EMBEDDING_SIZE = 192
        private const val SIMILARITY_THRESHOLD = 0.7f
        private const val MODEL_FILENAME = "mobile_face_net.tflite"
        private const val NUM_THREADS = 4
        private const val TAG = "ObjectDetection"
        const val REQUEST_IMAGE_CAPTURE: Int = 1
        private const val MAX_FONT_SIZE = 96F
        private const val MIN_FACE_SIZE = 0.5f
        private const val FACE_PADDING_PERCENT = 0.1f
    }

    private fun setupFaceDetector() = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
    )

    private fun cropFace(bitmap: Bitmap, faceRect: Rect): Bitmap {
        // Ensure rectangle is within bitmap bounds
        val safeRect = Rect(
            maxOf(0, faceRect.left),
            maxOf(0, faceRect.top),
            minOf(bitmap.width, faceRect.right),
            minOf(bitmap.height, faceRect.bottom)
        )
        return try {
            Bitmap.createBitmap(
                bitmap,
                safeRect.left,
                safeRect.top,
                safeRect.width(),
                safeRect.height()
            )
        } catch (e: IllegalArgumentException) {
            Log.e("FaceRecognition", "Error cropping face", e)
            Log.d("FaceRecognition", "Bitmap Size: ${bitmap.width}x${bitmap.height}")
            bitmap // Return original bitmap if cropping fails
        }
    }

    private fun loadTFLiteModel() {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val modelFile = loadModelFile(MODEL_FILENAME)
            tfLite = Interpreter(modelFile, options)
            Log.d("TFLite", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("TFLite", "Error loading model", e)
            _recognitionState.value = RecognitionState.Error("Failed to load model: ${e.message}")
        }
    }

    private fun loadModelFile(modelName: String): ByteBuffer {
        context.assets.openFd(modelName).use { assetFileDescriptor ->
            val inputStream = context.assets.open(modelName)
            val bytes = ByteArray(inputStream.available())
            inputStream.read(bytes)
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.order(ByteOrder.nativeOrder())
            buffer.put(bytes)
            buffer.rewind()
            return buffer
        }
    }

    fun processImage(bitmap: Bitmap, inputImage: InputImage, imageProxy: ImageProxy, onFacesDetected: (List<Rect>) -> Unit) {
        viewModelScope.launch {
            try {
                _recognitionState.value = RecognitionState.Loading
                // Run face detection
                val faceRects = detectFaces(inputImage, imageProxy)
                onFacesDetected(faceRects)

                if (faceRects.isEmpty()) {
                    _recognitionState.value = RecognitionState.NoFaceDetected
                    return@launch
                }
                if (faceRects.size > 1) {
                    _recognitionState.value = RecognitionState.MultipleFacesDetected(faceRects.size)
                    return@launch
                }

                val faceRect = faceRects.first()
                val croppedFace = cropFace(bitmap, faceRect)
                val scaledFace = Bitmap.createScaledBitmap(
                    croppedFace,
                    MODEL_INPUT_SIZE,
                    MODEL_INPUT_SIZE,
                    true
                )
                val embeddings = extractEmbeddings(scaledFace)
                val normalizedEmbeddings = normalizeEmbeddings(embeddings)
                val faces = withContext(Dispatchers.IO) {
                    repository.getAllFaces()
                }
                val bestMatch = findBestMatch(normalizedEmbeddings, faces)
                _recognitionState.value = when {
                    bestMatch == null -> RecognitionState.Success("Unknown", 0f, faceRect)
                    bestMatch.second > SIMILARITY_THRESHOLD ->
                        RecognitionState.Success(bestMatch.first, bestMatch.second, faceRect)
                    else -> RecognitionState.Success("Unknown", bestMatch.second, faceRect)
                }
            } catch (e: Exception) {
                Log.e("FaceRecognition", "Error in recognition pipeline", e)
                _recognitionState.value = RecognitionState.Error(
                    when (e) {
                        is CancellationException -> "Process cancelled"
                        else -> e.message ?: "Unknown error"
                    }
                )
            }
        }
    }

    suspend fun extractEmbeddings(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val inputBuffer = convertBitmapToByteBuffer(bitmap)
        val outputArray = Array(1) { FloatArray(EMBEDDING_SIZE) }
        withContext(inferenceScope.coroutineContext) {
            tfLite?.run(inputBuffer, outputArray)
        }
        outputArray[0]
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(
            1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * MODEL_CHANNELS * 4
        ).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        bitmap.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        pixels.forEach { pixel ->
            byteBuffer.putFloat(((pixel shr 16 and 0xFF) - 128f) / 128f)
            byteBuffer.putFloat(((pixel shr 8 and 0xFF) - 128f) / 128f)
            byteBuffer.putFloat((pixel and (0xFF - 128f).toInt()) / 128f)
        }
        return byteBuffer.apply { rewind() }
    }

    private fun normalizeEmbeddings(embeddings: FloatArray): FloatArray {
        val magnitude = sqrt(embeddings.map { it * it }.sum())
        return embeddings.map { it / magnitude }.toFloatArray()
    }

    private fun findBestMatch(
        embeddings: FloatArray,
        storedFaces: List<StoredFace>
    ): Pair<String, Float>? {
        return storedFaces
            .map { face ->
                face.embeddings.toFloatArray().let { storedEmb ->
                    val similarity = calculateCosineSimilarity(
                        embeddings,
                        normalizeEmbeddings(storedEmb)
                    )
                    Pair(face.name, similarity)
                }
            }
            .maxByOrNull { it.second }
    }

    private fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        return emb1.zip(emb2).map { (a, b) -> a * b }.sum()
    }

    // Database operations
    fun insertFace(face: StoredFace) = viewModelScope.launch(Dispatchers.IO) {
        repository.insertFace(face)
    }

    fun deleteFaceByName(name: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteFaceByName(name)
    }

    fun deleteAllFaces() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAllFaces()
    }

    private fun setupObjectDetector() {
        objectDetectorHelper = ObjectDetectorHelper(
            threshold = 0.5f,
            numThreads = 2,
            maxResults = 5,
            currentDelegate = ObjectDetectorHelper.DELEGATE_GPU,
            currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV2,
            context = context,
            objectDetectorListener = this
        )
    }

    fun processFrame(imageProxy: ImageProxy) {
        viewModelScope.launch {
            try {
                val bitmap = imageProxy.toBitmap()
                val rotation = imageProxy.imageInfo.rotationDegrees

                // Process detection on the dedicated dispatcher
                withContext(detectorDispatcher) {
                    if (objectDetectorHelper.objectDetector == null) {
                        objectDetectorHelper.setupObjectDetector()
                    }
                    objectDetectorHelper.detect(bitmap, rotation)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private suspend fun detectObjects(bitmap: Bitmap, rotation: Int) = withContext(Dispatchers.IO) {
        objectDetectorHelper.detect(bitmap, rotation)
    }

    // ObjectDetectorHelper.DetectorListener implementation
    override fun onError(error: String) {
        _objectDetectionState.value = ObjectDetectionState.Error(error)
    }

    override fun onResults(
        results: MutableList<Detection>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        results?.let {
            val boxes = it.map { detection ->
                BoxWithText(
                    box = detection.boundingBox.toRect(),
                    text = detection.categories.firstOrNull()?.label ?: "Unknown"
                )
            }
            _objectDetectionState.value = ObjectDetectionState.Success(boxes)
        }
    }

    // Helper function to convert RectF to Rect
    private fun RectF.toRect() = Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt()
    )

    override fun onCleared() {
        super.onCleared()
        // Cleanup resources
        faceDetector.close()
        tfLite?.close()
        detectorDispatcher.close()
        objectDetectorHelper.clearObjectDetector()
    }
}

data class BoxWithText(val box: Rect, val text: String)