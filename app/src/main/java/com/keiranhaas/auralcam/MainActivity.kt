package com.keiranhaas.auralcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.keiranhaas.auralcam.analysis.AnalysisPipeline
import com.keiranhaas.auralcam.camera.CameraController
import com.keiranhaas.auralcam.camera.NativeRawMerger
import com.keiranhaas.auralcam.camera.NormPoint
import com.keiranhaas.auralcam.ui.AuralCamTheme
import com.keiranhaas.auralcam.ui.CameraScreen
import com.keiranhaas.auralcam.ui.PermissionScreen

class MainActivity : ComponentActivity() {

    private lateinit var controller: CameraController
    private lateinit var analysis: AnalysisPipeline
    private var orientationListener: OrientationEventListener? = null
    private var mediaSession: MediaSession? = null
    private var permissionGranted by mutableStateOf(false)
    private var foreground = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            if (granted && foreground) startCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        controller = CameraController(applicationContext, mergerFactory = { NativeRawMerger() })
        analysis = AnalysisPipeline(onMeteredLuma = controller::onMeteredLuma)
        controller.analysisSink = analysis

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) controller.setDeviceOrientation(orientation)
            }
        }
        setupMediaSession()

        permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)

        val rotation = displayRotationDegrees()
        setContent {
            AuralCamTheme {
                if (permissionGranted) {
                    CameraScreen(controller, analysis, rotation)
                } else {
                    PermissionScreen(
                        onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", packageName, null))
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (permissionGranted) startCamera()
    }

    override fun onPause() {
        foreground = false
        controller.stop()
        orientationListener?.disable()
        mediaSession?.isActive = false
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun startCamera() {
        controller.setDisplayRotation(displayRotationDegrees())
        controller.start()
        orientationListener?.let { if (it.canDetectOrientation()) it.enable() }
        mediaSession?.isActive = true
    }

    private fun displayRotationDegrees(): Int = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    // ------------------------------------------------------------ remotes

    /**
     * Volume keys, camera/focus keys, Bluetooth selfie remotes (they present as keyboards or media
     * buttons) and USB HID remotes all arrive here as ordinary key events.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!permissionGranted) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            in SHUTTER_KEYS -> {
                if (event.repeatCount == 0) controller.capture()
                true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                if (event.repeatCount == 0) controller.tapToFocus(NormPoint(0.5f, 0.5f))
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (permissionGranted && (keyCode in SHUTTER_KEYS || keyCode == KeyEvent.KEYCODE_FOCUS)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Wired and Bluetooth headset buttons come through a media session, not as activity key events. */
    @Suppress("DEPRECATION")
    private fun setupMediaSession() {
        val session = MediaSession(this, "AuralCamRemote")
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (ev.keyCode !in HEADSET_KEYS) return false
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) controller.capture()
                return true
            }
        })
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build()
        )
        mediaSession = session
    }

    private companion object {
        val HEADSET_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        )
        val SHUTTER_KEYS = HEADSET_KEYS + setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE
        )
    }
}