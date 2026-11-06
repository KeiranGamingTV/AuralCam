package com.keiranhaas.auralcam.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import kotlin.math.max
import kotlin.math.min

class RequestFactory(
    private val chars: CameraCharacteristics,
    private val caps: CameraCaps
) {
    private val keys: Set<CaptureRequest.Key<*>> = chars.availableCaptureRequestKeys.toSet()
    private val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet().orEmpty()
    private val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty()
    private val toneModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
    private val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
    private val shadingModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
        ?.map { it.toInt() }?.toSet().orEmpty()

    private fun <T> CaptureRequest.Builder.putIf(key: CaptureRequest.Key<T>, value: T) {
        if (keys.contains(key)) set(key, value)
    }

    fun apply(
        b: CaptureRequest.Builder,
        s: CameraSettings,
        displayRotationDeg: Int,
        still: Boolean,
        jpegOrientationDeg: Int = 0
    ) {
        b.putIf(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.putIf(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        b.putIf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.putIf(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoomRatio)
        if (caps.hasOis) {
            b.putIf(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (s.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        applyExposure(b, s, displayRotationDeg)
        applyFocus(b, s, displayRotationDeg)
        applyWhiteBalance(b, s)
        applyProcessing(b, s)

        if (still) {
            b.putIf(CaptureRequest.JPEG_QUALITY, s.jpegQuality.toByte())
            b.putIf(CaptureRequest.JPEG_ORIENTATION, jpegOrientationDeg)
            b.putIf(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            if (CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON in shadingModes) {
                b.putIf(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
        }
    }

    private fun applyExposure(b: CaptureRequest.Builder, s: CameraSettings, rot: Int) {
        if (s.exposureMode == ExposureMode.AUTO) {
            b.putIf(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.putIf(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex)
            b.putIf(CaptureRequest.CONTROL_AE_LOCK, s.aeLocked)
            if (caps.maxAeRegions > 0) {
                s.aePoint?.let { b.putIf(CaptureRequest.CONTROL_AE_REGIONS, regionFor(it, rot, s.zoomRatio)) }
            }
        } else {
            // MANUAL, SHUTTER_PRIORITY and ISO_PRIORITY all run with AE off; the priority loop moves the free value.
            b.putIf(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.putIf(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureTimeNs)
            b.putIf(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            b.putIf(CaptureRequest.SENSOR_FRAME_DURATION, max(s.exposureTimeNs, MIN_FRAME_NS))
        }
    }

    private fun applyFocus(b: CaptureRequest.Builder, s: CameraSettings, rot: Int) {
        if (!caps.hasAutofocus && s.focusMode != FocusMode.MANUAL) return
        when (s.focusMode) {
            FocusMode.MANUAL -> {
                b.putIf(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.putIf(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
            }
            FocusMode.AUTO_TOUCH -> {
                b.putIf(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                if (caps.maxAfRegions > 0) {
                    s.afPoint?.let { b.putIf(CaptureRequest.CONTROL_AF_REGIONS, regionFor(it, rot, s.zoomRatio)) }
                }
            }
            FocusMode.AUTO_CONTINUOUS ->
                b.putIf(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    private fun applyWhiteBalance(b: CaptureRequest.Builder, s: CameraSettings) {
        if (s.whiteBalance == WhiteBalanceMode.AUTO) {
            b.putIf(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            return
        }
        val solution = if (caps.manualPostProcessing) ColorMath.solve(chars, s.kelvin) else null
        if (solution != null) {
            b.putIf(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.putIf(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.putIf(CaptureRequest.COLOR_CORRECTION_GAINS, solution.gains)
            b.putIf(CaptureRequest.COLOR_CORRECTION_TRANSFORM, solution.transform)
        } else {
            val preset = when {
                s.kelvin < 3500 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                s.kelvin < 4500 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                s.kelvin < 6000 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                else -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            }
            b.putIf(
                CaptureRequest.CONTROL_AWB_MODE,
                if (preset in awbModes) preset else CameraMetadata.CONTROL_AWB_MODE_AUTO
            )
        }
    }

    /** MINIMAL asks the pipeline to stay out of the way where the device allows it. */
    private fun applyProcessing(b: CaptureRequest.Builder, s: CameraSettings) {
        if (s.processing != ProcessingProfile.MINIMAL) return
        when {
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL in nrModes ->
                b.putIf(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
            CameraMetadata.NOISE_REDUCTION_MODE_OFF in nrModes ->
                b.putIf(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        }
        if (CameraMetadata.EDGE_MODE_OFF in edgeModes) {
            b.putIf(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        }
        if (CameraMetadata.TONEMAP_MODE_PRESET_CURVE in toneModes) {
            b.putIf(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
            b.putIf(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
        }
    }

    /** Maps a point in the upright displayed preview to a metering region in sensor coordinates. */
    fun regionFor(p: NormPoint, displayRotationDeg: Int, zoom: Float): Array<MeteringRectangle> {
        val rot = ((caps.sensorOrientation - displayRotationDeg) % 360 + 360) % 360
        val (sx0, sy0) = when (rot) {
            90 -> p.y to (1f - p.x)
            180 -> (1f - p.x) to (1f - p.y)
            270 -> (1f - p.y) to p.x
            else -> p.x to p.y
        }
        val z = max(zoom, 1f)
        val sx = 0.5f + (sx0 - 0.5f) / z
        val sy = 0.5f + (sy0 - 0.5f) / z
        val a: Rect = caps.activeArray
        val half = (min(a.width(), a.height()) * 0.08f).toInt().coerceAtLeast(8)
        val cx = a.left + sx * a.width()
        val cy = a.top + sy * a.height()
        val x = (cx - half).toInt().coerceIn(a.left, a.right - 2 * half)
        val y = (cy - half).toInt().coerceIn(a.top, a.bottom - 2 * half)
        return arrayOf(MeteringRectangle(x, y, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX))
    }

    private companion object {
        const val MIN_FRAME_NS = 33_333_333L
    }
}