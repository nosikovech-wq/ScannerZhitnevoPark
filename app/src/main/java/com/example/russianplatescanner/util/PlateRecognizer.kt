package com.example.russianplatescanner.util

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class PlateRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): RecognitionResult {
        return recognize(InputImage.fromBitmap(bitmap, 0))
    }

    suspend fun recognize(image: InputImage): RecognitionResult {
        val visionText = recognizer.process(image).await()
        val rawText = visionText.text
        val found = readPlate(rawText)
        return RecognitionResult(
            number = found,
            rawText = rawText,
            confidence = if (found != null) 0.85f else 0f
        )
    }
}

data class RecognitionResult(
    val number: String?,
    val rawText: String,
    val confidence: Float
)
