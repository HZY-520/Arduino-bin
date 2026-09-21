package com.arduinobin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arduinobin.ui.MainScreen
import com.arduinobin.ui.theme.ArduinoBinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArduinoBinTheme {
                MainScreen()
            }
        }
    }
}