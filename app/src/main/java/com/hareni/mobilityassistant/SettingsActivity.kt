package com.hareni.mobilityassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hareni.mobilityassistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // preference keys (keep in sync with MainActivity)
    companion object {
        const val PREFS_NAME = "mobility_prefs"
        const val KEY_SCORE_THRESHOLD = "scoreThreshold"
        const val KEY_DISTANCE_SCALE = "distanceScale"
        const val KEY_VIBRATION_STRENGTH = "vibrationStrength"
        const val KEY_AUDIO_MODE = "audioMode"
        const val SETTINGS_UPDATED_ACTION = "com.hareni.settings.UPDATED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAudioModeSpinner()
        loadSettingsIntoUi()

        binding.btnSave.setOnClickListener {
            saveSettings()
            finish()
        }

        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
            saveSettings()
        }
    }

    private fun setupAudioModeSpinner() {
        // simple options: both / tts / beep
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Both", "TTS only", "Beep only")
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAudioMode.adapter = adapter
    }

    private fun loadSettingsIntoUi() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val score = prefs.getFloat(KEY_SCORE_THRESHOLD, 0.35f)
        val distScale = prefs.getFloat(KEY_DISTANCE_SCALE, 1.0f)
        val vib = prefs.getInt(KEY_VIBRATION_STRENGTH, 150)
        val audioMode = prefs.getString(KEY_AUDIO_MODE, "both") ?: "both"

        // map to seekbars (example: score as 0..100)
        binding.seekScore.progress = (score * 100).toInt()
        binding.tvScoreValue.text = String.format("%.2f", score)

        binding.seekDistance.progress = (distScale * 100).toInt()
        binding.tvDistanceValue.text = String.format("%.2f", distScale)

        binding.seekVibration.progress = vib.coerceIn(0, 255)
        binding.tvVibrationValue.text = vib.toString()

        // audio spinner selection mapping
        when (audioMode.lowercase()) {
            "tts" -> binding.spinnerAudioMode.setSelection(1)
            "beep" -> binding.spinnerAudioMode.setSelection(2)
            else -> binding.spinnerAudioMode.setSelection(0)
        }

        // live update labels as the user moves the SeekBars
        binding.seekScore.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                binding.tvScoreValue.text = String.format("%.2f", v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                binding.tvDistanceValue.text = String.format("%.2f", v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekVibration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVibrationValue.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun saveSettings() {
        val prefsEditor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()

        val score = binding.seekScore.progress / 100f
        val distScale = binding.seekDistance.progress / 100f
        val vib = binding.seekVibration.progress
        val audioMode = when (binding.spinnerAudioMode.selectedItemPosition) {
            1 -> "tts"
            2 -> "beep"
            else -> "both"
        }

        prefsEditor.putFloat(KEY_SCORE_THRESHOLD, score)
        prefsEditor.putFloat(KEY_DISTANCE_SCALE, distScale)
        prefsEditor.putInt(KEY_VIBRATION_STRENGTH, vib)
        prefsEditor.putString(KEY_AUDIO_MODE, audioMode)

        prefsEditor.apply() // persist

        // notify activity/service that settings changed
        val intent = Intent(SETTINGS_UPDATED_ACTION)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun resetToDefaults() {
        binding.seekScore.progress = 35   // 0.35
        binding.seekDistance.progress = 100 // 1.0
        binding.seekVibration.progress = 150
        binding.spinnerAudioMode.setSelection(0)
    }
}
