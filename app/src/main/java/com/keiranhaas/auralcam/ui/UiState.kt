package com.keiranhaas.auralcam.ui

enum class GridType(val label: String) {
    OFF("GRID"), THIRDS("3×3"), GOLDEN("PHI"), TRIANGLE_A("TRI A"), TRIANGLE_B("TRI B")
}

enum class HistMode(val label: String) {
    OFF("HIST"), LUMA("LUMA"), RGB("RGB"), WAVE("WAVE"), VECTOR("VECTOR")
}

enum class TouchTarget(val label: String) { FOCUS("AF"), EXPOSURE("AE"), BOTH("AF+AE") }

enum class ParamTab(val title: String) {
    ISO("ISO"), SHUTTER("SHUTTER"), WB("WB"), FOCUS("FOCUS"), EV("EV"), ZOOM("ZOOM")
}

/** UI-only options. Camera options live in CameraSettings. */
data class UiOptions(
    val peaking: Boolean = false,
    val zebra: Boolean = false,
    val grid: GridType = GridType.OFF,
    val level: Boolean = true,
    val hist: HistMode = HistMode.LUMA,
    val touchTarget: TouchTarget = TouchTarget.FOCUS,
    val tab: ParamTab = ParamTab.ISO,
    val settingsOpen: Boolean = false,
    /** Luma (0..255) at or above which zebra stripes appear. */
    val zebraLevel: Int = 245,
    /** Peaking edge threshold; lower = more sensitive. */
    val peakThreshold: Int = 50,
    /** Focus magnifier on/off and its zoom factor. */
    val magnifier: Boolean = false,
    val magnifyFactor: Float = 4f,
    /** Percent of the frame clipped before the warning badge appears; 0 = off. */
    val clipAlertPct: Float = 1f
)