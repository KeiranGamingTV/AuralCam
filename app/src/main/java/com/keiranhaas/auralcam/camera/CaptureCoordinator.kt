package com.keiranhaas.auralcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.util.Size
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class CaptureJob(
    val id: Long,
    val baseName: String,
    val wantJpeg: Boolean,
    val wantRaw: Boolean,
    val orientationDeg: Int,
    val group: LowLightGroup? = null
)

/** State of one low-light capture. Touched only on the coordinator's worker thread (except construction). */
class LowLightGroup(
    val total: Int,
    val merger: RawFrameMerger,
    val baseName: String,
    val orientationDeg: Int
) {
    var done = 0
    var failed = 0
    var begun = false
    var finished = false
    var width = 0
    var height = 0
    var firstResult: TotalCaptureResult? = null
}

/**
 * Camera2 delivers images and capture results separately. This pairs them by sensor timestamp,
 * then writes JPEG / DNG on a worker thread so the camera thread never blocks on disk.
 */
class CaptureCoordinator(
    private val chars: CameraCharacteristics,
    private val saver: MediaSaver,
    private val cfaArrangement: Int,
    private val onSaved: (Uri) -> Unit,
    private val onError: (String) -> Unit,
    private val onJobDone: () -> Unit
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "AuralCam-Save") }
    private val lock = Any()
    private val jpegs = HashMap<Long, Image>()
    private val raws = HashMap<Long, Image>()
    private val results = HashMap<Long, Pair<CaptureJob, TotalCaptureResult>>()
    private val groups = mutableListOf<LowLightGroup>()
    private var released = false

    fun register(group: LowLightGroup) = synchronized(lock) { groups += group }

    fun onJpeg(image: Image) = onImage(jpegs, image)
    fun onRaw(image: Image) = onImage(raws, image)

    private fun onImage(map: HashMap<Long, Image>, image: Image) {
        synchronized(lock) {
            if (released) {
                image.close()
                return
            }
            map.put(image.timestamp, image)?.close()
            tryComplete(image.timestamp)
        }
    }

    fun onResult(job: CaptureJob, result: TotalCaptureResult) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        synchronized(lock) {
            if (released) return
            results[ts] = job to result
            tryComplete(ts)
            if (results.size > MAX_PENDING_RESULTS) {
                // A result whose image never arrived: drop the oldest so nothing leaks.
                val oldest = results.keys.minOrNull()
                if (oldest != null) {
                    val (staleJob, _) = results.remove(oldest)!!
                    jpegs.remove(oldest)?.close()
                    raws.remove(oldest)?.close()
                    submit { handleFailure(staleJob) }
                }
            }
        }
    }

    fun onFailed(job: CaptureJob) {
        synchronized(lock) { if (released) return }
        submit { handleFailure(job) }
    }

    /** Must be called with [lock] held. */
    private fun tryComplete(ts: Long) {
        val (job, result) = results[ts] ?: return
        val jpeg = if (job.wantJpeg) (jpegs[ts] ?: return) else null
        val raw = if (job.wantRaw) (raws[ts] ?: return) else null
        results.remove(ts)
        jpegs.remove(ts)
        raws.remove(ts)
        submit { process(job, result, jpeg, raw) }
    }

    private fun submit(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (e: RejectedExecutionException) {
            // Released: nothing left to do.
        }
    }

    private fun process(job: CaptureJob, result: TotalCaptureResult, jpeg: Image?, raw: Image?) {
        try {
            val group = job.group
            if (group != null) {
                if (raw != null) processGroupFrame(group, result, raw)
                return
            }
            var uri: Uri? = null
            if (jpeg != null) {
                val buf = jpeg.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                uri = saver.saveJpeg(job.baseName, bytes)
            }
            if (raw != null) {
                DngCreator(chars, result).use { dng ->
                    dng.setOrientation(exifOrientation(job.orientationDeg))
                    uri = saver.saveDng(job.baseName) { out -> dng.writeImage(out, raw) }
                }
            }
            uri?.let(onSaved)
        } catch (e: Exception) {
            onError("Save failed: ${e.message}")
        } finally {
            jpeg?.close()
            raw?.close()
            onJobDone()
        }
    }

    private fun processGroupFrame(g: LowLightGroup, result: TotalCaptureResult, raw: Image) {
        if (!g.finished) {
            val plane = raw.planes[0]
            if (!g.begun && g.done == 0) {
                g.begun = g.merger.begin(raw.width, raw.height, plane.rowStride, cfaArrangement, g.total)
                g.width = raw.width
                g.height = raw.height
                g.firstResult = result
            }
            if (g.begun) {
                g.merger.add(plane.buffer, g.done)
                g.done++
            } else {
                g.failed++
            }
        }
        maybeFinishGroup(g)
    }

    private fun handleFailure(job: CaptureJob) {
        val g = job.group
        if (g != null) {
            g.failed++
            maybeFinishGroup(g)
        } else {
            onError("The camera dropped a frame")
        }
        onJobDone()
    }

    private fun maybeFinishGroup(g: LowLightGroup) {
        if (g.finished || g.done + g.failed < g.total) return
        g.finished = true
        synchronized(lock) { groups.remove(g) }
        if (!g.begun || g.done == 0) {
            g.merger.abort()
            onError("Low-light capture failed")
            return
        }
        try {
            val merged = g.merger.finish()
            val first = g.firstResult
            if (merged == null || first == null) {
                onError("Low-light merge produced no output")
                return
            }
            DngCreator(chars, first).use { dng ->
                dng.setOrientation(exifOrientation(g.orientationDeg))
                val uri = saver.saveDng(g.baseName) { out ->
                    dng.writeByteBuffer(out, Size(g.width, g.height), merged, 0L)
                }
                uri?.let(onSaved)
            }
        } catch (e: Exception) {
            g.merger.abort()
            onError("Low-light save failed: ${e.message}")
        }
    }

    /**
     * Drops anything still waiting, aborts unfinished merges, then runs [then] on the worker after every
     * queued save has finished. Callers close their ImageReaders in [then] so in-flight images stay valid.
     */
    fun release(then: () -> Unit) {
        val unfinished: List<LowLightGroup>
        synchronized(lock) {
            released = true
            jpegs.values.forEach { it.close() }
            raws.values.forEach { it.close() }
            jpegs.clear()
            raws.clear()
            results.clear()
            unfinished = groups.toList()
            groups.clear()
        }
        try {
            worker.execute {
                unfinished.filter { !it.finished }.forEach {
                    it.finished = true
                    it.merger.abort()
                }
                then()
            }
            worker.shutdown()
        } catch (e: RejectedExecutionException) {
            then()
        }
    }

    private fun exifOrientation(deg: Int): Int = when (((deg % 360) + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    private companion object {
        const val MAX_PENDING_RESULTS = 16
    }
}