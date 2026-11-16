package com.keiranhaas.auralcam.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keiranhaas.auralcam.camera.CameraController
import com.keiranhaas.auralcam.camera.CameraState
import com.keiranhaas.auralcam.camera.ProcessingOverrides
import com.keiranhaas.auralcam.camera.ToneChoice
import java.util.Locale

private fun nrLabel(m: Int) = when (m) {
    CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "OFF"
    CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "FAST"
    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HQ"
    CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL"
    CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZSL"
    else -> "MODE $m"
}

private fun edgeLabel(m: Int) = when (m) {
    CameraMetadata.EDGE_MODE_OFF -> "OFF"
    CameraMetadata.EDGE_MODE_FAST -> "FAST"
    CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "HQ"
    CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "ZSL"
    else -> "MODE $m"
}

/** Hot-pixel, aberration and distortion modes all share 0 = off, 1 = fast, 2 = high quality. */
private fun offFastHqLabel(m: Int) = when (m) {
    0 -> "OFF"
    1 -> "FAST"
    2 -> "HQ"
    else -> "MODE $m"
}

@Composable
private fun ModeRow(
    title: String,
    available: Set<Int>,
    selected: Int?,
    label: (Int) -> String,
    onSelect: (Int?) -> Unit
) {
    if (available.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HintText(title)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("DEFAULT", selected = selected == null) { onSelect(null) }
            available.sorted().forEach { m ->
                Pill(label(m), selected = selected == m) { onSelect(m) }
            }
        }
    }
}

@Composable
fun AdvancedProcessingSection(state: CameraState, controller: CameraController) {
    val caps = state.active ?: return
    val o = state.settings.overrides

    fun change(block: (ProcessingOverrides) -> ProcessingOverrides) {
        controller.update { it.copy(overrides = block(it.overrides)) }
    }

    PanelSection("ADVANCED PROCESSING (JPEG)") {
        ModeRow("Noise reduction", caps.nrModes, o.noiseReduction, ::nrLabel) { v ->
            change { it.copy(noiseReduction = v) }
        }
        ModeRow("Edge enhancement (sharpening)", caps.edgeModes, o.edge, ::edgeLabel) { v ->
            change { it.copy(edge = v) }
        }
        ModeRow("Hot-pixel correction", caps.hotPixelModes, o.hotPixel, ::offFastHqLabel) { v ->
            change { it.copy(hotPixel = v) }
        }
        ModeRow("Chromatic aberration correction", caps.aberrationModes, o.aberration, ::offFastHqLabel) { v ->
            change { it.copy(aberration = v) }
        }
        ModeRow("Lens distortion correction", caps.distortionModes, o.distortion, ::offFastHqLabel) { v ->
            change { it.copy(distortion = v) }
        }

        val tones = buildList {
            add(ToneChoice.DEFAULT)
            if (CameraMetadata.TONEMAP_MODE_FAST in caps.toneModes) add(ToneChoice.FAST)
            if (CameraMetadata.TONEMAP_MODE_HIGH_QUALITY in caps.toneModes) add(ToneChoice.HIGH_QUALITY)
            if (CameraMetadata.TONEMAP_MODE_PRESET_CURVE in caps.toneModes) {
                add(ToneChoice.SRGB)
                add(ToneChoice.REC709)
            }
            if (CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in caps.toneModes) add(ToneChoice.GAMMA)
        }
        if (tones.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HintText("Tone curve")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tones.forEach { t ->
                        Pill(t.label, selected = o.tone == t) { change { it.copy(tone = t) } }
                    }
                }
            }
            if (ToneChoice.GAMMA in tones) {
                SliderRow("γ ${String.format(Locale.US, "%.2f", o.gamma)}") {
                    LinearSlider(o.gamma, 1f, 3f, o.tone == ToneChoice.GAMMA, Modifier.weight(1f), step = 0.05f) { v ->
                        change { it.copy(gamma = v) }
                    }
                }
            }
        }

        Row {
            Pill("RESET ALL TO DEFAULT") { change { ProcessingOverrides() } }
        }
        HintText(
            "DEFAULT follows the Processing setting. These only change JPEG output; DNG files keep the raw sensor data. " +
                "Each row shows only what this lens reports, and the phone may still apply its own processing on top."
        )
    }
}