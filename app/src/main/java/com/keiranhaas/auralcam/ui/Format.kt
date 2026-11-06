package com.keiranhaas.auralcam.ui

import java.util.Locale
import kotlin.math.roundToInt

fun formatShutter(ns: Long): String {
    val s = ns / 1_000_000_000.0
    return if (s >= 0.5) {
        String.format(Locale.US, "%.1f", s).removeSuffix(".0") + "s"
    } else {
        "1/" + (1.0 / s).roundToInt()
    }
}

fun formatFocus(diopters: Float): String {
    if (diopters < 0.05f) return "∞"
    val cm = 100f / diopters
    return if (cm >= 100f) String.format(Locale.US, "%.1f m", cm / 100f) else "${cm.roundToInt()} cm"
}

fun formatEv(index: Int, step: Float): String {
    val ev = index * step
    return (if (ev > 0.001f) "+" else "") + String.format(Locale.US, "%.1f", ev)
}

fun formatZoom(z: Float): String = String.format(Locale.US, "%.1f×", z)

fun formatPct(p: Float): String = String.format(Locale.US, "%.1f%%", p)