package com.hareni.mobilityassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hareni.mobilityassistant.databinding.ActivityMainBinding
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Executors
    private lateinit var analysisExecutor: ExecutorService

    // Detector & alerts
    private lateinit var detectorManager: DetectorManager
    private var distanceScale = 1.0f
    private var vibrationStrength = 150
    private var audioMode = "both"

    // Prefs / broadcast
    private val PREFS_NAME = "mobility_prefs"
    private val SETTINGS_UPDATED = "com.hareni.settings.UPDATED"
    private lateinit var settingsReceiver: android.content.BroadcastReceiver

    // timing
    private var lastFrameTsMs = 0L
    private var framesSeen = 0
    private var fpsAvg = 0.0

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            // permission denied UI fallback
            Log.w("MainActivity", "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // prepare executor
        analysisExecutor = Executors.newSingleThreadExecutor()

        // load preferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scoreThreshold = prefs.getFloat("scoreThreshold", 0.35f)
        distanceScale = prefs.getFloat("distanceScale", 1.0f)
        vibrationStrength = prefs.getInt("vibrationStrength", 150)
        audioMode = prefs.getString("audioMode", "both") ?: "both"

        // init detector + alert engine
        detectorManager = DetectorManager(this)
        detectorManager.initDetector(scoreThreshold)

        AlertEngine.init(this)

        // prepare broadcast receiver to reload settings live
        settingsReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val newThr = p.getFloat("scoreThreshold", 0.35f)
                detectorManager.initDetector(newThr)
                distanceScale = p.getFloat("distanceScale", 1.0f)
                vibrationStrength = p.getInt("vibrationStrength", 150)
                audioMode = p.getString("audioMode", "both") ?: "both"
                Log.i("MainActivity", "Settings updated: thr=$newThr dist=$distanceScale vib=$vibrationStrength mode=$audioMode")
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, IntentFilter(SETTINGS_UPDATED))

        // check permission and launch camera
        checkPermissionAndLaunch()

        // Assist big button behaviour (toggle)
        binding.btnAssistBig.setOnClickListener {
            // simple toggle example - you can expand this to start/stop service
            val isEnabled = binding.btnAssistBig.text.toString().contains("ON", ignoreCase = true)
            if (isEnabled) {
                binding.btnAssistBig.text = "Assist OFF"
            } else {
                binding.btnAssistBig.text = "Assist ON"
            }
        }
    }

    private fun checkPermissionAndLaunch() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // Analysis
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Log.e("MainActivity", "bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        // TIMING → FPS update
        val now = SystemClock.uptimeMillis()
        if (lastFrameTsMs != 0L) {
            val dt = now - lastFrameTsMs
            val fps = if (dt > 0) 1000.0 / dt else 0.0
            framesSeen++
            fpsAvg += (fps - fpsAvg) / framesSeen.coerceAtLeast(1)
            val info = "FPS: ${fpsAvg.roundToInt()}  •  ${imageProxy.width}x${imageProxy.height}  •  rot=${imageProxy.imageInfo.rotationDegrees}°"
            runOnUiThread { binding.tvDebug.text = info }
        } else {
            runOnUiThread { binding.tvDebug.text = "Analyzing…" }
        }
        lastFrameTsMs = now

        try {
            // 1) Convert camera frame to upright bitmap
            val bitmap = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            val oriented = if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap

            // 2) Resize short side for speed
            val shortSide = 320
            val inputBitmap = scaleBitmapToShortSide(oriented, shortSide)

            // 3) Detection
            val rawResults = detectorManager.detect(inputBitmap) // List<DetectionResultRaw>

            // 4) Map to overlay coordinates and update UI
            val overlayResults = mutableListOf<com.hareni.mobilityassistant.DetectionResult>()
            for (r in rawResults) {
                val mapped = mapRectFromImageToView(
                    r.box,
                    inputBitmap.width,
                    inputBitmap.height,
                    binding.previewView.width,
                    binding.previewView.height
                )
                overlayResults.add(com.hareni.mobilityassistant.DetectionResult(mapped, r.label, r.score.toFloat()))
            }

            runOnUiThread {
                binding.overlay.setResults(overlayResults)
                binding.tvDebug.text = "Detections: ${overlayResults.size}"
            }

            // 5) Pick a top detection (simple heuristics) and alert
            if (overlayResults.isNotEmpty()) {
                val top = overlayResults.maxByOrNull { it.score }!!
                val centerX = (top.rect.left + top.rect.right) / 2f
                val pan = ((centerX / binding.previewView.width) - 0.5f) * 2f
                val area = (top.rect.width() * top.rect.height())
                val urgency = when {
                    area > 20000f -> 2
                    area > 8000f -> 1
                    else -> 0
                }
                AlertEngine.alert(this, top.label, audioMode, pan.coerceIn(-1f, 1f), urgency, vibrationStrength)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Analyzer error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
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

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    // ------------------- Helpers: rotate & scale -------------------
    private fun rotateBitmap(src: Bitmap, angle: Int): Bitmap {
        if (angle == 0) return src
        val matrix = Matrix().apply { postRotate(angle.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun scaleBitmapToShortSide(bitmap: Bitmap, shortSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val minSide = kotlin.math.min(w, h)
        if (minSide == shortSide) return bitmap
        val scale = shortSide.toFloat() / minSide.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    private fun mapRectFromImageToView(
        rect: RectF,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): RectF {
        if (viewWidth == 0 || viewHeight == 0) return rect
        val scale = kotlin.math.min(viewWidth.toFloat() / imageWidth.toFloat(), viewHeight.toFloat() / imageHeight.toFloat())
        val dispW = imageWidth * scale
        val dispH = imageHeight * scale
        val offsetX = (viewWidth - dispW) / 2f
        val offsetY = (viewHeight - dispH) / 2f
        return RectF(
            rect.left * scale + offsetX,
            rect.top * scale + offsetY,
            rect.right * scale + offsetX,
            rect.bottom * scale + offsetY
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (!analysisExecutor.isShutdown) analysisExecutor.shutdown()
        } catch (_: Exception) {}
        try {
            AlertEngine.shutdown()
        } catch (_: Exception) {}
        try {
            detectorManager.close()
        } catch (_: Exception) {}
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {}
    }
}
