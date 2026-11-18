package com.keiranhaas.auralcam.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keiranhaas.auralcam.analysis.AnalysisPipeline
import com.keiranhaas.auralcam.camera.NormPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

// ---------------------------------------------------------------- focus peaking + zebras

@Composable
fun PeakZebraOverlay(analysis: AnalysisPipeline, modifier: Modifier = Modifier) {
    val frame by analysis.overlay.collectAsStateWithLifecycle()
    val f = frame ?: return
    Canvas(modifier) {
        if (f.version < 0) return@Canvas   // reads version so each new frame redraws
        drawImage(
            image = f.bitmap.asImageBitmap(),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(f.bitmap.width, f.bitmap.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None
        )
    }
}

// ---------------------------------------------------------------- composition grids

private fun foot(p: Offset, a: Offset, b: Offset): Offset {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / (abx * abx + aby * aby)
    return Offset(a.x + abx * t, a.y + aby * t)
}

@Composable
fun GridOverlay(type: GridType, modifier: Modifier = Modifier) {
    if (type == GridType.OFF) return
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val color = Color.White.copy(alpha = 0.55f)
        val sw = 1.dp.toPx()
        fun line(a: Offset, b: Offset) = drawLine(color, a, b, sw)
        fun triangles(a: Offset, b: Offset, p1: Offset, p2: Offset) {
            line(a, b)
            line(p1, foot(p1, a, b))
            line(p2, foot(p2, a, b))
        }
        when (type) {
            GridType.THIRDS -> for (i in 1..2) {
                line(Offset(w * i / 3f, 0f), Offset(w * i / 3f, h))
                line(Offset(0f, h * i / 3f), Offset(w, h * i / 3f))
            }
            GridType.GOLDEN -> for (f in floatArrayOf(0.381966f, 0.618034f)) {
                line(Offset(w * f, 0f), Offset(w * f, h))
                line(Offset(0f, h * f), Offset(w, h * f))
            }
            GridType.TRIANGLE_A ->
                triangles(Offset(0f, h), Offset(w, 0f), Offset(0f, 0f), Offset(w, h))
            GridType.TRIANGLE_B ->
                triangles(Offset(0f, 0f), Offset(w, h), Offset(w, 0f), Offset(0f, h))
            GridType.OFF -> Unit
        }
    }
}

// ---------------------------------------------------------------- level / tiltmeter

class LevelReading(
    /** Roll in degrees, clockwise positive. */
    val rollDeg: Float = 0f,
    /** Camera elevation above the horizon in degrees (up positive). */
    val elevationDeg: Float = 0f,
    /** True when the phone lies roughly flat (camera pointing up or down). */
    val flat: Boolean = false,
    /** Bubble offset for flat mode, -1..1. */
    val bubbleX: Float = 0f,
    val bubbleY: Float = 0f
)

@Composable
fun rememberLevel(enabled: Boolean): State<LevelReading> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(LevelReading()) }
    DisposableEffect(enabled) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || sm == null || sensor == null) return@DisposableEffect onDispose { }
        val f = FloatArray(3)
        var init = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (!init) {
                    for (i in 0..2) f[i] = e.values[i]
                    init = true
                } else {
                    for (i in 0..2) f[i] += 0.15f * (e.values[i] - f[i])
                }
                val g = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
                if (g < 1f) return
                val roll = Math.toDegrees(atan2((-f[0]).toDouble(), f[1].toDouble())).toFloat()
                val elev = -Math.toDegrees(asin((f[2] / g).coerceIn(-1f, 1f).toDouble())).toFloat()
                state.value = LevelReading(
                    rollDeg = roll,
                    elevationDeg = elev,
                    flat = abs(f[2]) / g > 0.85f,
                    bubbleX = (f[0] / g).coerceIn(-1f, 1f),
                    bubbleY = (f[1] / g).coerceIn(-1f, 1f)
                )
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

@Composable
fun LevelOverlay(level: State<LevelReading>, modifier: Modifier = Modifier) {
    val rollInt by remember(level) { derivedStateOf { level.value.rollDeg.roundToInt() } }
    val elevInt by remember(level) { derivedStateOf { level.value.elevationDeg.roundToInt() } }
    val flat by remember(level) { derivedStateOf { level.value.flat } }

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val r = level.value
            val c = center
            val sw = 2.dp.toPx()
            if (r.flat) {
                val radius = size.minDimension * 0.10f
                val level0 = abs(r.bubbleX) < 0.026f && abs(r.bubbleY) < 0.026f   // about 1.5 degrees
                val col = if (level0) GoodGreen else Color.White.copy(alpha = 0.85f)
                drawCircle(col, radius, c, style = Stroke(sw))
                drawLine(col.copy(alpha = 0.5f), Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), sw / 2)
                drawLine(col.copy(alpha = 0.5f), Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), sw / 2)
                val k = radius * 5f
                drawCircle(col, radius * 0.25f, Offset(c.x + r.bubbleX * k, c.y - r.bubbleY * k))
            } else {
                val len = size.width * 0.26f
                val col = if (abs(r.rollDeg) < 1f) GoodGreen else Color.White.copy(alpha = 0.85f)
                // The horizon stays level with the world, so it turns against the phone's roll.
                rotate(-r.rollDeg, pivot = c) {
                    drawLine(col, Offset(c.x - len, c.y), Offset(c.x + len, c.y), sw)
                }
                val tick = Color.White.copy(alpha = 0.55f)
                drawLine(tick, Offset(c.x - len - 40.dp.toPx(), c.y), Offset(c.x - len - 14.dp.toPx(), c.y), sw / 2)
                drawLine(tick, Offset(c.x + len + 14.dp.toPx(), c.y), Offset(c.x + len + 40.dp.toPx(), c.y), sw / 2)
            }
        }
        Text(
            text = if (flat) "FLAT" else "${if (rollInt > 0) "+" else ""}$rollInt°   ↕ ${if (elevInt > 0) "+" else ""}$elevInt°",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Scrim)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ---------------------------------------------------------------- focus + exposure reticles

private fun afColor(st: Int?): Color = when (st) {
    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> GoodGreen
    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> WarnRed
    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> Color.White
    else -> Accent
}

@Composable
fun Reticles(focus: NormPoint?, exposure: NormPoint?, afState: Int?, modifier: Modifier = Modifier) {
    if (focus == null && exposure == null) return
    Canvas(modifier) {
        val sw = 2.dp.toPx()
        focus?.let { p ->
            val c = Offset(p.x * size.width, p.y * size.height)
            val half = 34.dp.toPx()
            drawRect(afColor(afState), Offset(c.x - half, c.y - half), Size(half * 2, half * 2), style = Stroke(sw))
        }
        exposure?.let { p ->
            val c = Offset(p.x * size.width, p.y * size.height)
            val r = 24.dp.toPx()
            drawCircle(Accent, r, c, style = Stroke(sw))
            drawCircle(Accent, 3.dp.toPx(), c)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dx = Math.cos(a).toFloat()
                val dy = Math.sin(a).toFloat()
                drawLine(Accent, Offset(c.x + dx * (r + 4.dp.toPx()), c.y + dy * (r + 4.dp.toPx())),
                    Offset(c.x + dx * (r + 9.dp.toPx()), c.y + dy * (r + 9.dp.toPx())), sw)
            }
        }
    }
}

// ---------------------------------------------------------------- histogram, waveform, vectorscope

@Composable
fun HistogramView(analysis: AnalysisPipeline, mode: HistMode, modifier: Modifier = Modifier) {
    if (mode == HistMode.OFF) return
    val result by analysis.result.collectAsStateWithLifecycle()
    val r = result
    val plotHeight = when (mode) {
        HistMode.VECTOR -> 110.dp
        HistMode.WAVE -> 84.dp
        else -> 56.dp
    }

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Scrim)
            .padding(6.dp)
    ) {
        Canvas(Modifier.size(width = 132.dp, height = plotHeight)) {
            drawRect(Color(0x33FFFFFF), style = Stroke(1f))
            val hs = r?.histograms ?: return@Canvas
            val w = size.width
            val h = size.height

            when (mode) {
                HistMode.WAVE -> drawWaveform(hs.wave, hs.wavePeak)
                HistMode.VECTOR -> drawVector(hs.vec, hs.vecPeak)
                else -> {
                    fun curve(bins: IntArray): Path {
                        val p = Path()
                        p.moveTo(0f, h)
                        for (i in 0..255) {
                            val y = h - sqrt(bins[i].toFloat() / hs.peak) * h   // sqrt scale keeps small counts visible
                            p.lineTo(i / 255f * w, y)
                        }
                        p.lineTo(w, h)
                        p.close()
                        return p
                    }

                    if (mode == HistMode.LUMA) {
                        drawPath(curve(hs.luma), Color.White.copy(alpha = 0.75f))
                    } else {
                        for ((bins, col) in listOf(
                            hs.red to Color(0xFFFF4444),
                            hs.green to Color(0xFF44FF66),
                            hs.blue to Color(0xFF5588FF)
                        )) {
                            val path = curve(bins)
                            drawPath(path, col.copy(alpha = 0.30f))
                            drawPath(path, col.copy(alpha = 0.90f), style = Stroke(1.dp.toPx()))
                        }
                    }
                    if (r.highlightClipPct > 0.5f) drawRect(WarnRed, Offset(w - 3.dp.toPx(), 0f), Size(3.dp.toPx(), h))
                    if (r.shadowClipPct > 0.5f) drawRect(Color(0xFF5588FF), Offset(0f, 0f), Size(3.dp.toPx(), h))
                }
            }
        }

        if (mode == HistMode.LUMA || mode == HistMode.RGB) {
            Row(Modifier.width(132.dp)) {
                val hi = r?.highlightClipPct ?: 0f
                val lo = r?.shadowClipPct ?: 0f
                Text("HI ${formatPct(hi)}", color = if (hi > 0.5f) WarnRed else Color(0xAAFFFFFF), fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("LO ${formatPct(lo)}", color = if (lo > 0.5f) Color(0xFF5588FF) else Color(0xAAFFFFFF), fontSize = 10.sp)
            }
        } else {
            Text(
                if (mode == HistMode.WAVE) "WAVEFORM  left→right of frame" else "VECTORSCOPE  orange = skin tone",
                color = Color(0xAAFFFFFF), fontSize = 10.sp
            )
        }
    }
}

/** Luma against horizontal position: bright areas climb the plot, so clipping shows as a flat line on top. */
private fun DrawScope.drawWaveform(bins: IntArray, peak: Int) {
    if (bins.size < 4096) return
    val cw = size.width / 64f
    val ch = size.height / 64f
    for (col in 0 until 64) {
        for (lvl in 0 until 64) {
            val n = bins[col * 64 + lvl]
            if (n == 0) continue
            val a = sqrt(n.toFloat() / peak).coerceIn(0.12f, 1f)
            drawRect(
                Color(0xFF6CFFB0).copy(alpha = a),
                topLeft = Offset(col * cw, (63 - lvl) * ch),
                size = Size(cw + 0.5f, ch + 0.5f)
            )
        }
    }
    for (f in floatArrayOf(0.25f, 0.5f, 0.75f)) {
        drawLine(Color(0x33FFFFFF), Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f)
    }
}

/** Chroma plot (U across, V up). A neutral scene piles up in the centre; the circle marks full saturation. */
private fun DrawScope.drawVector(bins: IntArray, peak: Int) {
    if (bins.size < 4096) return
    val side = minOf(size.width, size.height)
    val left = (size.width - side) / 2f
    val cell = side / 64f
    val logPeak = ln(1f + peak)
    for (v in 0 until 64) {
        for (u in 0 until 64) {
            val n = bins[v * 64 + u]
            if (n == 0) continue
            val a = (ln(1f + n) / logPeak).coerceIn(0.15f, 1f)
            val uu = u * 4 + 2 - 128f
            val vv = v * 4 + 2 - 128f
            val y = 150f
            val rr = (y + 1.402f * vv).coerceIn(0f, 255f) / 255f
            val gg = (y - 0.344f * uu - 0.714f * vv).coerceIn(0f, 255f) / 255f
            val bb = (y + 1.772f * uu).coerceIn(0f, 255f) / 255f
            drawRect(
                Color(rr, gg, bb, a),
                topLeft = Offset(left + u * cell, (63 - v) * cell),
                size = Size(cell + 0.5f, cell + 0.5f)
            )
        }
    }
    val c = Offset(left + side / 2f, side / 2f)
    val radius = side / 2f
    val grid = Color(0x66FFFFFF)
    drawCircle(grid, radius, c, style = Stroke(1f))
    drawLine(grid, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1f)
    drawLine(grid, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1f)
    val skin = Math.toRadians(123.0)
    drawLine(
        Color(0xCCFF9800), c,
        Offset(c.x + cos(skin).toFloat() * radius, c.y - sin(skin).toFloat() * radius), 1.5f
    )
}

/** Small badge that appears when more than [thresholdPct] of the frame is blown out or crushed. */
@Composable
fun ClipBadge(analysis: AnalysisPipeline, thresholdPct: Float, modifier: Modifier = Modifier) {
    if (thresholdPct <= 0f) return
    val result by analysis.result.collectAsStateWithLifecycle()
    val r = result ?: return
    val hi = r.highlightClipPct
    val lo = r.shadowClipPct
    val highlights = hi >= thresholdPct
    if (!highlights && lo < thresholdPct * 5f) return
    Text(
        text = if (highlights) "HIGHLIGHTS ${formatPct(hi)}" else "SHADOWS ${formatPct(lo)}",
        color = Color.Black,
        fontSize = 11.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlights) WarnRed else Color(0xFF5588FF))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}