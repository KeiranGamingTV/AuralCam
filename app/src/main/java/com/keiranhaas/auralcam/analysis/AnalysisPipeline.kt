package com.keiranhaas.auralcam.analysis

import android.graphics.Bitmap
import android.media.Image
import com.keiranhaas.auralcam.NativeBridge
import com.keiranhaas.auralcam.camera.FrameSink
import com.keiranhaas.auralcam.camera.NormPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ln

data class AnalysisConfig(
    val peaking: Boolean = false,
    /** Edge strength (0..1020) a pixel must exceed to be painted. Lower = more sensitive. */
    val peakThreshold: Int = 50,
    val peakColor: Int = 0xFF39FF6A.toInt(),
    val zebra: Boolean = false,
    /** Luma (0..255) at or above which zebra stripes appear. */
    val zebraThreshold: Int = 245,
    /** See [analysisRotation]. */
    val rotationDeg: Int = 0,
    /** Metered area in the upright image, 0..1. Defaults to the whole frame. */
    val meterLeft: Float = 0f,
    val meterTop: Float = 0f,
    val meterRight: Float = 1f,
    val meterBottom: Float = 1f
) {
    /** Meter a square around [p] (the AE point), or the whole frame when [p] is null. */
    fun withMeterAround(p: NormPoint?, half: Float = 0.12f): AnalysisConfig =
        if (p == null) copy(meterLeft = 0f, meterTop = 0f, meterRight = 1f, meterBottom = 1f)
        else copy(
            meterLeft = (p.x - half).coerceIn(0f, 1f), meterTop = (p.y - half).coerceIn(0f, 1f),
            meterRight = (p.x + half).coerceIn(0f, 1f), meterBottom = (p.y + half).coerceIn(0f, 1f)
        )
}

/** Same rotation the camera layer uses to map touch points to sensor coordinates. */
fun analysisRotation(sensorOrientation: Int, displayRotationDeg: Int): Int =
    ((sensorOrientation - displayRotationDeg) % 360 + 360) % 360

class Histograms(
    val luma: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    /** 64 columns x 64 luma levels: index = column * 64 + level. */
    val wave: IntArray = IntArray(0),
    /** 64 x 64 chroma cells: index = v * 64 + u. */
    val vec: IntArray = IntArray(0)
) {
    /** Tallest bin across all channels, for scaling the plot. */
    val peak: Int = maxOf(luma.max(), red.max(), green.max(), blue.max()).coerceAtLeast(1)
    val wavePeak: Int = (wave.maxOrNull() ?: 1).coerceAtLeast(1)
    val vecPeak: Int = (vec.maxOrNull() ?: 1).coerceAtLeast(1)
}

/**
 * Stops (positive = brighter) that would move the brightest 0.5% of pixels to about 94% of full scale.
 * Based on the preview's luma histogram, so it is a guide, not a guarantee of RAW headroom.
 */
fun AnalysisResult.ettrStops(): Float? {
    val lum = histograms.luma
    val total = lum.sum()
    if (total <= 0) return null
    val limit = (total * 0.005).toLong()
    var acc = 0L
    var bin = 255
    while (bin > 0) {
        acc += lum[bin]
        if (acc >= limit) break
        bin--
    }
    val p = bin.coerceAtLeast(8) / 255f
    val stops = 2.2f * ln(0.94f / p) / ln(2f)
    return stops.coerceIn(-3f, 3f)
}

class AnalysisResult(
    val histograms: Histograms,
    /** Mean luma of the metered area, 0..1. */
    val meterLuma: Float,
    /** Percent of pixels at luma >= 250. */
    val highlightClipPct: Float,
    /** Percent of pixels at luma <= 5. */
    val shadowClipPct: Float,
    val meanLuma: Float,
    val peakingPct: Float,
    val frame: Long
)

/** Draw [bitmap] stretched over the preview. It is already upright. A new [version] means new content. */
class OverlayFrame(val bitmap: Bitmap, val version: Long)

/**
 * Receives analysis-size YUV frames from CameraController, runs the native analysis, and publishes
 * histograms and a peaking/zebra overlay. Frames arrive on the controller's analysis thread.
 */
class AnalysisPipeline(
    /** Called every frame with the metered luma; wire it to CameraController::onMeteredLuma. */
    private val onMeteredLuma: ((Float) -> Unit)? = null
) : FrameSink {

    @Volatile var config = AnalysisConfig()

    private val _result = MutableStateFlow<AnalysisResult?>(null)
    val result: StateFlow<AnalysisResult?> = _result.asStateFlow()

    private val _overlay = MutableStateFlow<OverlayFrame?>(null)
    val overlay: StateFlow<OverlayFrame?> = _overlay.asStateFlow()

    // Three bitmaps in rotation, so the UI never draws one while native code is writing it.
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var bitmapIndex = 0
    private var overlayActive = false
    private var frameCounter = 0L
    private val meter = FloatArray(4)
    private val stats = FloatArray(8)

    override fun onFrame(image: Image) {
        val planes = image.planes
        if (planes.size < 3) return
        val cfg = config
        val rot = ((cfg.rotationDeg % 360) + 360) % 360
        val sideways = rot == 90 || rot == 270
        val outW = if (sideways) image.height else image.width
        val outH = if (sideways) image.width else image.height

        val wantOverlay = cfg.peaking || cfg.zebra
        val target = if (wantOverlay) nextBitmap(outW, outH) else null

        meter[0] = cfg.meterLeft
        meter[1] = cfg.meterTop
        meter[2] = cfg.meterRight
        meter[3] = cfg.meterBottom
        val hist = IntArray(9216)

        frameCounter++
        val ok = NativeBridge.analyzeYuv(
            planes[0].buffer, planes[1].buffer, planes[2].buffer,
            image.width, image.height,
            planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
            rot,
            cfg.peaking, cfg.peakThreshold, cfg.peakColor,
            cfg.zebra, cfg.zebraThreshold, (frameCounter / 3).toInt() and 0xFFFF,
            meter, target, hist, stats
        )
        if (!ok) return

        onMeteredLuma?.invoke(stats[0])

        if (target != null) {
            _overlay.value = OverlayFrame(target, frameCounter)
            overlayActive = true
        } else if (overlayActive) {
            _overlay.value = null
            overlayActive = false
        }

        if (frameCounter % 2L == 0L) {
            _result.value = AnalysisResult(
                histograms = Histograms(
                    hist.copyOfRange(0, 256), hist.copyOfRange(256, 512),
                    hist.copyOfRange(512, 768), hist.copyOfRange(768, 1024),
                    hist.copyOfRange(1024, 5120), hist.copyOfRange(5120, 9216)
                ),
                meterLuma = stats[0],
                highlightClipPct = stats[1],
                shadowClipPct = stats[2],
                meanLuma = stats[3],
                peakingPct = stats[4],
                frame = frameCounter
            )
        }
    }

    private fun nextBitmap(w: Int, h: Int): Bitmap {
        bitmapIndex = (bitmapIndex + 1) % bitmaps.size
        val existing = bitmaps[bitmapIndex]
        if (existing != null && existing.width == w && existing.height == h) return existing
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmaps[bitmapIndex] = fresh
        return fresh
    }
}