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

class MainActivity : AppCompatActivity() {

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

            analysis.setAnalyzer(cameraExecutor) { image: ImageProxy ->
                onFrame(image)
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

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
