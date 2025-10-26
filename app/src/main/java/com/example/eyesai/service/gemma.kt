package com.example.eyesai.service

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton // Make it a singleton if you want one instance throughout the app
class GemmaService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val taskOptions = LlmInference.LlmInferenceOptions.builder()
        .setModelPath("/data/local/tmp/llm/gemma3-1b-int4.task") // Use your actual model path
        .setMaxTopK(64)
        // .setResultListener { partialResult, done -> /* Handle async results if needed */ }
        .build()

    // Initialize llmInference lazily
    private val llmInference: LlmInference by lazy {
        LlmInference.createFromOptions(context, taskOptions)
    }

    /**
     * Generates a response from the Gemma model for the given prompt.
     *
     * @param prompt The text input to the model.
     * @return The model's generated response, or null if an error occurs.
     */
    fun generateResponse(prompt: String): String? {
        return try {
            llmInference.generateResponse(prompt)
        } catch (e: Exception) {
            // Log the error or handle it appropriately
            android.util.Log.e("GemmaService", "Error generating response: ${e.message}", e)
            null
        }
    }

    // Optional: A method to close/release resources if needed when the service is no longer used.
    // LlmInference might have a close() method; check its documentation.
    // fun close() {
    //     if (::llmInference.isInitialized) {
    //         // llmInference.close() // If such a method exists
    //     }
    // }
}