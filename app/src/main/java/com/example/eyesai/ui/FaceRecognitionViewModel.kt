package com.example.eyesai.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.ImageView
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eyesai.database.helpers.StoredFace
import com.example.eyesai.database.repository.FaceRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.sqrt

@HiltViewModel
class FaceRecognitionViewModel @Inject constructor(
    private val repository: FaceRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

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

    private val _recognitionState = MutableStateFlow<RecognitionState>(RecognitionState.Loading)
    val recognitionState: StateFlow<RecognitionState> = _recognitionState.asStateFlow()

    private var tfLite: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val faceDetector by lazy { setupFaceDetector() }
    private val inferenceThread = HandlerThread("inference").apply { start() }
    private val inferenceHandler = Handler(inferenceThread.looper)
    private val inferenceScope = CoroutineScope(Dispatchers.Default + Job())

    // YOLOv11 model and labels
    private var yoloInterpreter: Interpreter? = null
    private val yoloLabels = mutableListOf<String>()

    // YOLOv11 model parameters
    private val yoloInputSize = 640 // YOLOv11 input size
    private val yoloOutputSize = 25200 * 85 // Adjust based on your model output
    private val confidenceThreshold = 0.7f // Confidence threshold for detections
    private val maxDetections = 20

    init {
        loadYOLOModel()
        loadYOLOLabels()
    }

    private fun loadYOLOModel() {
        try {
            val modelFile = context.assets.openFd("yolo11l_float32.tflite")
            val fileChannel = FileInputStream(modelFile.fileDescriptor).channel
            val startOffset = modelFile.startOffset
            val declaredLength = modelFile.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4) // Use 4 threads for inference
            }

            yoloInterpreter = Interpreter(modelBuffer, options)
            Log.d("YOLOv11", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("YOLOv11", "Error loading model", e)
        }
    }

    private fun loadYOLOLabels() {
        try {
            val inputStream = context.assets.open("labels.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))
            yoloLabels.addAll(reader.readLines())
            Log.d("YOLOv11", "Labels loaded: ${yoloLabels.size}")
        } catch (e: Exception) {
            Log.e("YOLOv11", "Error loading labels", e)
        }
    }

    fun detectObjectsYOLO(bitmap: Bitmap): List<BoxWithText> {
        val inputBuffer = convertBitmapToYOLOInput(bitmap)
        val outputBuffer = ByteBuffer.allocateDirect(yoloOutputSize * 4) // 4 bytes per float
        outputBuffer.order(ByteOrder.nativeOrder())

        yoloInterpreter?.run(inputBuffer, outputBuffer)
        return parseYOLOOutput(outputBuffer, bitmap.width, bitmap.height)
    }

    private fun convertBitmapToYOLOInput(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(4 * yoloInputSize * yoloInputSize * 3) // 4 bytes per float, 3 channels
        inputBuffer.order(ByteOrder.nativeOrder())

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, yoloInputSize, yoloInputSize, true)
        val pixels = IntArray(yoloInputSize * yoloInputSize)
        resizedBitmap.getPixels(pixels, 0, yoloInputSize, 0, 0, yoloInputSize, yoloInputSize)

        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }

        return inputBuffer
    }

    private fun parseYOLOOutput(outputBuffer: ByteBuffer, imageWidth: Int, imageHeight: Int): List<BoxWithText> {
        val detections = mutableListOf<BoxWithText>()
        val scaleX = imageWidth.toFloat() / yoloInputSize
        val scaleY = imageHeight.toFloat() / yoloInputSize

        for (i in 0 until yoloOutputSize step 85) { // 85 values per detection (x, y, w, h, confidence, 80 class scores)
            val confidence = outputBuffer.getFloat(i + 4)
            if (confidence > confidenceThreshold) {
                val x = outputBuffer.getFloat(i) * scaleX
                val y = outputBuffer.getFloat(i + 1) * scaleY
                val width = outputBuffer.getFloat(i + 2) * scaleX
                val height = outputBuffer.getFloat(i + 3) * scaleY

                val classScores = FloatArray(80) { outputBuffer.getFloat(i + 5 + it) }
                val classIndex = classScores.indices.maxByOrNull { classScores[it] } ?: 0
                val label = yoloLabels.getOrNull(classIndex) ?: "Unknown"

                detections.add(
                    BoxWithText(
                        box = Rect(x.toInt(), y.toInt(), (x + width).toInt(), (y + height).toInt()),
                        text = "$label (${"%.2f".format(confidence)})"
                    )
                )

                if (detections.size >= maxDetections) break
            }
        }

        // Apply Non-Maximum Suppression (NMS) to filter overlapping detections
        return nonMaxSuppression(detections)
    }

    private fun nonMaxSuppression(
        detections: List<BoxWithText>,
        iouThreshold: Float = 0.5f
    ): List<BoxWithText> {
        val selectedDetections = mutableListOf<BoxWithText>()
        val sortedDetections = detections.sortedByDescending { it.text.substringAfterLast(" ").toFloat() }.toMutableList()

        while (sortedDetections.isNotEmpty()) {
            val selected = sortedDetections.first()
            selectedDetections.add(selected)

            // Remove all detections with high IoU overlap
            sortedDetections.removeAll { detection ->
                calculateIoU(selected.box, detection.box) > iouThreshold
            }
        }

        return selectedDetections
    }

    private fun calculateIoU(box1: Rect, box2: Rect): Float {
        val intersection = Rect(
            maxOf(box1.left, box2.left),
            maxOf(box1.top, box2.top),
            minOf(box1.right, box2.right),
            minOf(box1.bottom, box2.bottom)
        )

        val intersectionArea = maxOf(0, intersection.width()) * maxOf(0, intersection.height())
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()

        return intersectionArea.toFloat() / (box1Area + box2Area - intersectionArea)
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

    private fun debugPrint(detectedObjects: List<DetectedObject>) {
        detectedObjects.forEachIndexed { index, detectedObject ->
            val box = detectedObject.boundingBox

            Log.d(TAG, "Detected object: $index")
            Log.d(TAG, " trackingId: ${detectedObject.trackingId}")
            Log.d(TAG, " boundingBox: (${box.left}, ${box.top}) - (${box.right},${box.bottom})")
            detectedObject.labels.forEach {
                Log.d(TAG, " categories: ${it.text}")
                Log.d(TAG, " confidence: ${it.confidence}")
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

        const val TAG = "MLKit-ODT"
        const val REQUEST_IMAGE_CAPTURE: Int = 1
        private const val MAX_FONT_SIZE = 96F

        // Face detection parameters
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

    init {
        loadTFLiteModel()
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

    override fun onCleared() {
        super.onCleared()
        faceDetector.close()
        tfLite?.close()
    }
}
data class BoxWithText(val box: Rect, val text: String)