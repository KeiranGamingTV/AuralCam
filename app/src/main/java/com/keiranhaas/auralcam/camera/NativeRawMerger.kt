package com.keiranhaas.auralcam.camera

import com.keiranhaas.auralcam.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * C++-backed [RawFrameMerger]. One instance per low-light capture, used from a single thread
 * (the coordinator's save worker). Pass `{ NativeRawMerger() }` as CameraController's mergerFactory.
 */
class NativeRawMerger : RawFrameMerger {
    private var handle = 0L
    private var width = 0
    private var height = 0
    private var rowStrideBytes = 0

    // The merge works on 2x2 Bayer blocks, so it is independent of the CFA arrangement.
    override fun begin(width: Int, height: Int, rowStrideBytes: Int, cfaArrangement: Int, frameCount: Int): Boolean {
        abort()
        handle = NativeBridge.mergeBegin(width, height, frameCount)
        if (handle == 0L) return false
        this.width = width
        this.height = height
        this.rowStrideBytes = rowStrideBytes
        return true
    }

    override fun add(raw: ByteBuffer, frameIndex: Int) {
        if (handle != 0L) NativeBridge.mergeAdd(handle, raw, rowStrideBytes)
    }

    override fun finish(): ByteBuffer? {
        if (handle == 0L) return null
        return try {
            // Allocated on the Java side so the GC owns it: it stays valid while DngCreator writes it.
            val out = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
            if (NativeBridge.mergeFinish(handle, out)) out else null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            NativeBridge.mergeDestroy(handle)
            handle = 0L
        }
    }

    override fun abort() {
        if (handle != 0L) {
            NativeBridge.mergeDestroy(handle)
            handle = 0L
        }
    }
}