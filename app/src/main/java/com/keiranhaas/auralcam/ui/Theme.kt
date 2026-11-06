package com.keiranhaas.auralcam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFC107)
val GoodGreen = Color(0xFF39FF6A)
val WarnRed = Color(0xFFFF5252)
val Scrim = Color(0x99000000)

@Composable
fun AuralCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Color.Black,
            surface = Color(0xFF121212),
            onSurface = Color.White
        ),
        content = content
    )
}