package com.keiranhaas.auralcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keiranhaas.auralcam.camera.CameraController
import kotlin.math.roundToInt

@Composable
fun AnalysisToolsSection(ui: UiOptions, onUi: (UiOptions) -> Unit) {
    PanelSection("MONITORING") {
        SliderRow(if (ui.clipAlertPct <= 0f) "OFF" else formatPct(ui.clipAlertPct)) {
            LinearSlider(ui.clipAlertPct, 0f, 10f, true, Modifier.weight(1f), step = 0.5f) { v ->
                onUi(ui.copy(clipAlertPct = v))
            }
        }
        HintText("Clipping alert: a badge appears when this share of the frame is blown out. Shadows warn at five times this share. 0 turns it off.")

        SliderRow("${ui.magnifyFactor.roundToInt()}×") {
            LinearSlider(ui.magnifyFactor, 2f, 8f, true, Modifier.weight(1f), step = 1f) { v ->
                onUi(ui.copy(magnifyFactor = v))
            }
        }
        HintText("Focus magnifier: the MAG button in the toolbar enlarges the preview around your focus point (or the centre) so you can judge manual focus. Peaking stays visible.")
    }
}

@Composable
fun PresetSection(controller: CameraController) {
    PanelSection("PRESETS") {
        for (slot in 1..3) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SLOT $slot", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Pill("SAVE") { controller.savePreset(slot) }
                Pill("LOAD") { controller.loadPreset(slot) }
            }
        }
        HintText(
            "A preset stores exposure mode and values, white balance, focus mode, zoom, format, processing, drive mode, bracket, timer " +
                "and advanced processing. Focus and exposure points are not saved. Your last-used settings come back automatically on launch."
        )
    }
}