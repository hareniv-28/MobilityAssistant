package com.hareni.mobilityassistant

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*

object AlertEngine {
    private var tts: TextToSpeech? = null
    private var soundPool: SoundPool? = null
    private var beepId: Int = 0
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        // init SoundPool
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setAudioAttributes(attrs).setMaxStreams(2).build()
        try {
            val resId = context.resources.getIdentifier("beep_short", "raw", context.packageName)
            if (resId != 0) beepId = soundPool?.load(context, resId, 1) ?: 0
        } catch (e: Exception) {
            Log.w("AlertEngine", "beep load failed: ${e.message}")
        }

        // init TTS
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en")
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        soundPool?.release()
        tts = null
        soundPool = null
        initialized = false
    }

    private fun playBeep(directionPan: Float, volume: Float = 1.0f) {
        // directionPan: -1.0 (left) .. 0 .. +1.0 (right)
        val sp = soundPool ?: return
        val id = beepId
        if (id == 0) return
        // convert pan to left/right volumes
        val left = if (directionPan <= 0f) 1.0f else 1.0f - directionPan
        val right = if (directionPan >= 0f) 1.0f else 1.0f + directionPan
        sp.play(id, left * volume, right * volume, 1, 0, 1.0f)
    }

    private fun speak(context: Context, text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "alert_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.w("AlertEngine", "TTS speak failed: ${e.message}")
        }
    }

    private fun vibrate(context: Context, pattern: LongArray, amplitude: Int = 150) {
        try {
            val vib = context.getSystemService(Vibrator::class.java)
            if (vib != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    vib.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.w("AlertEngine", "vibrate failed: ${e.message}")
        }
    }

    /**
     * Public unified alert API.
     * audioMode: "both" | "tts" | "beep"
     * pan: -1..1 where -1 is left, 1 is right
     */
    fun alert(
        context: Context,
        label: String,
        audioMode: String,
        pan: Float,
        urgency: Int,
        vibrationStrength: Int
    ) {
        init(context) // safe init

        val spoken = "${label.capitalize(Locale.getDefault())} ahead"
        // choose beep volume by urgency (0..2)
        val beepVolume = 0.5f + 0.25f * urgency

        when (audioMode.lowercase(Locale.getDefault())) {
            "tts" -> speak(context, spoken)
            "beep" -> playBeep(pan, beepVolume)
            else -> { // both
                playBeep(pan, beepVolume)
                speak(context, spoken)
            }
        }

        // vibration pattern: short pulses scaled by vibrationStrength
        val base = 60L
        val pattern = longArrayOf(0L, base, base, base) // simple pattern
        vibrate(context, pattern, vibrationStrength)
    }
}
