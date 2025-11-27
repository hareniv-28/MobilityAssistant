package com.hareni.mobilityassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.support.image.TensorImage
import java.io.ByteArrayOutputStream
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.util.concurrent.Executors

class MobilityService : LifecycleService() {
    private val CHANNEL_ID = "mobility_service_channel"
    private val NOTIF_ID = 1111

    private lateinit var detectorManager: DetectorManager
    private var audioMode = "both"
    private var vibrationStrength = 150

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // prefs
        val prefs = getSharedPreferences("mobility_prefs", Context.MODE_PRIVATE)
        val scoreThreshold = prefs.getFloat("scoreThreshold", 0.35f)
        audioMode = prefs.getString("audioMode", "both") ?: "both"
        vibrationStrength = prefs.getInt("vibrationStrength", 150)

        detectorManager = DetectorManager(this)
        detectorManager.initDetector(scoreThreshold)

        AlertEngine.init(this)

        startCameraAnalysis()
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(channelId, "Mobility Service", NotificationManager.IMPORTANCE_LOW))
        }
        val icon = try { R.mipmap.ic_launcher } catch (e: Exception) { android.R.drawable.ic_dialog_info }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mobility Assistant")
            .setContentText("Running background assist")
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Mobility Service", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun startCameraAnalysis() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val bitmap = imageProxyToBitmap(imageProxy)
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val oriented = if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap
                        val shortSide = 320
                        val inputBitmap = scaleBitmapToShortSide(oriented, shortSide)

                        val rawResults = detectorManager.detect(inputBitmap)
                        if (rawResults.isNotEmpty()) {
                            val top = rawResults.maxByOrNull { it.score }!!
                            // map center to pan (-1..1) using image width
                            val box = top.box
                            val centerX = (box.left + box.right) / 2f
                            val pan = ((centerX / inputBitmap.width) - 0.5f) * 2f
                            val area = (box.width() * box.height())
                            val urgency = when {
                                area > 20000f -> 2
                                area > 8000f -> 1
                                else -> 0
                            }
                            // alert
                            AlertEngine.alert(this, top.label, audioMode, pan.coerceIn(-1f,1f), urgency, vibrationStrength)
                        }
                    } catch (e: Exception) {
                        Log.e("MobilityService", "analyzer error: ${e.message}", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                Log.e("MobilityService", "camera bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // --- Helpers (same as MainActivity; you can refactor to shared util file) ---
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

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        AlertEngine.shutdown()
        detectorManager.close()
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }
}

