package com.arduinobin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArduinoGreen = Color(0xFF0D986A)

private val LightColors = lightColorScheme(
    primary = ArduinoGreen,
    onPrimary = Color.White,
    secondary = Color(0xFF1B6E8C),
    background = Color(0xFFF6F7F9),
    surface = Color.White,
)

@Composable
fun ArduinoBinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}