package com.keiranhaas.auralcam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.keiranhaas.auralcam.camera.CameraEnumerator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { DiagnosticsScreen() }
        }
    }
}

@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val lenses = remember {
        CameraEnumerator.enumerate(context.getSystemService(CameraManager::class.java))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("AuralCam: camera diagnostics", color = Color.White)
        Text("Camera permission: ${if (granted) "granted" else "not granted"}", color = Color.White)
        Text("Back lenses found: ${lenses.size}", color = Color.White)
        lenses.forEach { c ->
            Text(
                "\n${c.label}\n" +
                    "  focal ${c.focalLengthMm}mm, sensor orientation ${c.sensorOrientation}\n" +
                    "  manual sensor ${c.manualSensor}, manual post ${c.manualPostProcessing}, RAW ${c.raw}\n" +
                    "  ISO ${c.isoMin}..${c.isoMax}\n" +
                    "  shutter ${c.exposureMinNs / 1000}us .. ${c.exposureMaxNs / 1_000_000}ms\n" +
                    "  AF ${c.hasAutofocus}, min focus ${c.minFocusDiopters} diopters\n" +
                    "  EV ${c.evMin}..${c.evMax} step ${c.evStep}\n" +
                    "  zoom ${c.zoomMin}..${c.zoomMax}, OIS ${c.hasOis}\n" +
                    "  AE/AF regions ${c.maxAeRegions}/${c.maxAfRegions}, exact Kelvin WB ${c.exactKelvin}\n" +
                    "  physical ids ${c.physicalIds}",
                color = Color.White
            )
        }
    }
}