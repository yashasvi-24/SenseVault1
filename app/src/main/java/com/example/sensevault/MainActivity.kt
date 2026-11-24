package com.example.sensevault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.sensevault.sensors.SensorMonitor
import com.example.sensevault.ui.AppRoot

class MainActivity : ComponentActivity() {
    private lateinit var sensorMonitor: SensorMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorMonitor = SensorMonitor(applicationContext)
        setContent {
            AppRoot(sensorMonitor)
        }
    }
}
