package com.keiranhaas.auralcam.camera

import android.media.Image
import java.nio.ByteBuffer

/** Receives small YUV frames for the histogram / peaking / zebra analysis (implemented in the NDK batch). */
fun interface FrameSink {
    /** Called on the analysis thread. The image is closed by the caller; do not keep it. */
    fun onFrame(image: Image)
}

/**
 * Streaming multi-frame RAW merge. Implemented in C++ in the NDK batch.
 * Frames are fed one at a time so memory stays at roughly one reference frame plus one accumulator.
 */
interface RawFrameMerger {
    /** @return false if the geometry/CFA is unsupported. */
    fun begin(width: Int, height: Int, rowStrideBytes: Int, cfaArrangement: Int, frameCount: Int): Boolean

    /** [raw] is plane 0 of a RAW_SENSOR image (16-bit samples, native byte order). */
    fun add(raw: ByteBuffer, frameIndex: Int)

    /** Merged RAW as a direct buffer, tightly packed (stride = width * 2), native byte order. Releases native state. */
    fun finish(): ByteBuffer?

    /** Releases native state without producing output. Must be safe to call after finish(). */
    fun abort()
}