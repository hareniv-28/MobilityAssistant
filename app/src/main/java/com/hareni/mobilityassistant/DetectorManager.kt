package com.hareni.mobilityassistant

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.vision.detector.ObjectDetector.ObjectDetectorOptions

data class DetectionResultRaw(val box: android.graphics.RectF, val label: String, val score: Float)

class DetectorManager(private val context: Context) {
    private var objectDetector: ObjectDetector? = null
    private var scoreThreshold = 0.35f

    fun initDetector(threshold: Float = 0.35f) {
        try {
            scoreThreshold = threshold
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(scoreThreshold)
                .setMaxResults(5)
                .build()
            objectDetector = ObjectDetector.createFromFileAndOptions(context, "detect.tflite", options)
            Log.i("DetectorManager", "initDetector OK (thr=$scoreThreshold)")
        } catch (e: Exception) {
            Log.e("DetectorManager", "initDetector failed: ${e.message}")
            objectDetector = null
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResultRaw> {
        val detector = objectDetector ?: return emptyList()
        return try {
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results = detector.detect(tensorImage)
            val out = mutableListOf<DetectionResultRaw>()
            for (r in results) {
                val cat = r.categories.firstOrNull() ?: continue
                val label = cat.label ?: continue
                val score = cat.score
                val lower = label.lowercase()
                if (lower !in listOf("person", "bicycle", "motorcycle")) continue
                out.add(DetectionResultRaw(r.boundingBox, label, score))
            }
            out
        } catch (e: Exception) {
            Log.e("DetectorManager", "detect failed: ${e.message}")
            emptyList()
        }
    }

    fun close() {
        // nothing to close for TaskLib, placeholder if needed
    }
}
