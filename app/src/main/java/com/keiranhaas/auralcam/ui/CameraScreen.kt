package com.keiranhaas.auralcam.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.TextureView
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keiranhaas.auralcam.analysis.AnalysisPipeline
import com.keiranhaas.auralcam.analysis.analysisRotation
import com.keiranhaas.auralcam.analysis.ettrStops
import com.keiranhaas.auralcam.camera.CameraController
import com.keiranhaas.auralcam.camera.CameraState
import com.keiranhaas.auralcam.camera.CaptureFormat
import com.keiranhaas.auralcam.camera.DriveMode
import com.keiranhaas.auralcam.camera.ExposureMode
import com.keiranhaas.auralcam.camera.FocusMode
import com.keiranhaas.auralcam.camera.NormPoint
import com.keiranhaas.auralcam.camera.WhiteBalanceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class DragState(val target: TouchTarget, val point: NormPoint)

@Composable
fun CameraScreen(controller: CameraController, analysis: AnalysisPipeline, displayRotationDeg: Int) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    var ui by remember { mutableStateOf(UiOptions()) }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val level = rememberLevel(ui.level, displayRotationDeg)
    val s = state.settings
    val caps = state.active
    val landscape = displayRotationDeg == 90 || displayRotationDeg == 270

    // Push overlay settings and the metered area into the native analysis.
    LaunchedEffect(ui.peaking, ui.zebra, ui.zebraLevel, ui.peakThreshold, s.aePoint, caps?.sensorOrientation, displayRotationDeg) {
        analysis.config = analysis.config.copy(
            peaking = ui.peaking,
            zebra = ui.zebra,
            zebraThreshold = ui.zebraLevel,
            peakThreshold = ui.peakThreshold,
            rotationDeg = analysisRotation(caps?.sensorOrientation ?: 90, displayRotationDeg)
        ).withMeterAround(s.aePoint)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            controller.consumeMessage()
        }
    }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.shutterTick) {
        if (state.shutterTick > 0) {
            flash.snapTo(0.55f)
            flash.animateTo(0f, tween(180))
        }
    }

    val previewSize = state.previewSize
    val bufferW = previewSize?.let { max(it.width, it.height) } ?: 0
    val bufferH = previewSize?.let { min(it.width, it.height) } ?: 0
    val ratio = if (bufferH > 0) bufferW.toFloat() / bufferH.toFloat() else 4f / 3f
    val aspect = if (landscape) ratio else 1f / ratio

    val latestState by rememberUpdatedState(state)
    val latestTarget by rememberUpdatedState(ui.touchTarget)
    val magOrigin = s.afPoint?.takeIf { s.focusMode == FocusMode.AUTO_TOUCH } ?: NormPoint(0.5f, 0.5f)

    // ---------------------------------------------------------------- preview area
    val previewBlock: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            // Everything that must magnify together (and keep touch coordinates honest) lives in this layer.
            Box(Modifier.fillMaxSize().clipToBounds()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (ui.magnifier) {
                                scaleX = ui.magnifyFactor
                                scaleY = ui.magnifyFactor
                                transformOrigin = TransformOrigin(magOrigin.x, magOrigin.y)
                            }
                        }
                ) {
                    CameraPreview(controller, bufferW, bufferH, displayRotationDeg, Modifier.fillMaxSize())
                    PeakZebraOverlay(analysis, Modifier.fillMaxSize())

                    val d = drag
                    val focusPoint = if (d?.target == TouchTarget.FOCUS) d.point
                    else s.afPoint.takeIf { s.focusMode == FocusMode.AUTO_TOUCH }
                    val exposurePoint = if (d?.target == TouchTarget.EXPOSURE) d.point else s.aePoint
                    Reticles(focusPoint, exposurePoint, state.live.afState, Modifier.fillMaxSize())

                    // Touch layer: tap = place point, long-press = clear, drag a reticle = move it, pinch = zoom.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { off ->
                                        val p = NormPoint(
                                            (off.x / size.width).coerceIn(0f, 1f),
                                            (off.y / size.height).coerceIn(0f, 1f)
                                        )
                                        when (latestTarget) {
                                            TouchTarget.FOCUS -> controller.tapToFocus(p)
                                            TouchTarget.EXPOSURE -> controller.setExposurePoint(p)
                                            TouchTarget.BOTH -> {
                                                controller.tapToFocus(p)
                                                controller.setExposurePoint(p)
                                            }
                                        }
                                    },
                                    onLongPress = {
                                        controller.clearFocusPoint()
                                        controller.setExposurePoint(null)
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                var target: TouchTarget? = null
                                detectDragGestures(
                                    onDragStart = { off ->
                                        val st = latestState.settings
                                        val w = size.width
                                        val h = size.height
                                        val threshold = 56.dp.toPx()
                                        fun dist(p: NormPoint?) =
                                            if (p == null) Float.MAX_VALUE else hypot(p.x * w - off.x, p.y * h - off.y)
                                        val df = dist(st.afPoint.takeIf { st.focusMode == FocusMode.AUTO_TOUCH })
                                        val da = dist(st.aePoint)
                                        target = when {
                                            df <= threshold && df <= da -> TouchTarget.FOCUS
                                            da <= threshold -> TouchTarget.EXPOSURE
                                            else -> null
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        val t = target
                                        if (t != null) {
                                            val p = NormPoint(
                                                (change.position.x / size.width).coerceIn(0f, 1f),
                                                (change.position.y / size.height).coerceIn(0f, 1f)
                                            )
                                            drag = DragState(t, p)
                                            if (t == TouchTarget.EXPOSURE) controller.setExposurePoint(p)
                                        }
                                    },
                                    onDragEnd = {
                                        val dd = drag
                                        if (dd?.target == TouchTarget.FOCUS) controller.tapToFocus(dd.point)
                                        drag = null
                                        target = null
                                    },
                                    onDragCancel = {
                                        drag = null
                                        target = null
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var last = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            val dist = (pressed[0].position - pressed[1].position).getDistance()
                                            if (last > 0f && dist > 0f) {
                                                val factor = dist / last
                                                controller.update { it.copy(zoomRatio = it.zoomRatio * factor) }
                                            }
                                            last = dist
                                            event.changes.forEach { it.consume() }
                                        } else {
                                            last = 0f
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    )
                }
            }

            // These stay put when the preview is magnified.
            GridOverlay(ui.grid, Modifier.fillMaxSize())
            if (ui.level) LevelOverlay(level, Modifier.fillMaxSize())

            // Toolbar
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Pill(formatLabel(s.format), selected = s.drive == DriveMode.LOW_LIGHT, enabled = s.drive != DriveMode.LOW_LIGHT) {
                    controller.update { it.copy(format = nextFormat(it.format, caps?.raw == true)) }
                }
                Pill("PEAK", ui.peaking) { ui = ui.copy(peaking = !ui.peaking) }
                Pill("ZEBRA", ui.zebra) { ui = ui.copy(zebra = !ui.zebra) }
                Pill(if (ui.magnifier) "MAG ${ui.magnifyFactor.roundToInt()}×" else "MAG", ui.magnifier) {
                    ui = ui.copy(magnifier = !ui.magnifier)
                }
                Pill(ui.grid.label, ui.grid != GridType.OFF) {
                    ui = ui.copy(grid = GridType.entries[(ui.grid.ordinal + 1) % GridType.entries.size])
                }
                Pill("LEVEL", ui.level) { ui = ui.copy(level = !ui.level) }
                Pill(if (s.timerSeconds == 0) "TIMER" else "TIMER ${s.timerSeconds}s", s.timerSeconds > 0) {
                    controller.update { it.copy(timerSeconds = nextTimer(it.timerSeconds)) }
                }
                Pill("ETTR", enabled = state.analysisAvailable) {
                    val stops = analysis.result.value?.ettrStops()
                    if (stops != null) controller.adjustExposureStops(stops)
                }
                Pill(ui.hist.label, ui.hist != HistMode.OFF) {
                    ui = ui.copy(hist = HistMode.entries[(ui.hist.ordinal + 1) % HistMode.entries.size])
                }
                Pill("SET") { ui = ui.copy(settingsOpen = true) }
            }

            // Live readout
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Scrim)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val iso = state.live.iso ?: s.iso
                val ns = state.live.exposureTimeNs ?: s.exposureTimeNs
                Text("ISO $iso   ${formatShutter(ns)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${if (s.whiteBalance == WhiteBalanceMode.AUTO) "AWB" else "${s.kelvin}K"}   ${formatZoom(s.zoomRatio)}   " +
                        driveLabel(s.drive, s.burstCount, s.lowLightFrames),
                    color = Color(0xCCFFFFFF), fontSize = 11.sp
                )
                if (state.exposureAtLimit) Text("EXPOSURE LIMIT", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                ClipBadge(analysis, ui.clipAlertPct, Modifier.padding(top = 3.dp))
            }

            HistogramView(analysis, ui.hist, Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 48.dp))

            // Lens switcher
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.lenses.forEach { c ->
                    Pill("${c.equivFocalMm.roundToInt()}mm", selected = caps?.id == c.id) { controller.selectLens(c.id) }
                }
            }

            if (flash.value > 0.01f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
        }
    }

    // ---------------------------------------------------------------- controls area
    val controlsBlock: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExposureModeRow(state, controller)
                TouchRow(state, ui, { ui = it }, controller)
                ParamTabs(state, ui, { ui = it })
                ParamControl(state, ui.tab, controller)
            }
            ShutterRow(state, controller)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            Row(Modifier.fillMaxSize().displayCutoutPadding().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    previewBlock(Modifier.aspectRatio(aspect))
                }
                controlsBlock(Modifier.width(340.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                previewBlock(Modifier.fillMaxWidth().aspectRatio(aspect))
                controlsBlock(Modifier.weight(1f).fillMaxWidth())
            }
        }

        if (ui.settingsOpen) {
            SettingsPanel(state, ui, { ui = it }, controller) { ui = ui.copy(settingsOpen = false) }
        }
    }
}

// ---------------------------------------------------------------- preview surface

private class PreviewParams {
    var bufferW = 0
    var bufferH = 0
    var rotation = 0
}

/**
 * The camera delivers sensor-landscape buffers. In portrait and the 180° case the system compensates;
 * in landscape the TextureView needs this rotate-and-scale matrix (the standard Camera2 sample transform).
 */
private fun applyPreviewTransform(view: TextureView, p: PreviewParams) {
    val vw = view.width.toFloat()
    val vh = view.height.toFloat()
    if (vw <= 0f || vh <= 0f || p.bufferW <= 0 || p.bufferH <= 0) return
    val matrix = Matrix()
    val cx = vw / 2f
    val cy = vh / 2f
    when (p.rotation) {
        90, 270 -> {
            val viewRect = RectF(0f, 0f, vw, vh)
            val bufferRect = RectF(0f, 0f, p.bufferH.toFloat(), p.bufferW.toFloat())
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / p.bufferH, vw / p.bufferW)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(if (p.rotation == 90) -90f else 90f, cx, cy)
        }
        180 -> matrix.postRotate(180f, cx, cy)
        else -> Unit
    }
    view.setTransform(matrix)
}

@Composable
private fun CameraPreview(
    controller: CameraController,
    bufferWidth: Int,
    bufferHeight: Int,
    rotationDeg: Int,
    modifier: Modifier = Modifier
) {
    val params = remember { PreviewParams() }
    params.bufferW = bufferWidth
    params.bufferH = bufferHeight
    params.rotation = rotationDeg

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        controller.setPreviewTexture(st)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        controller.setPreviewTexture(null)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyPreviewTransform(v as TextureView, params) }
            }
        },
        update = { view -> applyPreviewTransform(view, params) }
    )
}

// ---------------------------------------------------------------- control rows

@Composable
private fun ExposureModeRow(state: CameraState, controller: CameraController) {
    val manual = state.active?.manualSensor == true
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((mode, label) in listOf(
            ExposureMode.AUTO to "AUTO",
            ExposureMode.MANUAL to "MANUAL",
            ExposureMode.SHUTTER_PRIORITY to "SHUTTER-P",
            ExposureMode.ISO_PRIORITY to "ISO-P"
        )) {
            val modeEnabled = when (mode) {
                ExposureMode.AUTO -> true
                ExposureMode.MANUAL -> manual
                else -> manual && state.analysisAvailable   // priority modes need the analysis stream
            }
            Pill(label, selected = state.settings.exposureMode == mode, enabled = modeEnabled) {
                controller.update { it.copy(exposureMode = mode) }
            }
        }
    }
}

@Composable
private fun TouchRow(state: CameraState, ui: UiOptions, setUi: (UiOptions) -> Unit, controller: CameraController) {
    val s = state.settings
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("TAP", color = Color(0xAAFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        TouchTarget.entries.forEach { t ->
            Pill(t.label, selected = ui.touchTarget == t) { setUi(ui.copy(touchTarget = t)) }
        }
        Pill("AE-L", selected = s.aeLocked, enabled = s.exposureMode == ExposureMode.AUTO) {
            controller.update { it.copy(aeLocked = !it.aeLocked) }
        }
        Pill("RESET") {
            controller.clearFocusPoint()
            controller.setExposurePoint(null)
            controller.update { it.copy(aeLocked = false) }
        }
    }
}

@Composable
private fun ParamTabs(state: CameraState, ui: UiOptions, setUi: (UiOptions) -> Unit) {
    val s = state.settings
    val live = state.live
    val auto = s.exposureMode == ExposureMode.AUTO
    val iso = if (auto) live.iso ?: s.iso else s.iso
    val ns = if (auto) live.exposureTimeNs ?: s.exposureTimeNs else s.exposureTimeNs
    val caps = state.active
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (tab in ParamTab.entries) {
            val value = when (tab) {
                ParamTab.ISO -> if (auto || s.exposureMode == ExposureMode.SHUTTER_PRIORITY) "A $iso" else "$iso"
                ParamTab.SHUTTER -> if (auto || s.exposureMode == ExposureMode.ISO_PRIORITY) "A ${formatShutter(ns)}" else formatShutter(ns)
                ParamTab.WB -> if (s.whiteBalance == WhiteBalanceMode.AUTO) "AUTO" else "${s.kelvin}K"
                ParamTab.FOCUS -> if (s.focusMode == FocusMode.MANUAL) formatFocus(s.focusDiopters) else "AF"
                ParamTab.EV -> formatEv(s.evIndex, caps?.evStep ?: 0f)
                ParamTab.ZOOM -> formatZoom(s.zoomRatio)
            }
            ParamChip(tab.title, value, ui.tab == tab, Modifier.weight(1f)) { setUi(ui.copy(tab = tab)) }
        }
    }
}

@Composable
private fun ParamControl(state: CameraState, tab: ParamTab, controller: CameraController) {
    val caps = state.active ?: return
    val s = state.settings
    val live = state.live
    val m = s.exposureMode

    when (tab) {
        ParamTab.ISO -> {
            val user = caps.manualSensor && (m == ExposureMode.MANUAL || m == ExposureMode.ISO_PRIORITY)
            val shown = if (m == ExposureMode.AUTO) live.iso ?: s.iso else s.iso
            SliderRow("ISO $shown") {
                LogSlider(shown.toDouble(), caps.isoMin.toDouble(), caps.isoMax.toDouble(), user, Modifier.weight(1f)) { v ->
                    controller.update { it.copy(iso = v.roundToInt()) }
                }
            }
            HintText(exposureHint(m, state.exposureAtLimit))
        }

        ParamTab.SHUTTER -> {
            val user = caps.manualSensor && (m == ExposureMode.MANUAL || m == ExposureMode.SHUTTER_PRIORITY)
            val shown = if (m == ExposureMode.AUTO) live.exposureTimeNs ?: s.exposureTimeNs else s.exposureTimeNs
            val maxNs = min(caps.exposureMaxNs, 30_000_000_000L)
            SliderRow(formatShutter(shown)) {
                LogSlider(shown.toDouble(), caps.exposureMinNs.toDouble(), maxNs.toDouble(), user, Modifier.weight(1f)) { v ->
                    controller.update { it.copy(exposureTimeNs = v.toLong()) }
                }
            }
            HintText(exposureHint(m, state.exposureAtLimit))
        }

        ParamTab.WB -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("AUTO", s.whiteBalance == WhiteBalanceMode.AUTO) {
                    controller.update { it.copy(whiteBalance = WhiteBalanceMode.AUTO) }
                }
                Pill("KELVIN", s.whiteBalance == WhiteBalanceMode.KELVIN) {
                    controller.update { it.copy(whiteBalance = WhiteBalanceMode.KELVIN) }
                }
            }
            SliderRow("${s.kelvin}K") {
                LinearSlider(
                    s.kelvin.toFloat(), 2000f, 10000f, s.whiteBalance == WhiteBalanceMode.KELVIN,
                    Modifier.weight(1f), step = 100f
                ) { v -> controller.update { it.copy(kelvin = v.roundToInt()) } }
            }
            if (!caps.exactKelvin) HintText("Approximate: this lens exposes no colour calibration, so presets are used.")
        }

        ParamTab.FOCUS -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("AF", s.focusMode != FocusMode.MANUAL) {
                    controller.update { it.copy(focusMode = FocusMode.AUTO_CONTINUOUS, afPoint = null) }
                }
                Pill("MF", s.focusMode == FocusMode.MANUAL, enabled = caps.manualSensor && caps.minFocusDiopters > 0f) {
                    val d = live.focusDiopters ?: 0f
                    controller.update { it.copy(focusMode = FocusMode.MANUAL, focusDiopters = d) }
                }
            }
            val d = if (s.focusMode == FocusMode.MANUAL) s.focusDiopters else live.focusDiopters ?: 0f
            SliderRow(formatFocus(d)) {
                LinearSlider(
                    d, 0f, caps.minFocusDiopters, s.focusMode == FocusMode.MANUAL, Modifier.weight(1f)
                ) { v -> controller.update { it.copy(focusDiopters = v) } }
            }
            HintText("Left is infinity, right is the closest focus. Use MAG in the toolbar to judge focus.")
        }

        ParamTab.EV -> {
            SliderRow("${formatEv(s.evIndex, caps.evStep)} EV") {
                LinearSlider(
                    s.evIndex.toFloat(), caps.evMin.toFloat(), caps.evMax.toFloat(),
                    m != ExposureMode.MANUAL && caps.evMax > caps.evMin,
                    Modifier.weight(1f), step = 1f
                ) { v -> controller.update { it.copy(evIndex = v.roundToInt()) } }
            }
            HintText(
                if (m == ExposureMode.MANUAL) "Exposure compensation doesn't apply in manual mode. ETTR in the toolbar adjusts the shutter instead."
                else "Shifts the exposure target in AUTO and in both priority modes. ETTR in the toolbar sets it for you."
            )
        }

        ParamTab.ZOOM -> {
            SliderRow(formatZoom(s.zoomRatio)) {
                LinearSlider(
                    s.zoomRatio, caps.zoomMin, caps.zoomMax, caps.zoomMax > caps.zoomMin, Modifier.weight(1f)
                ) { v -> controller.update { it.copy(zoomRatio = v) } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5f, 0.6f, 1f, 2f, 3f, 5f, 10f, 20f)
                    .filter { it >= caps.zoomMin - 0.001f && it <= caps.zoomMax + 0.001f }
                    .forEach { z ->
                        Pill(formatZoom(z), selected = kotlin.math.abs(s.zoomRatio - z) < 0.05f) {
                            controller.update { it.copy(zoomRatio = z) }
                        }
                    }
            }
            HintText("Pinch on the preview also zooms. The phone may switch lenses as the ratio crosses a stop.")
        }
    }
}

private fun exposureHint(m: ExposureMode, atLimit: Boolean): String = when (m) {
    ExposureMode.AUTO -> "Auto: the camera picks ISO and shutter."
    ExposureMode.MANUAL -> "Manual: you set both ISO and shutter."
    ExposureMode.SHUTTER_PRIORITY ->
        "Shutter priority: you set shutter, ISO follows." + if (atLimit) " ISO limit reached." else ""
    ExposureMode.ISO_PRIORITY ->
        "ISO priority: you set ISO, shutter follows." + if (atLimit) " Shutter limit reached." else ""
}

// ---------------------------------------------------------------- shutter row

@Composable
private fun ShutterRow(state: CameraState, controller: CameraController) {
    val s = state.settings
    val caps = state.active
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            LastShotThumb(state.lastSavedUri)
        }

        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(78.dp)
                    .alpha(if (state.sessionReady) 1f else 0.4f)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(if (state.busy) WarnRed else Color.White)
                    .clickable(enabled = state.sessionReady) { controller.capture() }
            )
            when {
                state.countdown > 0 ->
                    Text("${state.countdown}", color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                state.intervalRunning ->
                    Text("${state.intervalShot}/${s.intervalCount}", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Pill(driveLabel(s.drive, s.burstCount, s.lowLightFrames), selected = s.drive != DriveMode.SINGLE) {
                controller.update {
                    it.copy(
                        drive = nextDrive(
                            it.drive,
                            lowLightOk = caps?.raw == true && state.lowLightAvailable,
                            bracketOk = caps?.manualSensor == true
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun LastShotThumb(uri: Uri?) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, android.util.Size(192, 192), null).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(54.dp)
            .clip(shape)
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x66FFFFFF), shape)
            .clickable(enabled = uri != null) { uri?.let { openInViewer(context, it) } },
        contentAlignment = Alignment.Center
    ) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun openInViewer(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this image", Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------- small helpers

private fun formatLabel(f: CaptureFormat) = when (f) {
    CaptureFormat.JPEG -> "JPEG"
    CaptureFormat.DNG -> "DNG"
    CaptureFormat.DNG_JPEG -> "DNG+JPG"
}

private fun nextFormat(cur: CaptureFormat, rawAvailable: Boolean): CaptureFormat {
    if (!rawAvailable) return CaptureFormat.JPEG
    return when (cur) {
        CaptureFormat.JPEG -> CaptureFormat.DNG
        CaptureFormat.DNG -> CaptureFormat.DNG_JPEG
        CaptureFormat.DNG_JPEG -> CaptureFormat.JPEG
    }
}

private fun driveLabel(d: DriveMode, burst: Int, lowLight: Int) = when (d) {
    DriveMode.SINGLE -> "SINGLE"
    DriveMode.BURST -> "BURST ×$burst"
    DriveMode.BRACKET -> "BRACKET"
    DriveMode.LOW_LIGHT -> "LOW-LIGHT ×$lowLight"
}

private fun nextDrive(cur: DriveMode, lowLightOk: Boolean, bracketOk: Boolean): DriveMode {
    val order = listOf(DriveMode.SINGLE, DriveMode.BURST, DriveMode.BRACKET, DriveMode.LOW_LIGHT)
    var i = order.indexOf(cur)
    repeat(order.size) {
        i = (i + 1) % order.size
        val d = order[i]
        val ok = when (d) {
            DriveMode.LOW_LIGHT -> lowLightOk
            DriveMode.BRACKET -> bracketOk
            else -> true
        }
        if (ok) return d
    }
    return cur
}

private fun nextTimer(cur: Int) = when (cur) {
    0 -> 2
    2 -> 5
    5 -> 10
    else -> 0
}