package com.hareni.mobilityassistant

import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MobilityService : LifecycleService() {

    private val CHANNEL_ID = "mobility_service_channel"
    private val NOTIF_ID = 12345

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        // Start camera when service starts. Use main thread executor for camera provider.
        startCameraInService()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Mobility Service", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }
    private fun buildNotification(): Notification {
        val channelId = "mobility_service_channel"
        val channelName = "Mobility Service"

        // create channel for O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }

        val smallIconRes = try {
            // prefer your drawable, fallback to launcher
            if (resources.getIdentifier("ic_stat_name", "drawable", packageName) != 0) {
                R.drawable.ic_stat_name
            } else {
                R.mipmap.ic_launcher
            }
        } catch (e: Exception) {
            R.mipmap.ic_launcher
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mobility Assistant")
            .setContentText("Running in background")
            .setSmallIcon(smallIconRes)
            .setPriority(NotificationCompat.PRIORITY_LOW)     // <-- setPriority here
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }


    private fun startCameraInService() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // Provide an analyzer that calls the same processing logic.
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                // replicate minimal processing: convert, detect, alert
                // To avoid duplicating code, consider calling a helper singleton or move detection to a shared class
                image.close()
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                Log.e("MobilityService", "bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
