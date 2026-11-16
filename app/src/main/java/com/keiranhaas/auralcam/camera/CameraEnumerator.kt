package com.keiranhaas.auralcam.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Size
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

object CameraEnumerator {
    private const val TAG = "AuralCam"

    fun enumerate(manager: CameraManager): List<CameraCaps> {
        val result = mutableListOf<CameraCaps>()
        for (id in manager.cameraIdList) {
            val ch = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                continue
            }
            Log.i(TAG, "camera id=$id facing=${ch.get(CameraCharacteristics.LENS_FACING)} physical=${ch.physicalCameraIds}")
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue

            val capList = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capList) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            if (map.getOutputSizes(ImageFormat.JPEG).isNullOrEmpty()) continue

            val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capList
            val manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capList
            val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capList &&
                !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()

            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val equiv = if (sensor != null && focal > 0f) focal * 43.267f / hypot(sensor.width, sensor.height) else focal
            val kind = when {
                equiv < 20f -> "Ultra-wide"
                equiv < 40f -> "Wide"
                else -> "Tele"
            }

            val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
            val ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val zoom = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty()

            result += CameraCaps(
                id = id,
                label = "$kind ${equiv.roundToInt()}mm (id $id)",
                focalLengthMm = focal,
                equivFocalMm = equiv,
                sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000),
                manualSensor = manualSensor,
                manualPostProcessing = manualPost,
                raw = raw,
                isoMin = iso?.lower ?: 100,
                isoMax = iso?.upper ?: 100,
                exposureMinNs = exp?.lower ?: 16_666_667L,
                exposureMaxNs = exp?.upper ?: 16_666_667L,
                minFocusDiopters = minFocus,
                hasAutofocus = minFocus > 0f && afModes.any { it != CameraMetadata.CONTROL_AF_MODE_OFF },
                evMin = ev?.lower ?: 0,
                evMax = ev?.upper ?: 0,
                evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
                zoomMin = zoom?.lower ?: 1f,
                zoomMax = zoom?.upper ?: 1f,
                hasOis = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in ois,
                maxAeRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                maxAfRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                exactKelvin = manualPost && ColorMath.solve(ch, 5500) != null,
                physicalIds = ch.physicalCameraIds,
                nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet().orEmpty(),
                edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty(),
                hotPixelModes = ch.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toSet().orEmpty(),
                aberrationModes = ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)?.toSet().orEmpty(),
                distortionModes = ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.toSet().orEmpty(),
                toneModes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
            )
        }
        return result.sortedBy { it.equivFocalMm }
    }
}

data class OutputSizes(val preview: Size, val analysis: Size, val jpeg: Size, val raw: Size?)

object OutputPlanner {
    private fun Size.area() = width.toLong() * height

    fun plan(ch: CameraCharacteristics): OutputSizes? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.area() } ?: return null
        val aspect = jpeg.width.toFloat() / jpeg.height
        fun sameAspect(s: Size) = abs(s.width.toFloat() / s.height - aspect) < 0.02f

        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toList()
        val preview = previewSizes.filter { sameAspect(it) && it.width <= 1920 }.maxByOrNull { it.area() }
            ?: previewSizes.filter { it.width <= 1920 }.maxByOrNull { it.area() }
            ?: previewSizes.minByOrNull { it.area() }
            ?: return null

        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toList()
        val analysis = yuvSizes.filter { sameAspect(it) && it.width <= 960 }.maxByOrNull { it.area() }
            ?: yuvSizes.filter { it.width <= 960 }.maxByOrNull { it.area() }
            ?: yuvSizes.minByOrNull { it.area() }
            ?: return null

        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.area() }
        return OutputSizes(preview, analysis, jpeg, raw)
    }
}