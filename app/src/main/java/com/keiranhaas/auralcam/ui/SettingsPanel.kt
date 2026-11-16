package com.keiranhaas.auralcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keiranhaas.auralcam.camera.CameraController
import com.keiranhaas.auralcam.camera.CameraState
import com.keiranhaas.auralcam.camera.CaptureFormat
import com.keiranhaas.auralcam.camera.ProcessingProfile
import kotlin.math.roundToInt

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun SettingsPanel(
    state: CameraState,
    ui: UiOptions,
    onUi: (UiOptions) -> Unit,
    controller: CameraController,
    onClose: () -> Unit
) {
    val caps = state.active
    val s = state.settings
    val raw = caps?.raw == true

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .pointerInput(Unit) { detectTapGestures { } }   // keeps touches from reaching the preview below
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Pill("DONE", selected = true, onClick = onClose)
        }

        Section("CAPTURE FORMAT") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((f, label) in listOf(
                    CaptureFormat.JPEG to "JPEG",
                    CaptureFormat.DNG to "DNG",
                    CaptureFormat.DNG_JPEG to "DNG + JPEG"
                )) {
                    Pill(label, selected = s.format == f, enabled = f == CaptureFormat.JPEG || raw) {
                        controller.update { it.copy(format = f) }
                    }
                }
            }
            if (!raw) HintText("This lens does not offer RAW.")
            HintText("Low-light mode always writes a merged DNG.")
        }

        Section("PROCESSING") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("MINIMAL", selected = s.processing == ProcessingProfile.MINIMAL) {
                    controller.update { it.copy(processing = ProcessingProfile.MINIMAL) }
                }
                Pill("STANDARD", selected = s.processing == ProcessingProfile.STANDARD) {
                    controller.update { it.copy(processing = ProcessingProfile.STANDARD) }
                }
            }
            HintText(
                "Minimal asks the camera for light noise reduction, no edge sharpening and a plain tone curve on JPEGs, " +
                    "where the device allows it. DNG files are never sharpened or tone-mapped. The phone's own pipeline can't be switched off completely."
            )
        }

        Section("JPEG QUALITY") {
            SliderRow("${s.jpegQuality}") {
                LinearSlider(s.jpegQuality.toFloat(), 70f, 100f, true, Modifier.weight(1f), step = 1f) { v ->
                    controller.update { it.copy(jpegQuality = v.roundToInt()) }
                }
            }
        }

        Section("STABILISATION") {
            Pill(if (s.ois) "OPTICAL STABILISATION ON" else "OPTICAL STABILISATION OFF",
                selected = s.ois, enabled = caps?.hasOis == true) {
                controller.update { it.copy(ois = !it.ois) }
            }
            HintText("Turn it off on a tripod for the sharpest long exposures.")
        }

        Section("BURST FRAMES") {
            SliderRow("${s.burstCount}") {
                LinearSlider(s.burstCount.toFloat(), 2f, 30f, true, Modifier.weight(1f), step = 1f) { v ->
                    controller.update { it.copy(burstCount = v.roundToInt()) }
                }
            }
        }

        Section("LOW-LIGHT FRAMES") {
            SliderRow("${s.lowLightFrames}") {
                LinearSlider(
                    s.lowLightFrames.toFloat(), 3f, 16f, raw && state.lowLightAvailable,
                    Modifier.weight(1f), step = 1f
                ) { v -> controller.update { it.copy(lowLightFrames = v.roundToInt()) } }
            }
            HintText("Frames share one exposure, are aligned in tiles and merged. Use a tripod or steady hands.")
        }

        Section("ZEBRA LEVEL") {
            SliderRow("${ui.zebraLevel}") {
                LinearSlider(ui.zebraLevel.toFloat(), 200f, 255f, true, Modifier.weight(1f), step = 1f) { v ->
                    onUi(ui.copy(zebraLevel = v.roundToInt()))
                }
            }
            HintText("Stripes mark pixels at or above this brightness (0 to 255).")
        }

        Section("PEAKING THRESHOLD") {
            SliderRow("${ui.peakThreshold}") {
                LinearSlider(ui.peakThreshold.toFloat(), 20f, 150f, true, Modifier.weight(1f), step = 1f) { v ->
                    onUi(ui.copy(peakThreshold = v.roundToInt()))
                }
            }
            HintText("Lower = more edges highlighted. Peaking runs at preview resolution, so it shows coarse focus.")
        }

        BracketSection(state, controller)
        TimerSection(state, controller, onClose)
        AdvancedProcessingSection(state, controller)

        Section("REMOTES") {
            HintText(
                "Shutter: volume up or down, camera key, Enter, Space, D-pad centre, and headset or Bluetooth media buttons. " +
                    "Bluetooth selfie remotes and USB HID remotes work as long as they send one of these keys."
            )
        }

        if (caps != null) {
            Section("THIS LENS") {
                HintText(
                    "${caps.label}\n" +
                        "Manual sensor: ${if (caps.manualSensor) "yes" else "no"}   RAW: ${if (caps.raw) "yes" else "no"}\n" +
                        "Exact Kelvin white balance: ${if (caps.exactKelvin) "yes" else "preset fallback"}\n" +
                        "Autofocus: ${if (caps.hasAutofocus) "yes" else "no"}   OIS: ${if (caps.hasOis) "yes" else "no"}\n" +
                        "AE / AF regions: ${caps.maxAeRegions} / ${caps.maxAfRegions}"
                )
            }
        }
    }
}