package com.keiranhaas.auralcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor
import kotlin.math.abs
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Owns the Camera2 device and session. All mutation runs on one camera thread; the public API is safe
 * to call from the UI thread. UI observes [state].
 */
@SuppressLint("MissingPermission")
class CameraController(
    private val context: Context,
    private val mergerFactory: (() -> RawFrameMerger)? = null
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("AuralCam-Camera").apply { start() }
    private val handler = Handler(cameraThread.looper)
    private val analysisThread = HandlerThread("AuralCam-Analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)
    private val executor = Executor { handler.post(it) }
    private val saver = MediaSaver(context)

    private val _state = MutableStateFlow(CameraState(lowLightAvailable = mergerFactory != null))
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** Set by the analysis layer (NDK batch). */
    @Volatile var analysisSink: FrameSink? = null

    @Volatile private var displayRotationDeg = 0
    @Volatile private var deviceOrientationDeg = 0
    @Volatile private var started = false

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var factory: RequestFactory? = null
    private var priority: PriorityExposureController? = null
    private var outputs: OutputSizes? = null
    private var coordinator: CaptureCoordinator? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var analysisReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var sessionGen = 0
    /** False once the lens refused to run the analysis stream next to the capture outputs. */
    private var useAnalysis = true
    private var framesSinceChange = 0
    private var lastLiveMs = 0L
    private var jobCounter = 0L
    private var pendingJobs = 0

    /** Bumped to cancel a running self-timer countdown or intervalometer. */
    private var timerToken = 0

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() = post {
        started = true
        if (_state.value.lenses.isEmpty()) {
            _state.update { it.copy(lenses = CameraEnumerator.enumerate(manager)) }
        }
        val lenses = _state.value.lenses
        if (lenses.isEmpty()) {
            message("No back cameras found")
            return@post
        }
        val id = _state.value.active?.id
            ?: lenses.minByOrNull { abs(it.equivFocalMm - 24f) }!!.id
        openCameraLocked(id)
    }

    fun stop() {
        started = false
        post { closeCameraLocked() }
    }

    fun release() {
        started = false
        handler.post {
            closeCameraLocked()
            cameraThread.quitSafely()
            analysisThread.quitSafely()
        }
    }

    /** The TextureView's SurfaceTexture. The controller sizes its buffers itself. */
    fun setPreviewTexture(texture: SurfaceTexture?) = post {
        previewTexture = texture
        if (texture == null) {
            closeSessionLocked()
        } else if (device != null && session == null) {
            createSessionLocked()
        }
    }

    fun setDisplayRotation(degrees: Int) { displayRotationDeg = degrees }

    /** Device orientation from an OrientationEventListener, in degrees (rounded to 0/90/180/270 here). */
    fun setDeviceOrientation(degrees: Int) {
        deviceOrientationDeg = (((degrees + 45) / 90) * 90) % 360
    }

    fun selectLens(id: String) = post {
        if (_state.value.active?.id == id && device != null) return@post
        openCameraLocked(id)
    }

    fun consumeMessage() { _state.update { it.copy(message = null) } }

    // ---------------------------------------------------------------- settings

    fun update(transform: (CameraSettings) -> CameraSettings) = post {
        val st = _state.value
        val caps = st.active ?: return@post
        val old = st.settings
        var new = transform(old)

        // Leaving AUTO: start from what the camera was actually doing so nothing jumps.
        if (old.exposureMode == ExposureMode.AUTO && new.exposureMode != ExposureMode.AUTO) {
            st.live.iso?.let { if (new.iso == old.iso) new = new.copy(iso = it) }
            st.live.exposureTimeNs?.let { if (new.exposureTimeNs == old.exposureTimeNs) new = new.copy(exposureTimeNs = it) }
        }
        new = new.clampedTo(caps, mergerFactory != null)
        _state.update { it.copy(settings = new, exposureAtLimit = false) }

        val planChanged = old.needsJpeg() != new.needsJpeg() || old.needsRaw() != new.needsRaw()
        if (planChanged) {
            useAnalysis = true   // a changed output plan restarts the fallback chain
            createSessionLocked()
        } else {
            applyRepeatingLocked()
        }
    }

    fun tapToFocus(point: NormPoint) = post {
        val caps = _state.value.active ?: return@post
        if (!caps.hasAutofocus) return@post
        _state.update { it.copy(settings = it.settings.copy(focusMode = FocusMode.AUTO_TOUCH, afPoint = point)) }
        applyRepeatingLocked()
        triggerAutofocusLocked()
    }

    fun clearFocusPoint() = update { it.copy(focusMode = FocusMode.AUTO_CONTINUOUS, afPoint = null) }

    /** Separate from focus: in AUTO this sets the AE region, in priority modes it sets the metered area. */
    fun setExposurePoint(point: NormPoint?) = update { it.copy(aePoint = point) }

    /**
     * Feed the mean luma (0..1) of the metered area here, once per analysis frame.
     * Only used in SHUTTER_PRIORITY / ISO_PRIORITY.
     */
    fun onMeteredLuma(meanLuma: Float) = post {
        val st = _state.value
        val s = st.settings
        val caps = st.active ?: return@post
        if (s.exposureMode != ExposureMode.SHUTTER_PRIORITY && s.exposureMode != ExposureMode.ISO_PRIORITY) return@post
        if (framesSinceChange < SETTLE_FRAMES) return@post
        val sol = priority?.step(s.exposureMode, s.iso, s.exposureTimeNs, meanLuma, s.evIndex * caps.evStep)
            ?: return@post
        _state.update { it.copy(exposureAtLimit = sol.atLimit) }
        if (sol.iso == s.iso && sol.exposureNs == s.exposureTimeNs) return@post
        _state.update { it.copy(settings = s.copy(iso = sol.iso, exposureTimeNs = sol.exposureNs)) }
        applyRepeatingLocked()
    }

    // ---------------------------------------------------------------- capture

    /**
     * The public shutter. Honours the self-timer; pressing it again cancels a running countdown or
     * intervalometer. Volume keys and remotes call this too.
     */
    fun capture() = post {
        val st = _state.value
        when {
            st.intervalRunning -> stopIntervalLocked()
            st.countdown > 0 -> cancelTimersLocked()
            st.settings.timerSeconds > 0 && st.sessionReady -> startCountdownLocked(st.settings.timerSeconds)
            else -> captureNowLocked()
        }
    }

    /** Fires [settings.intervalCount] shots, one every [settings.intervalSeconds] seconds. */
    fun startIntervalometer() = post {
        val st = _state.value
        if (!st.sessionReady || st.intervalRunning || st.countdown > 0) return@post
        val s = st.settings
        val token = ++timerToken
        _state.update { it.copy(intervalRunning = true, intervalShot = 0) }

        fun shoot(n: Int) {
            if (token != timerToken) return
            if (pendingJobs > 0) {                 // previous frame still being written
                handler.postDelayed({ shoot(n) }, 300)
                return
            }
            captureNowLocked()
            _state.update { it.copy(intervalShot = n + 1) }
            if (n + 1 >= s.intervalCount) {
                stopIntervalLocked()
            } else {
                handler.postDelayed({ shoot(n + 1) }, s.intervalSeconds * 1000L)
            }
        }
        shoot(0)
    }

    private fun startCountdownLocked(seconds: Int) {
        val token = ++timerToken
        fun tick(left: Int) {
            if (token != timerToken) return
            _state.update { it.copy(countdown = left) }
            if (left <= 0) {
                captureNowLocked()
            } else {
                handler.postDelayed({ tick(left - 1) }, 1000)
            }
        }
        tick(seconds)
    }

    private fun stopIntervalLocked() {
        timerToken++
        _state.update { it.copy(intervalRunning = false) }
    }

    private fun cancelTimersLocked() {
        timerToken++
        _state.update { it.copy(countdown = 0, intervalRunning = false) }
    }

    private fun captureNowLocked() {
        val dev = device ?: return
        val sess = session ?: return
        val fac = factory ?: return
        val caps = _state.value.active ?: return
        val coord = coordinator ?: return
        val s = _state.value.settings
        if (pendingJobs > 0 && s.drive != DriveMode.SINGLE) return

        val orientation = (caps.sensorOrientation + deviceOrientationDeg + 360) % 360
        val base = saver.newBaseName()
        try {
            when (s.drive) {
                DriveMode.SINGLE -> {
                    val job = CaptureJob(++jobCounter, base, s.needsJpeg(), s.needsRaw(), orientation)
                    val request = buildStill(dev, fac, s, job) ?: return
                    pendingJobs += 1
                    sess.capture(request, stillCallback, handler)
                }
                DriveMode.BURST -> {
                    val jobs = (1..s.burstCount).map { i ->
                        CaptureJob(++jobCounter, "${base}_${"%02d".format(i)}", s.needsJpeg(), s.needsRaw(), orientation)
                    }
                    val requests = jobs.mapNotNull { buildStill(dev, fac, s, it) }
                    if (requests.size != jobs.size) return
                    pendingJobs += jobs.size
                    sess.captureBurst(requests, stillCallback, handler)
                }
                DriveMode.BRACKET -> {
                    val frames = bracketSettings(s, caps, _state.value.live)
                    val jobs = frames.map { (label, frameSettings) ->
                        CaptureJob(++jobCounter, "${base}_EV$label", s.needsJpeg(), s.needsRaw(), orientation) to frameSettings
                    }
                    val requests = jobs.mapNotNull { (job, frameSettings) -> buildStill(dev, fac, frameSettings, job) }
                    if (requests.size != jobs.size) return
                    pendingJobs += jobs.size
                    sess.captureBurst(requests, stillCallback, handler)
                }
                DriveMode.LOW_LIGHT -> {
                    val newMerger = mergerFactory ?: run {
                        message("Low-light mode is not available yet")
                        return
                    }
                    // Every frame must share one exposure or the average would be wrong.
                    val live = _state.value.live
                    val fixed = if (s.exposureMode == ExposureMode.AUTO) {
                        s.copy(
                            exposureMode = ExposureMode.MANUAL,
                            iso = live.iso ?: s.iso,
                            exposureTimeNs = live.exposureTimeNs ?: s.exposureTimeNs
                        )
                    } else s
                    val group = LowLightGroup(s.lowLightFrames, newMerger(), base, orientation)
                    coord.register(group)
                    val jobs = List(group.total) {
                        CaptureJob(++jobCounter, base, wantJpeg = false, wantRaw = true, orientationDeg = orientation, group = group)
                    }
                    val requests = jobs.mapNotNull { buildStill(dev, fac, fixed, it) }
                    if (requests.size != jobs.size) {
                        group.merger.abort()
                        return
                    }
                    pendingJobs += jobs.size
                    sess.captureBurst(requests, stillCallback, handler)
                }
            }
            publishBusy()
        } catch (e: CameraAccessException) {
            pendingJobs = 0
            publishBusy()
            message("Capture failed: ${e.message}")
        } catch (e: IllegalStateException) {
            pendingJobs = 0
            publishBusy()
            message("Capture failed: ${e.message}")
        }
    }

    /**
     * One entry per bracket frame, centred on the current exposure. The shutter moves first; whatever
     * the sensor's shutter range can't absorb goes into ISO. In AUTO the live metered values are the centre.
     */
    private fun bracketSettings(s: CameraSettings, caps: CameraCaps, live: LiveReadout): List<Pair<String, CameraSettings>> {
        val auto = s.exposureMode == ExposureMode.AUTO
        val baseIso = if (auto) live.iso ?: s.iso else s.iso
        val baseNs = if (auto) live.exposureTimeNs ?: s.exposureTimeNs else s.exposureTimeNs
        val n = s.bracketFrames
        return (0 until n).map { i ->
            val ev = (i - (n - 1) / 2f) * s.bracketStepEv
            val wantNs = baseNs * 2.0.pow(ev.toDouble())
            val ns = wantNs.roundToLong().coerceIn(caps.exposureMinNs, caps.exposureMaxNs)
            val iso = (baseIso * (wantNs / ns)).roundToInt().coerceIn(caps.isoMin, caps.isoMax)
            String.format(Locale.US, "%+.1f", ev) to
                s.copy(exposureMode = ExposureMode.MANUAL, iso = iso, exposureTimeNs = ns)
        }
    }

    private fun buildStill(dev: CameraDevice, fac: RequestFactory, s: CameraSettings, job: CaptureJob): CaptureRequest? {
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        var targets = 0
        if (job.wantJpeg) jpegReader?.surface?.let { b.addTarget(it); targets++ }
        if (job.wantRaw) rawReader?.surface?.let { b.addTarget(it); targets++ }
        val wanted = (if (job.wantJpeg) 1 else 0) + (if (job.wantRaw) 1 else 0)
        if (targets == 0 || targets != wanted) {
            message("Capture outputs aren't ready")
            return null
        }
        fac.apply(b, s, displayRotationDeg, still = true, jpegOrientationDeg = job.orientationDeg)
        b.setTag(job)
        return b.build()
    }

    private fun publishBusy() {
        _state.update { it.copy(busy = pendingJobs > 0) }
    }

    // ---------------------------------------------------------------- device + session

    private fun openCameraLocked(id: String) {
        closeCameraLocked()
        val ch = try {
            manager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            message("Cannot read camera $id")
            return
        }
        val caps = _state.value.lenses.firstOrNull { it.id == id } ?: return
        val plan = OutputPlanner.plan(ch) ?: run {
            message("No usable output sizes for camera $id")
            return
        }
        chars = ch
        factory = RequestFactory(ch, caps)
        priority = PriorityExposureController(caps.isoMin, caps.isoMax, caps.exposureMinNs, caps.exposureMaxNs)
        outputs = plan
        useAnalysis = true
        _state.update {
            it.copy(
                active = caps,
                previewSize = plan.preview,
                settings = it.settings.copy(afPoint = null, aePoint = null).clampedTo(caps, mergerFactory != null),
                sessionReady = false
            )
        }
        try {
            manager.openCamera(id, deviceCallback, handler)
        } catch (e: Exception) {
            message("Cannot open camera $id: ${e.message}")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!started) {
                camera.close()
                return
            }
            device = camera
            createSessionLocked()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) {
                device = null
                closeSessionLocked()
            }
            message("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (device === camera) {
                device = null
                closeSessionLocked()
            }
            message("Camera error $error")
        }
    }

    private fun createSessionLocked() {
        val dev = device ?: return
        val texture = previewTexture ?: return
        val out = outputs ?: return
        val ch = chars ?: return
        closeSessionLocked()

        val s = _state.value.settings
        val needJpeg = s.needsJpeg()
        val needRaw = s.needsRaw() && out.raw != null

        texture.setDefaultBufferSize(out.preview.width, out.preview.height)
        val surface = Surface(texture)
        previewSurface = surface
        val configs = mutableListOf(OutputConfiguration(surface))

        if (useAnalysis) {
            val analysis = ImageReader.newInstance(out.analysis.width, out.analysis.height, ImageFormat.YUV_420_888, 3)
            analysis.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null }
                if (img != null) {
                    try { analysisSink?.onFrame(img) } finally { img.close() }
                }
            }, analysisHandler)
            analysisReader = analysis
            configs += OutputConfiguration(analysis.surface)
        }
        _state.update { it.copy(analysisAvailable = useAnalysis) }

        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val coord = CaptureCoordinator(
            chars = ch,
            saver = saver,
            cfaArrangement = cfa,
            onSaved = { uri -> _state.update { it.copy(lastSavedUri = uri) } },
            onError = { msg -> message(msg) },
            onJobDone = {
                post {
                    pendingJobs = (pendingJobs - 1).coerceAtLeast(0)
                    publishBusy()
                }
            }
        )
        coordinator = coord

        if (needJpeg) {
            val r = ImageReader.newInstance(out.jpeg.width, out.jpeg.height, ImageFormat.JPEG, 6)
            r.setOnImageAvailableListener({ reader ->
                try { reader.acquireNextImage()?.let { coord.onJpeg(it) } } catch (e: IllegalStateException) { Log.w(TAG, "jpeg acquire", e) }
            }, handler)
            jpegReader = r
            configs += OutputConfiguration(r.surface)
        }
        if (needRaw) {
            val rawSize = out.raw!!
            val r = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 5)
            r.setOnImageAvailableListener({ reader ->
                try { reader.acquireNextImage()?.let { coord.onRaw(it) } } catch (e: IllegalStateException) { Log.w(TAG, "raw acquire", e) }
            }, handler)
            rawReader = r
            configs += OutputConfiguration(r.surface)
        }

        val gen = sessionGen
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != sessionGen || device == null) {
                    s.close()
                    return
                }
                session = s
                _state.update { it.copy(sessionReady = true) }
                applyRepeatingLocked()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen == sessionGen) {
                    Log.w(TAG, "onConfigureFailed (analysis=$useAnalysis jpeg=$needJpeg raw=$needRaw)")
                    fallbackSessionLocked()
                }
            }
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor, callback)

        val supported = try { dev.isSessionConfigurationSupported(config) } catch (e: Exception) { true }
        if (!supported) {
            Log.w(TAG, "session not supported (analysis=$useAnalysis jpeg=$needJpeg raw=$needRaw)")
            fallbackSessionLocked()
            return
        }
        try {
            dev.createCaptureSession(config)
        } catch (e: Exception) {
            message("Could not start the camera session: ${e.message}")
        }
    }

    /**
     * Steps down one level each time the lens refuses a stream combination:
     * DNG+JPEG -> DNG only -> no analysis stream -> JPEG (with analysis again). Every step reduces
     * the request, so the chain always ends.
     */
    private fun fallbackSessionLocked() {
        val s = _state.value.settings
        when {
            s.needsJpeg() && s.needsRaw() -> {
                message("DNG + JPEG together isn't supported on this lens, switching to DNG only")
                _state.update { it.copy(settings = it.settings.copy(format = CaptureFormat.DNG)) }
            }
            useAnalysis -> {
                useAnalysis = false
                val fixed = if (s.exposureMode == ExposureMode.SHUTTER_PRIORITY || s.exposureMode == ExposureMode.ISO_PRIORITY)
                    s.copy(exposureMode = ExposureMode.MANUAL) else s
                _state.update { it.copy(settings = fixed) }
                message("Live histogram, peaking, zebras and priority modes are off: this lens can't run them alongside the capture outputs")
            }
            s.needsRaw() -> {
                useAnalysis = true
                message("RAW can't be opened on this lens with these settings, switching to JPEG")
                _state.update {
                    it.copy(
                        settings = it.settings.copy(
                            format = CaptureFormat.JPEG,
                            drive = if (it.settings.drive == DriveMode.LOW_LIGHT) DriveMode.SINGLE else it.settings.drive
                        )
                    )
                }
            }
            else -> {
                message("This lens can't open the requested outputs")
                return
            }
        }
        createSessionLocked()
    }

    private fun closeSessionLocked() {
        sessionGen++
        try { session?.stopRepeating() } catch (e: Exception) { /* closing anyway */ }
        session?.close()
        session = null

        analysisReader?.close()
        analysisReader = null
        val j = jpegReader
        val r = rawReader
        jpegReader = null
        rawReader = null
        val coord = coordinator
        coordinator = null
        if (coord != null) {
            // Close the readers only after queued saves finish, so images being written stay valid.
            coord.release { j?.close(); r?.close() }
        } else {
            j?.close()
            r?.close()
        }
        previewSurface?.release()
        previewSurface = null
        pendingJobs = 0
        timerToken++
        _state.update { it.copy(sessionReady = false, busy = false, countdown = 0, intervalRunning = false) }
    }

    private fun closeCameraLocked() {
        closeSessionLocked()
        device?.close()
        device = null
        chars = null
        factory = null
        priority = null
        outputs = null
    }

    // ---------------------------------------------------------------- repeating request

    private fun applyRepeatingLocked() {
        val dev = device ?: return
        val sess = session ?: return
        val fac = factory ?: return
        val surface = previewSurface ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            analysisReader?.let { b.addTarget(it.surface) }
            fac.apply(b, _state.value.settings, displayRotationDeg, still = false)
            sess.setRepeatingRequest(b.build(), previewCallback, handler)
            framesSinceChange = 0
        } catch (e: CameraAccessException) {
            Log.w(TAG, "setRepeatingRequest", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setRepeatingRequest", e)
        }
    }

    private fun triggerAutofocusLocked() {
        val dev = device ?: return
        val sess = session ?: return
        val fac = factory ?: return
        val surface = previewSurface ?: return
        val s = _state.value.settings
        try {
            fun request(trigger: Int): CaptureRequest {
                val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(surface)
                analysisReader?.let { b.addTarget(it.surface) }
                fac.apply(b, s, displayRotationDeg, still = false)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
                return b.build()
            }
            sess.capture(request(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), null, handler)
            sess.capture(request(CameraMetadata.CONTROL_AF_TRIGGER_START), null, handler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "AF trigger", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AF trigger", e)
        }
    }

    // ---------------------------------------------------------------- callbacks

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            framesSinceChange++
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveMs < 100) return
            lastLiveMs = now
            val live = LiveReadout(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                afState = result.get(CaptureResult.CONTROL_AF_STATE),
                aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            )
            _state.update { it.copy(live = live) }
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            _state.update { it.copy(shutterTick = it.shutterTick + 1) }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            (request.tag as? CaptureJob)?.let { coordinator?.onResult(it, result) }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            (request.tag as? CaptureJob)?.let { coordinator?.onFailed(it) }
        }
    }

    private fun message(text: String) {
        Log.w(TAG, text)
        _state.update { it.copy(message = text) }
    }

    private companion object {
        const val TAG = "AuralCam"
        const val SETTLE_FRAMES = 5
    }
}