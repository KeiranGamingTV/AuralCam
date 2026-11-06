package com.keiranhaas.auralcam

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** The only place that talks to the C++ library. */
object NativeBridge {
    init {
        System.loadLibrary("auralcam_native")
    }

    @JvmStatic external fun nativeVersion(): String

    /**
     * Analyses one YUV_420_888 frame.
     *
     * @param rotationDeg clockwise rotation that turns the sensor image upright on screen
     *                    (sensorOrientation - displayRotation). Histograms are unaffected; the overlay
     *                    bitmap and meter rectangle are in this upright orientation.
     * @param meterRect   left, top, right, bottom in 0..1 of the upright image
     * @param overlay     ARGB_8888 bitmap sized for the rotated frame, or null to skip overlays
     * @param outHist     1024 ints: luma[0..255], R, G, B
     * @param outStats    >= 8 floats: meterLuma, highlightClip%, shadowClip%, meanLuma, peaking%
     */
    @JvmStatic external fun analyzeYuv(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        rotationDeg: Int,
        peaking: Boolean, peakThreshold: Int, peakColor: Int,
        zebra: Boolean, zebraThreshold: Int, zebraPhase: Int,
        meterRect: FloatArray,
        overlay: Bitmap?,
        outHist: IntArray,
        outStats: FloatArray
    ): Boolean

    @JvmStatic external fun mergeBegin(width: Int, height: Int, frames: Int): Long
    @JvmStatic external fun mergeAdd(handle: Long, raw: ByteBuffer, rowStrideBytes: Int): Boolean
    @JvmStatic external fun mergeFinish(handle: Long, out: ByteBuffer): Boolean
    @JvmStatic external fun mergeDestroy(handle: Long)
}