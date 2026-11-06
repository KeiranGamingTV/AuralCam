package com.keiranhaas.auralcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionScreen(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AuralCam needs the camera", color = Color.White, fontSize = 20.sp)
        Text(
            "Camera access is the only permission AuralCam uses. Photos are saved to DCIM/AuralCam without any storage permission.",
            color = Color(0xCCFFFFFF), fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Pill("GRANT CAMERA ACCESS", selected = true, onClick = onRequest)
        Pill("OPEN APP SETTINGS", onClick = onOpenSettings)
    }
}