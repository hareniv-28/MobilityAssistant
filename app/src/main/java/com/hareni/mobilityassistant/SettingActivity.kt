package com.hareni.mobilityassistant

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.SeekBar
import android.widget.Button

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("mobility_prefs", Context.MODE_PRIVATE)
        val seekThreshold: SeekBar = findViewById(R.id.seekThreshold)
        val seekDistance: SeekBar = findViewById(R.id.seekDistanceScale)
        val seekVibrate: SeekBar = findViewById(R.id.seekVibrate)
        val btnSave: Button = findViewById(R.id.btnSave)

        // Load existing
        val score = (prefs.getFloat("scoreThreshold", 0.35f) * 100).toInt()
        seekThreshold.progress = score
        seekDistance.progress = (prefs.getFloat("distanceScale", 1.0f) * 100).toInt()
        seekVibrate.progress = prefs.getInt("vibrationStrength", 100)

        btnSave.setOnClickListener {
            val editor = prefs.edit()
            editor.putFloat("scoreThreshold", seekThreshold.progress / 100f)
            editor.putFloat("distanceScale", seekDistance.progress / 100f)
            editor.putInt("vibrationStrength", seekVibrate.progress)
            editor.apply()
            finish()
        }
    }
}
