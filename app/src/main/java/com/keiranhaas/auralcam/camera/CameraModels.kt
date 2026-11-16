package com.keiranhaas.auralcam.camera

import android.graphics.Rect
import android.net.Uri
import android.util.Size

enum class ExposureMode { AUTO, MANUAL, SHUTTER_PRIORITY, ISO_PRIORITY }
enum class FocusMode { AUTO_CONTINUOUS, AUTO_TOUCH, MANUAL }
enum class WhiteBalanceMode { AUTO, KELVIN }
enum class CaptureFormat { JPEG, DNG, DNG_JPEG }
enum class ProcessingProfile { MINIMAL, STANDARD }
enum class DriveMode { SINGLE, BURST, LOW_LIGHT }

/** A point in the displayed preview, normalised 0..1 (x right, y down), upright as the user sees it. */
data class NormPoint(val x: Float, val y: Float)

data class CameraCaps(
    val id: String,
    val label: String,
    val focalLengthMm: Float,
    val equivFocalMm: Float,
    val sensorOrientation: Int,
    val activeArray: Rect,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val raw: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    val minFocusDiopters: Float,
    val hasAutofocus: Boolean,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    val zoomMin: Float,
    val zoomMax: Float,
    val hasOis: Boolean,
    val maxAeRegions: Int,
    val maxAfRegions: Int,
    val exactKelvin: Boolean,
    val physicalIds: Set<String>
)

data class CameraSettings(
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val iso: Int = 100,
    val exposureTimeNs: Long = 16_666_667L,
    val evIndex: Int = 0,
    val aeLocked: Boolean = false,
    val aePoint: NormPoint? = null,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val kelvin: Int = 5500,
    val focusMode: FocusMode = FocusMode.AUTO_CONTINUOUS,
    val focusDiopters: Float = 0f,
    val afPoint: NormPoint? = null,
    val zoomRatio: Float = 1f,
    val ois: Boolean = true,
    val format: CaptureFormat = CaptureFormat.DNG_JPEG,
    val processing: ProcessingProfile = ProcessingProfile.MINIMAL,
    val jpegQuality: Int = 100,
    val drive: DriveMode = DriveMode.SINGLE,
    val burstCount: Int = 8,
    val lowLightFrames: Int = 8
)

/** Low-light mode captures RAW only and writes one merged DNG. */
fun CameraSettings.needsJpeg() = drive != DriveMode.LOW_LIGHT && format != CaptureFormat.DNG
fun CameraSettings.needsRaw() = drive == DriveMode.LOW_LIGHT || format != CaptureFormat.JPEG

fun CameraSettings.clampedTo(caps: CameraCaps, lowLightAvailable: Boolean): CameraSettings {
    var s = this
    if (!caps.manualSensor && s.exposureMode != ExposureMode.AUTO) s = s.copy(exposureMode = ExposureMode.AUTO)
    if (!caps.manualSensor && s.focusMode == FocusMode.MANUAL) s = s.copy(focusMode = FocusMode.AUTO_CONTINUOUS)
    if (!caps.raw && s.format != CaptureFormat.JPEG) s = s.copy(format = CaptureFormat.JPEG)
    if (s.drive == DriveMode.LOW_LIGHT && !(caps.raw && lowLightAvailable)) s = s.copy(drive = DriveMode.SINGLE)
    return s.copy(
        iso = s.iso.coerceIn(caps.isoMin, caps.isoMax),
        exposureTimeNs = s.exposureTimeNs.coerceIn(caps.exposureMinNs, caps.exposureMaxNs),
        focusDiopters = s.focusDiopters.coerceIn(0f, caps.minFocusDiopters),
        evIndex = s.evIndex.coerceIn(caps.evMin, caps.evMax),
        zoomRatio = s.zoomRatio.coerceIn(caps.zoomMin, caps.zoomMax),
        burstCount = s.burstCount.coerceIn(2, 30),
        lowLightFrames = s.lowLightFrames.coerceIn(3, 16),
        jpegQuality = s.jpegQuality.coerceIn(50, 100)
    )
}

data class LiveReadout(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDiopters: Float? = null,
    val afState: Int? = null,
    val aeState: Int? = null
)

data class CameraState(
    val lenses: List<CameraCaps> = emptyList(),
    val active: CameraCaps? = null,
    val settings: CameraSettings = CameraSettings(),
    val live: LiveReadout = LiveReadout(),
    val previewSize: Size? = null,
    val sessionReady: Boolean = false,
    val busy: Boolean = false,
    val exposureAtLimit: Boolean = false,
    val analysisAvailable: Boolean = true,
    val lowLightAvailable: Boolean = false,
    val lastSavedUri: Uri? = null,
    val shutterTick: Int = 0,
    val message: String? = null
)