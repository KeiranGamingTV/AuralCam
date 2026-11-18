package com.keiranhaas.auralcam.camera

import android.content.Context
import org.json.JSONObject

/** Saves whole sets of camera settings. Slot 0 is the automatic "last used" slot. */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("auralcam_presets", Context.MODE_PRIVATE)

    fun save(slot: Int, s: CameraSettings) {
        prefs.edit().putString("slot_$slot", toJson(s).toString()).apply()
    }

    fun load(slot: Int): CameraSettings? {
        val raw = prefs.getString("slot_$slot", null) ?: return null
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    // Touch points and the AE lock describe one moment in the frame, so presets leave them out.
    private fun toJson(s: CameraSettings): JSONObject = JSONObject().apply {
        put("exposureMode", s.exposureMode.name)
        put("iso", s.iso)
        put("exposureTimeNs", s.exposureTimeNs)
        put("evIndex", s.evIndex)
        put("whiteBalance", s.whiteBalance.name)
        put("kelvin", s.kelvin)
        put("focusMode", s.focusMode.name)
        put("focusDiopters", s.focusDiopters.toDouble())
        put("zoomRatio", s.zoomRatio.toDouble())
        put("ois", s.ois)
        put("format", s.format.name)
        put("processing", s.processing.name)
        put("jpegQuality", s.jpegQuality)
        put("drive", s.drive.name)
        put("burstCount", s.burstCount)
        put("lowLightFrames", s.lowLightFrames)
        put("bracketFrames", s.bracketFrames)
        put("bracketStepEv", s.bracketStepEv.toDouble())
        put("timerSeconds", s.timerSeconds)
        put("intervalSeconds", s.intervalSeconds)
        put("intervalCount", s.intervalCount)
        val o = s.overrides
        put("overrides", JSONObject().apply {
            o.noiseReduction?.let { put("nr", it) }
            o.edge?.let { put("edge", it) }
            o.hotPixel?.let { put("hot", it) }
            o.aberration?.let { put("aberr", it) }
            o.distortion?.let { put("dist", it) }
            put("tone", o.tone.name)
            put("gamma", o.gamma.toDouble())
        })
    }

    private fun fromJson(o: JSONObject): CameraSettings {
        val d = CameraSettings()
        val focus = o.enumOr("focusMode", d.focusMode)
        val ov = o.optJSONObject("overrides")
        return d.copy(
            exposureMode = o.enumOr("exposureMode", d.exposureMode),
            iso = o.optInt("iso", d.iso),
            exposureTimeNs = o.optLong("exposureTimeNs", d.exposureTimeNs),
            evIndex = o.optInt("evIndex", d.evIndex),
            whiteBalance = o.enumOr("whiteBalance", d.whiteBalance),
            kelvin = o.optInt("kelvin", d.kelvin),
            focusMode = if (focus == FocusMode.AUTO_TOUCH) FocusMode.AUTO_CONTINUOUS else focus,
            focusDiopters = o.optDouble("focusDiopters", d.focusDiopters.toDouble()).toFloat(),
            zoomRatio = o.optDouble("zoomRatio", d.zoomRatio.toDouble()).toFloat(),
            ois = o.optBoolean("ois", d.ois),
            format = o.enumOr("format", d.format),
            processing = o.enumOr("processing", d.processing),
            jpegQuality = o.optInt("jpegQuality", d.jpegQuality),
            drive = o.enumOr("drive", d.drive),
            burstCount = o.optInt("burstCount", d.burstCount),
            lowLightFrames = o.optInt("lowLightFrames", d.lowLightFrames),
            bracketFrames = o.optInt("bracketFrames", d.bracketFrames),
            bracketStepEv = o.optDouble("bracketStepEv", d.bracketStepEv.toDouble()).toFloat(),
            timerSeconds = o.optInt("timerSeconds", d.timerSeconds),
            intervalSeconds = o.optInt("intervalSeconds", d.intervalSeconds),
            intervalCount = o.optInt("intervalCount", d.intervalCount),
            overrides = if (ov == null) d.overrides else ProcessingOverrides(
                noiseReduction = ov.optIntOrNull("nr"),
                edge = ov.optIntOrNull("edge"),
                hotPixel = ov.optIntOrNull("hot"),
                aberration = ov.optIntOrNull("aberr"),
                distortion = ov.optIntOrNull("dist"),
                tone = ov.enumOr("tone", ToneChoice.DEFAULT),
                gamma = ov.optDouble("gamma", 2.2).toFloat()
            )
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key)) optInt(key) else null

    private inline fun <reified E : Enum<E>> JSONObject.enumOr(key: String, default: E): E {
        val name = optString(key, "")
        return enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}