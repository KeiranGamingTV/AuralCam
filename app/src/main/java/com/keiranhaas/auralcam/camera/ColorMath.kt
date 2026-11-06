package com.keiranhaas.auralcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import kotlin.math.roundToInt

/**
 * Turns a colour temperature into the gains + 3x3 matrix Camera2 needs for manual white balance,
 * using the sensor's own DNG-style calibration data (ColorMatrix / ForwardMatrix).
 */
object ColorMath {
    class WbSolution(val gains: RggbChannelVector, val transform: ColorSpaceTransform)

    private val XYZ_D50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427
    )

    fun solve(ch: CameraCharacteristics, kelvin: Int): WbSolution? {
        val cm1 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toDoubles() ?: return null
        val fm1 = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toDoubles() ?: return null
        val cm2 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toDoubles()
        val fm2 = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toDoubles()
        val t1 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.let { illuminantToKelvin(it) }
        val t2 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.let { illuminantToKelvin(it.toInt()) }

        val k = kelvin.coerceIn(1700, 25000).toDouble()
        val w = if (cm2 != null && fm2 != null && t1 != null && t2 != null && t1 != t2) {
            val mk = 1e6 / k
            val m1 = 1e6 / t1
            val m2 = 1e6 / t2
            ((mk - m1) / (m2 - m1)).coerceIn(0.0, 1.0)
        } else 0.0

        val cm = lerp(cm1, cm2 ?: cm1, w)   // XYZ -> camera native
        val fm = lerp(fm1, fm2 ?: fm1, w)   // white-balanced camera -> XYZ D50

        val (x, y) = planckianXy(k)
        val xyz = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
        val neutral = mul(cm, xyz)          // what the sensor sees for a white at this temperature
        if (neutral.any { it <= 1e-6 }) return null

        val gains = RggbChannelVector(
            (neutral[1] / neutral[0]).toFloat(), 1f, 1f, (neutral[1] / neutral[2]).toFloat()
        )
        val t = matMul(XYZ_D50_TO_SRGB, fm)
        val transform = ColorSpaceTransform(Array(9) { Rational((t[it] * 10000.0).roundToInt(), 10000) })
        return WbSolution(gains, transform)
    }

    private fun ColorSpaceTransform.toDoubles(): DoubleArray {
        val dst = Array(9) { Rational.ZERO }
        copyElements(dst, 0)
        return DoubleArray(9) { dst[it].toDouble() }
    }

    private fun lerp(a: DoubleArray, b: DoubleArray, w: Double) = DoubleArray(9) { a[it] * (1 - w) + b[it] * w }

    private fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r ->
        m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
    }

    private fun matMul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** Planckian locus approximation (Kim et al.), valid 1667K..25000K. */
    private fun planckianXy(t: Double): Pair<Double, Double> {
        val x = if (t <= 4000.0) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    /** EXIF/DNG LightSource codes -> approximate CCT. */
    private fun illuminantToKelvin(code: Int): Double? = when (code) {
        1 -> 5500.0   // daylight
        2 -> 4200.0   // fluorescent
        3 -> 2850.0   // tungsten
        4 -> 5500.0   // flash
        9 -> 5500.0   // fine weather
        10 -> 6500.0  // cloudy
        11 -> 7500.0  // shade
        12 -> 6500.0  // daylight fluorescent
        13 -> 5000.0  // day white fluorescent
        14 -> 4150.0  // cool white fluorescent
        15 -> 3500.0  // white fluorescent
        17 -> 2856.0  // standard A
        18 -> 4874.0  // standard B
        19 -> 6774.0  // standard C
        20 -> 5503.0  // D55
        21 -> 6504.0  // D65
        22 -> 7504.0  // D75
        23 -> 5003.0  // D50
        24 -> 3200.0  // ISO studio tungsten
        else -> null
    }
}