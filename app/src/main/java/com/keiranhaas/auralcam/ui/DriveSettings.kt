package com.keiranhaas.auralcam.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keiranhaas.auralcam.camera.CameraController
import com.keiranhaas.auralcam.camera.CameraState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PanelSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun BracketSection(state: CameraState, controller: CameraController) {
    val s = state.settings
    val manual = state.active?.manualSensor == true
    PanelSection("EXPOSURE BRACKETING") {
        SliderRow("${s.bracketFrames} frames") {
            LinearSlider(s.bracketFrames.toFloat(), 3f, 9f, manual, Modifier.weight(1f), step = 1f) { v ->
                controller.update { it.copy(bracketFrames = v.roundToInt()) }
            }
        }
        SliderRow("±${String.format(Locale.US, "%.1f", s.bracketStepEv)} EV") {
            LinearSlider(s.bracketStepEv, 0.5f, 3f, manual, Modifier.weight(1f), step = 0.5f) { v ->
                controller.update { it.copy(bracketStepEv = v) }
            }
        }
        HintText(
            if (manual) "Choose BRACKET with the drive button next to the shutter. Frames are centred on the current " +
                "exposure; the shutter moves first, then ISO. Files are named …_EV-1.0, …_EV+0.0, …"
            else "This lens has no manual sensor control, so bracketing is unavailable."
        )
    }
}

@Composable
fun TimerSection(state: CameraState, controller: CameraController, onClose: () -> Unit) {
    val s = state.settings
    PanelSection("SELF-TIMER AND INTERVALOMETER") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 2, 5, 10).forEach { t ->
                Pill(if (t == 0) "TIMER OFF" else "${t}s", selected = s.timerSeconds == t) {
                    controller.update { it.copy(timerSeconds = t) }
                }
            }
        }
        SliderRow("${s.intervalSeconds}s apart") {
            LinearSlider(s.intervalSeconds.toFloat(), 1f, 120f, true, Modifier.weight(1f), step = 1f) { v ->
                controller.update { it.copy(intervalSeconds = v.roundToInt()) }
            }
        }
        SliderRow("${s.intervalCount} shots") {
            LinearSlider(s.intervalCount.toFloat(), 2f, 200f, true, Modifier.weight(1f), step = 1f) { v ->
                controller.update { it.copy(intervalCount = v.roundToInt()) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                if (state.intervalRunning) "STOP INTERVALOMETER" else "START INTERVALOMETER",
                selected = state.intervalRunning,
                enabled = state.sessionReady
            ) {
                if (state.intervalRunning) {
                    controller.capture()            // the shutter cancels a running sequence
                } else {
                    controller.startIntervalometer()
                    onClose()
                }
            }
        }
        HintText(
            "Each shot uses the current drive mode and settings. The screen stays on, and the sequence stops if you leave the app " +
                "or change the output format. Press the shutter, a volume key or a remote button to cancel."
        )
    }
}