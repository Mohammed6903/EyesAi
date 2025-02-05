package com.example.eyesai.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eyesai.database.helpers.StoredFace
import com.example.eyesai.database.repository.FaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import kotlin.math.sqrt

@HiltViewModel
class FaceRecognitionViewModel @Inject constructor (
    private val repository: FaceRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _recognizedFace = MutableStateFlow<Bitmap?>(null)
    val recognizedFace = _recognizedFace.asStateFlow()

    private val _recognizedName = MutableStateFlow("")
    val recognizedName = _recognizedName.asStateFlow()

    private var tfLite: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    init {
        loadTFLiteModel()
    }

    private val inferenceThread = HandlerThread("inference").apply { start() }
    private val inferenceHandler = Handler(inferenceThread.looper)

    private val inferenceScope = CoroutineScope(Dispatchers.Default + Job())

    fun loadTFLiteModel() {
        try {
            val options = Interpreter.Options()

            // Only enable GPU if device supports it
            if (isGPUSupported()) {
                try {
                    gpuDelegate = GpuDelegate(GpuDelegate.Options().apply {
                        setPrecisionLossAllowed(false)
                        setQuantizedModelsAllowed(false)
                    })
                    options.addDelegate(gpuDelegate)
                    Log.d("TFLite", "GPU Delegate enabled")
                } catch (e: Exception) {
                    Log.e("TFLite", "GPU Delegate failed, falling back to CPU", e)
                    gpuDelegate?.close()
                    gpuDelegate = null
                }
            }

            options.setNumThreads(4)
            val modelFile = loadModelFile("mobile_face_net.tflite")

            // Initialize interpreter on inference thread
            inferenceHandler.post {
                tfLite = Interpreter(modelFile, options)
                Log.d("TFLite", "Model loaded on inference thread")
            }
        } catch (e: Exception) {
            Log.e("TFLite", "Error loading model", e)
        }
    }

    private fun isGPUSupported(): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_DEQP_LEVEL)
    }

    private fun loadModelFile(modelName: String): ByteBuffer {
        context.assets.openFd(modelName).use { assetFileDescriptor ->
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                return inputStream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.length
                )
            }
        }
    }

    // **ROOM Database Operations**
    fun insertFace(face: StoredFace) = viewModelScope.launch {
        repository.insertFace(face)
    }

    fun getFaceByName(name: String, callback: (StoredFace?) -> Unit) = viewModelScope.launch {
        val face = repository.getFaceByName(name)
        callback(face)
    }

    fun getAllFaces(callback: (List<StoredFace>) -> Unit) = viewModelScope.launch {
        val faces = repository.getAllFaces()
        callback(faces)
    }

    fun deleteFaceByName(name: String) = viewModelScope.launch {
        repository.deleteFaceByName(name)
    }

    fun deleteAllFaces() = viewModelScope.launch {
        repository.deleteAllFaces()
    }

    // **Face Processing & Recognition**
    fun processImage(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val scaledBitmap = withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(bitmap, 112, 112, true)
                }

                val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)
                val outputArray = Array(1) { FloatArray(192) }

                withContext(inferenceScope.coroutineContext) {
                    tfLite?.run {
                        val inputs = arrayOf<Any>(byteBuffer)
                        val outputs = mutableMapOf<Int, Any>()
                        outputs[0] = outputArray
                        runForMultipleInputsOutputs(inputs, outputs)
                    }
                }

                val normalizedEmbeddings = normalizeEmbeddings(outputArray[0])

                getAllFaces { storedFaces ->
                    val matches = storedFaces.map { face ->
                        val storedEmbeddings = face.embeddings.toFloatArray() ?: return@map null
                        val normalizedStored = normalizeEmbeddings(storedEmbeddings)
                        val distance = calculateCosineSimilarity(normalizedEmbeddings, normalizedStored)
                        Pair(face.name, distance)
                    }.filterNotNull()

                    val bestMatch = matches.maxByOrNull { it.second }
                    _recognizedName.value = if (bestMatch?.second ?: 0f > SIMILARITY_THRESHOLD) ({
                        bestMatch?.first
                    }).toString() else "Unknown"
                }
            } catch (e: Exception) {
                Log.e("FaceRecognition", "Error processing image", e)
            }
        }
    }

    private fun normalizeEmbeddings(embeddings: FloatArray): FloatArray {
        val magnitude = sqrt(embeddings.map { it * it }.sum())
        return embeddings.map { it / magnitude }.toFloatArray()
    }

    private fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        val dotProduct = emb1.zip(emb2).map { (a, b) -> a * b }.sum()
        return dotProduct // vectors are already normalized
    }

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.7f
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(112 * 112)
        bitmap.getPixels(intValues, 0, 112, 0, 0, 112, 112)

        for (i in 0 until 112 * 112) {
            val value = intValues[i]
            byteBuffer.putFloat(((value shr 16 and 0xFF) - 128f) / 128f)
            byteBuffer.putFloat(((value shr 8 and 0xFF) - 128f) / 128f)
            byteBuffer.putFloat(((value and 0xFF) - 128f) / 128f)
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    fun extractEmbeddings(bitmap: Bitmap): FloatArray {
        val imgData = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4)
        imgData.order(ByteOrder.nativeOrder())

        val intValues = IntArray(112 * 112)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until 112) {
            for (j in 0 until 112) {
                val pixelValue = intValues[pixel++]
                imgData.putFloat((((pixelValue shr 16) and 0xFF) - 128.0f) / 128.0f)
                imgData.putFloat((((pixelValue shr 8) and 0xFF) - 128.0f) / 128.0f)
                imgData.putFloat(((pixelValue and 0xFF) - 128.0f) / 128.0f)
            }
        }

        val embeddings = Array(1) { FloatArray(192) }
        val outputMap = mapOf(0 to embeddings)
        tfLite?.runForMultipleInputsOutputs(arrayOf(imgData), outputMap)
        return embeddings[0]
    }

    private fun findNearestMatch(embeddings: FloatArray, storedFaces: List<StoredFace>): String? {
        var bestMatch: Pair<String, Float>? = null

        for (face in storedFaces) {
            val storedEmbeddings = face.embeddings.toFloatArray() ?: continue
            val distance = calculateEuclideanDistance(embeddings, storedEmbeddings)

            if (bestMatch == null || distance < bestMatch.second) {
                bestMatch = Pair(face.name, distance)
            }
        }

        return bestMatch?.takeIf { it.second < 5.0 }?.first
    }

    private fun calculateEuclideanDistance(emb1: FloatArray, emb2: FloatArray): Float {
        return kotlin.math.sqrt(
            emb1.zip(emb2).map { (a, b) -> (a - b) * (a - b) }.sum()
        )
    }

    override fun onCleared() {
        super.onCleared()
        inferenceThread.quitSafely()
        tfLite?.close()
        gpuDelegate?.close()
    }
}