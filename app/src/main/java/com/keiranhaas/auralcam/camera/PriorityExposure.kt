package com.keiranhaas.auralcam.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Camera2 has no shutter/ISO priority. This is a small feedback loop: the host feeds it the mean
 * luma of the metered area (0..1, display-encoded) and it moves the *free* parameter toward a
 * mid-grey target, shifted by the exposure-compensation setting.
 */
class PriorityExposureController(
    private val isoMin: Int,
    private val isoMax: Int,
    private val exposureMinNs: Long,
    exposureMaxNs: Long,
    loopMaxExposureNs: Long = 100_000_000L
) {
    data class Solution(val iso: Int, val exposureNs: Long, val atLimit: Boolean)

    // Longest exposure the loop will pick on its own, so the preview stays usable.
    private val exposureCeilingNs = max(exposureMinNs, min(exposureMaxNs, loopMaxExposureNs))

    fun step(mode: ExposureMode, iso: Int, exposureNs: Long, meanLuma: Float, evStops: Float): Solution? {
        if (mode != ExposureMode.SHUTTER_PRIORITY && mode != ExposureMode.ISO_PRIORITY) return null
        val measured = meanLuma.coerceIn(0.01f, 1f).toDouble().pow(GAMMA)
        val target = TARGET_LUMA.pow(GAMMA) * 2.0.pow(evStops.toDouble())
        val errStops = ln(target / measured) / ln(2.0)
        if (abs(errStops) < DEADBAND_STOPS) return null
        val factor = 2.0.pow((errStops * GAIN).coerceIn(-MAX_STEP_STOPS, MAX_STEP_STOPS))

        return if (mode == ExposureMode.SHUTTER_PRIORITY) {
            val desired = (iso * factor).roundToInt()
            val clamped = desired.coerceIn(isoMin, isoMax)
            Solution(clamped, exposureNs, atLimit = clamped != desired)
        } else {
            val desired = (exposureNs * factor).roundToLong()
            val clamped = desired.coerceIn(exposureMinNs, exposureCeilingNs)
            Solution(iso, clamped, atLimit = clamped != desired)
        }
    }

    private companion object {
        const val GAMMA = 2.2
        val TARGET_LUMA = 0.46          // sRGB value of 18% grey
        const val DEADBAND_STOPS = 0.07
        const val GAIN = 0.6
        const val MAX_STEP_STOPS = 1.0
    }
}