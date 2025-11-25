package com.hareni.mobilityassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.hareni.mobilityassistant.databinding.ActivityMainBinding
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // ---------- View + model ----------
    private lateinit var binding: ActivityMainBinding
    private lateinit var objectDetector: ObjectDetector
    private lateinit var cameraExecutor: ExecutorService

    // ---------- Day7 alert fields ----------
    private var lastAlertTime = 0L
    private val alertCooldownMs = 1500L
    private lateinit var vibrator: Vibrator
    private lateinit var tts: TextToSpeech

    // ---------- Day9 SoundPool ----------
    private lateinit var soundPool: SoundPool
    private var beepSoundId: Int = 0

    // ---------- Day8 context/tracking ----------
    private var previousDetections: List<com.hareni.mobilityassistant.DetectionResult> = emptyList()
    private var previousFrameTs: Long = 0L
    private val PERSON_REAL_HEIGHT_M = 1.7f
    private val BICYCLE_REAL_HEIGHT_M = 1.1f
    private val MOTORCYCLE_REAL_HEIGHT_M = 1.2f
    private var focalLengthPx = 0f

    // ---------- Misc state for fps / analyzer ----------
    private var isAnalyzing = false
    private var lastFrameTsMs = 0L
    private var framesSeen = 0
    private var fpsAvg = 0.0

    // accessibility toggle
    private var assistEnabled = true

    // SharedPrefs keys
    private val PREFS = "mobility_prefs"

    // Permission launcher
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else Log.w("MobilityAssistant", "Camera permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // init vibrator
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // init TTS
        tts = TextToSpeech(this) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.ENGLISH
            } else {
                Log.w("MobilityAssistant", "TTS init failed: status=$status")
            }
        }

        // init soundpool (Day9)
        initSoundPool()

        // initialize TaskLib detector (Day5) using prefs (Day10)
        initDetector()

        // create executor for analysis
        cameraExecutor = Executors.newSingleThreadExecutor()

        // UI wiring - big assist button (Day12)
        setupAssistButton()

        // request permission & start camera
        checkPermissionAndLaunch()
    }

    override fun onResume() {
        super.onResume()
        // If settings may have changed, re-init detector with updated prefs
        // (lightweight — reinit only if threshold changed in settings)
        // For simplicity we'll re-init every resume (safe for now)
        try {
            initDetector()
        } catch (e: Exception) {
            Log.w("MobilityAssistant", "Re-init detector failed: ${e.message}")
        }
    }

    // ------------- Initialization helpers ----------------

    private fun initSoundPool() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setAudioAttributes(attrs)
                .setMaxStreams(2)
                .build()

            // Attempt to load beep_short from res/raw/beep_short.mp3
            beepSoundId = try {
                resources.getIdentifier("beep_short", "raw", packageName).takeIf { it != 0 }?.let {
                    soundPool.load(this, it, 1)
                } ?: 0
            } catch (e: Exception) {
                Log.w("MobilityAssistant", "Beep load failed: ${e.message}")
                0
            }
        } catch (e: Exception) {
            Log.w("MobilityAssistant", "SoundPool init failed: ${e.message}")
            beepSoundId = 0
        }
    }

    private fun setupAssistButton() {
        // Make sure binding has btnAssistBig (Day12)
        try {
            binding.btnAssistBig.setOnClickListener {
                assistEnabled = !assistEnabled
                binding.btnAssistBig.text = if (assistEnabled) "Assist ON" else "Assist OFF"
            }
        } catch (e: Exception) {
            // ignore if layout not present
            Log.i("MobilityAssistant", "Assist button not found in layout")
        }
    }

    private fun initDetector() {
        try {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val scoreThreshold = prefs.getFloat("scoreThreshold", 0.35f)

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(5)
                .setScoreThreshold(scoreThreshold)
                .build()
            objectDetector = ObjectDetector.createFromFileAndOptions(this, "detect.tflite", options)
            Log.i("MobilityAssistant", "ObjectDetector initialized (scoreThreshold=$scoreThreshold)")
        } catch (e: Exception) {
            Log.e("MobilityAssistant", "initDetector failed: ${e.message}", e)
        }
    }

    private fun checkPermissionAndLaunch() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    // ------------- CameraX / Analyzer (Day6 + Day5 integration) --------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isAnalyzing) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                isAnalyzing = true

                try {
                    // timing info (FPS)
                    val now = SystemClock.uptimeMillis()
                    if (lastFrameTsMs != 0L) {
                        val dt = now - lastFrameTsMs
                        val fps = if (dt > 0) 1000.0 / dt else 0.0
                        framesSeen++
                        fpsAvg += (fps - fpsAvg) / framesSeen.coerceAtLeast(1)
                        val info = "FPS: ${fpsAvg.roundToInt()} • ${imageProxy.width}x${imageProxy.height} • rot=${imageProxy.imageInfo.rotationDegrees}°"
                        runOnUiThread { binding.tvDebug.text = info }
                    } else {
                        runOnUiThread { binding.tvDebug.text = "Analyzing…" }
                    }
                    lastFrameTsMs = now

                    // 1) Convert to bitmap + orient
                    val bitmap = imageProxyToBitmap(imageProxy)
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val oriented = if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap

                    // 2) Resize for speed
                    val shortSide = 320
                    val inputBitmap = scaleBitmapToShortSide(oriented, shortSide)

                    // 3) Run detection + map results
                    val overlayResults = processImageForDetection(inputBitmap)

                    // 4) Post to UI
                    runOnUiThread {
                        binding.overlay.setResults(overlayResults)
                        binding.tvDebug.text = "Detections: ${overlayResults.size}"
                    }
                } catch (e: Exception) {
                    Log.e("MobilityAssistant", "Analyzer error: ${e.message}", e)
                } finally {
                    isAnalyzing = false
                    imageProxy.close()
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

    // ------------- Image conversion helpers --------------

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

    // ------------- Detection pipeline (Day6) --------------

    private fun processImageForDetection(inputBitmap: Bitmap): List<com.hareni.mobilityassistant.DetectionResult> {
        val tensorImage = TensorImage.fromBitmap(inputBitmap)

        val results = try {
            if (::objectDetector.isInitialized) objectDetector.detect(tensorImage) else emptyList()
        } catch (e: Exception) {
            Log.e("MobilityAssistant", "Detection failed: ${e.message}", e)
            emptyList()
        }

        val overlayResults = mutableListOf<com.hareni.mobilityassistant.DetectionResult>()
        for (r in results) {
            val cats = r.categories
            if (cats.isEmpty()) continue
            val label = cats[0].label ?: continue
            val score = cats[0].score
            val lower = label.lowercase()
            if (lower !in listOf("person", "bicycle", "motorcycle")) continue

            val box = r.boundingBox // in inputBitmap coordinates (RectF)
            val mapped = mapRectFromImageToView(
                box,
                inputBitmap.width, inputBitmap.height,
                binding.previewView.width, binding.previewView.height
            )
            overlayResults.add(com.hareni.mobilityassistant.DetectionResult(mapped, label, score))
        }

        // Day 7: alerts (uses Day8 context)
        if (overlayResults.isNotEmpty()) {
            analyzeContext(overlayResults, inputBitmap.width, inputBitmap.height)
        }

        return overlayResults
    }

    // ------------- Day8 context helpers & data classes --------------

    private data class ContextInfo(
        val label: String,
        val score: Float,
        val box: RectF,
        val centerX: Float,
        val centerY: Float,
        val areaFrac: Float
    )

    private data class ItemCtx(
        val info: ContextInfo,
        val bucket: String,
        val distanceM: Float,
        val approaching: Boolean
    )

    private fun toContextInfo(
        d: com.hareni.mobilityassistant.DetectionResult,
        inputWidth: Int,
        inputHeight: Int
    ): ContextInfo {
        // DetectionResult has property `rect` (your class). We copy it into ContextInfo.box
        val b = d.rect
        val centerX = (b.left + b.right) / 2f
        val centerY = (b.top + b.bottom) / 2f
        val area = (b.right - b.left) * (b.bottom - b.top)
        val areaFrac = area / (inputWidth.toFloat() * inputHeight.toFloat()).coerceAtLeast(1f)
        return ContextInfo(d.label.lowercase(), d.score, b, centerX, centerY, areaFrac)
    }

    private fun horizontalBucket(centerX: Float, inputWidth: Int): String {
        val nx = centerX / inputWidth.toFloat()
        return when {
            nx < 0.33f -> "left"
            nx > 0.66f -> "right"
            else -> "center"
        }
    }

    private fun estimateDistanceMeters(label: String, boxHeightPx: Float, inputHeightPx: Int): Float {
        if (boxHeightPx <= 0f) return Float.POSITIVE_INFINITY

        val realHeight = when {
            "person" in label -> PERSON_REAL_HEIGHT_M
            "bicycle" in label -> BICYCLE_REAL_HEIGHT_M
            "motorcycle" in label -> MOTORCYCLE_REAL_HEIGHT_M
            else -> PERSON_REAL_HEIGHT_M
        }

        if (focalLengthPx > 0f) {
            return (realHeight * focalLengthPx) / boxHeightPx
        }

        val frac = (boxHeightPx / inputHeightPx.toFloat()).coerceAtLeast(0.01f)
        val scaleFactor = 1.8f
        return scaleFactor / frac
    }

    private fun matchDetections(
        prev: List<ContextInfo>,
        curr: List<ContextInfo>
    ): List<Pair<ContextInfo, ContextInfo?>> {
        val matches = mutableListOf<Pair<ContextInfo, ContextInfo?>>()
        for (c in curr) {
            var best: ContextInfo? = null
            var bestDist = Float.MAX_VALUE
            for (p in prev) {
                if (p.label != c.label) continue
                val dx = p.centerX - c.centerX
                val dy = p.centerY - c.centerY
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    best = p
                }
            }
            matches.add(Pair(c, best))
        }
        return matches
    }

    private fun analyzeContext(
        overlayResults: List<com.hareni.mobilityassistant.DetectionResult>,
        inputWidth: Int,
        inputHeight: Int
    ) {
        val now = System.currentTimeMillis()

        val curr = overlayResults.map { toContextInfo(it, inputWidth, inputHeight) }
        val prev = previousDetections.map { toContextInfo(it, inputWidth, inputHeight) }

        val matches = matchDetections(prev, curr)

        val items = mutableListOf<ItemCtx>()

        for ((currInfo, prevInfo) in matches) {
            val boxHeightPx = currInfo.box.height()
            val dist = estimateDistanceMeters(currInfo.label, boxHeightPx, inputHeight)

            val bucket = when {
                dist < 4f -> "near"
                dist < 10f -> "medium"
                else -> "far"
            }

            val approaching = if (prevInfo != null) {
                val prevArea = prevInfo.box.width() * prevInfo.box.height()
                val currArea = currInfo.box.width() * currInfo.box.height()
                val areaIncrease = (currArea - prevArea) / prevArea.coerceAtLeast(1f)
                areaIncrease > 0.10f
            } else false

            items.add(ItemCtx(currInfo, bucket, dist, approaching))
        }

        previousDetections = overlayResults
        previousFrameTs = now

        if (items.isNotEmpty()) handleContextAlert(items)
    }

    // ------------- Day8/9: context -> alert mapping (with directional beep) --------------

    private fun handleContextAlert(items: List<ItemCtx>) {
        if (!assistEnabled) return

        val prioritized = items.sortedWith(compareBy({ it.distanceM }, { if (it.approaching) 0 else 1 }))
        val top = prioritized.firstOrNull() ?: return

        val label = top.info.label
        val distBucket = top.bucket
        val approaching = top.approaching
        val horiz = horizontalBucket(top.info.centerX, binding.previewView.width)

        when {
            approaching && distBucket == "near" -> {
                vibratePattern(label)
                playDirectionalBeep(horiz)
                speak("${label.replaceFirstChar { it.uppercase() }} approaching ahead. Take care.")
            }
            distBucket == "near" -> {
                vibratePattern(label)
                playDirectionalBeep(horiz)
                speak("${label.replaceFirstChar { it.uppercase() }} nearby ahead.")
            }
            distBucket == "medium" -> {
                vibratePattern(label)
                playDirectionalBeep(horiz)
                speak("${label.replaceFirstChar { it.uppercase() }} ahead on your $horiz.")
            }
            else -> {
                if (top.info.areaFrac > 0.02f) {
                    vibratePattern(label)
                    playDirectionalBeep(horiz)
                    speak("${label.replaceFirstChar { it.uppercase() }} ahead on your $horiz.")
                }
            }
        }
    }

    // ------------- Day9: directional beep helper --------------

    private fun playDirectionalBeep(direction: String) {
        // direction = "left" | "center" | "right"
        if (beepSoundId == 0) {
            // fallback: short TTS ping (silence or beep depending on TTS)
            if (::tts.isInitialized) {
                // speak a short tone-like word (quiet)
                tts.speak("", TextToSpeech.QUEUE_FLUSH, null, "beep")
            }
            return
        }
        val (leftVolume, rightVolume) = when (direction) {
            "left" -> Pair(1.0f, 0.2f)
            "right" -> Pair(0.2f, 1.0f)
            else -> Pair(0.85f, 0.85f)
        }
        try {
            soundPool.play(beepSoundId, leftVolume, rightVolume, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w("MobilityAssistant", "playDirectionalBeep failed: ${e.message}")
        }
    }

    // ------------- Day12: haptic patterns (replace earlier vibrate helpers) --------------

    private fun vibratePattern(type: String) {
        if (!::vibrator.isInitialized) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = when (type) {
                    "person" -> longArrayOf(0, 100, 80, 100) // double short pulses
                    "motorcycle" -> longArrayOf(0, 300) // long vibration
                    "bicycle" -> longArrayOf(0, 120) // short
                    else -> longArrayOf(0, 80)
                }
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
        } catch (e: Exception) {
            Log.w("MobilityAssistant", "vibratePattern failed: ${e.message}")
        }
    }

    // ------------- TTS helper --------------

    private fun speak(text: String, urgent: Boolean = true) {
        if (!::tts.isInitialized) return
        try {
            val queueMode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(text, queueMode, null, null)
        } catch (e: Exception) {
            Log.w("MobilityAssistant", "TTS speak failed: ${e.message}")
        }
    }

    // ------------- MobilityService control (Day11) --------------

    private fun startMobilityService() {
        try {
            val intent = Intent(this, MobilityService::class.java)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w("MobilityAssistant", "startMobilityService failed: ${e.message}")
        }
    }

    private fun stopMobilityService() {
        try {
            stopService(Intent(this, MobilityService::class.java))
        } catch (e: Exception) {
            Log.w("MobilityAssistant", "stopMobilityService failed: ${e.message}")
        }
    }

    // ------------- Lifecycle cleanup --------------

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        if (::tts.isInitialized) tts.shutdown()
        try {
            if (::soundPool.isInitialized) soundPool.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}
