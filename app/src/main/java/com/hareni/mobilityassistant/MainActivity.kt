package com.hareni.mobilityassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hareni.mobilityassistant.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream


class MainActivity : AppCompatActivity() {

    // ---------- Model & overlay references ----------
    private lateinit var overlay: com.hareni.mobilityassistant.DetectionOverlay
    private lateinit var tflite: org.tensorflow.lite.Interpreter
    private lateinit var labels: List<String>

    // Executor for analysis (single background thread)
    private val analysisExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()



    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    // simple FPS state
    private var lastFrameTsMs: Long = 0L
    private var fpsAvg: Double = 0.0
    private var framesSeen: Int = 0

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else binding.previewView.alpha = 0.3f
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // ---- hook overlay from XML ----
        overlay = findViewById(R.id.overlay)

    // ---- load labels and model (assets) ----
        try {
            labels = loadLabels() // loads labelmap.txt from assets, if present
        } catch (e: Exception) {
            labels = emptyList()
            android.util.Log.w("MobilityAssistant", "Labels not loaded: ${e.message}")
        }

        try {
            tflite = loadModel() // loads detect.tflite from assets
        } catch (e: Exception) {
            android.util.Log.e("MobilityAssistant", "Model load failed: ${e.message}", e)
        }
        // one background thread for analysis
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkPermissionAndLaunch()
    }

    private fun checkPermissionAndLaunch() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            // PREVIEW use case (UI)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // ANALYSIS use case (frames to CPU)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // leave default YUV_420_888; fast for camera
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                // offload work to background thread executor (analysisExecutor ensures it)
                try {
                    // Convert frame -> Bitmap
                    val bitmap = imageProxyToBitmap(imageProxy)

                    // Run detection only if model is loaded
                    if (::tflite.isInitialized) {
                        // run inference and get overlay-ready results
                        val results = runObjectDetection(bitmap)
                        // post results to UI
                        runOnUiThread {
                            overlay.setResults(results)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MobilityAssistant", "Analyzer error: ${e.message}", e)
                } finally {
                    imageProxy.close() // IMPORTANT: always close or CameraX will stop delivering frames
                }
            }


            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Log.e("MobilityAssistant", "bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: ImageProxy) {
        // TIMING → FPS
        val now = SystemClock.uptimeMillis()
        if (lastFrameTsMs != 0L) {
            val dt = now - lastFrameTsMs // ms between frames
            val fps = if (dt > 0) 1000.0 / dt else 0.0
            // running average to smooth display
            framesSeen++
            fpsAvg += (fps - fpsAvg) / framesSeen.coerceAtLeast(1)
            val info = "FPS: ${fpsAvg.roundToInt()}  •  ${image.width}x${image.height}  •  rot=${image.imageInfo.rotationDegrees}°"
            runOnUiThread { binding.tvDebug.text = info }
        } else {
            runOnUiThread { binding.tvDebug.text = "Analyzing…" }
        }
        lastFrameTsMs = now

        // IMPORTANT: always close the image to allow the next one
        image.close()
    }

    // ---------- Model and labels loaders ----------
    private fun loadModel(): org.tensorflow.lite.Interpreter {
        // uses FileUtil to memory-map the .tflite in assets (efficient)
        val modelBuffer = org.tensorflow.lite.support.common.FileUtil.loadMappedFile(this, "detect.tflite")
        val options = org.tensorflow.lite.Interpreter.Options()
        return org.tensorflow.lite.Interpreter(modelBuffer, options)
    }

    private fun loadLabels(): List<String> {
        // attempts to read a labelmap.txt in assets (one label per line)
        return org.tensorflow.lite.support.common.FileUtil.loadLabels(this, "labelmap.txt")
    }



    // ------------------- Helper: convert ImageProxy -> Bitmap -------------------
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    // ------------------- Helper: run object detection (stub / safe) -------------------
    /**
     * Temporary safe implementation.
     * Returns an empty list so overlay won't crash while we wire the real model parsing.
     * We'll replace this with proper TFLite parsing or Task Library detection next.
     */
    // ---------- Detection run (simple, Task-Lite raw parsing) ----------
    private fun runObjectDetection(bitmap: Bitmap): List<com.hareni.mobilityassistant.DetectionResult> {
        // resize model input (common SSD-MobileNet expects 300x300)
        val inputSize = 300
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val tensorImage = org.tensorflow.lite.support.image.TensorImage.fromBitmap(resized)

        // NOTE: exact output tensor shape depends on model. This is a safe placeholder path
        // because Task Library parsing is more robust. For now we will show a placeholder box
        // so overlay pipeline can be tested. Later we'll parse real output from your chosen model.
        val w = overlay.width.takeIf { it > 0 } ?: binding.previewView.width
        val h = overlay.height.takeIf { it > 0 } ?: binding.previewView.height

        // temporary: produce no boxes if model not usable; later we will parse real outputs
        return emptyList()
    }



    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
