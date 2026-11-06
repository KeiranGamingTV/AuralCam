package com.keiranhaas.auralcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) Accent else Scrim
    val fg = when {
        !enabled -> Color(0x66FFFFFF)
        selected -> Color.Black
        else -> Color.White
    }
    Text(
        text = text,
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
fun ParamChip(
    title: String,
    value: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Color(0x33FFC107) else Color(0x22FFFFFF))
            .border(1.dp, if (selected) Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = if (selected) Accent else Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun SliderRow(valueText: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        content()
        Text(
            valueText,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(76.dp)
        )
    }
}

@Composable
fun HintText(text: String, color: Color = Color(0x99FFFFFF)) {
    Text(text, color = color, fontSize = 11.sp)
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Accent,
    activeTrackColor = Accent,
    inactiveTrackColor = Color(0x33FFFFFF),
    disabledThumbColor = Color(0x66FFFFFF),
    disabledActiveTrackColor = Color(0x44FFFFFF),
    disabledInactiveTrackColor = Color(0x22FFFFFF)
)

/**
 * Slider that follows the finger instantly (camera state updates arrive a moment later) and then
 * hands control back to [value] shortly after release.
 */
@Composable
private fun BaseSlider(
    value: Float,
    min: Float,
    max: Float,
    enabled: Boolean,
    modifier: Modifier,
    snap: (Float) -> Float,
    onValue: (Float) -> Unit
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hi = if (max > min) max else min + 1f
    Slider(
        value = (drag ?: value).coerceIn(min, hi),
        onValueChange = { raw ->
            dragging = true
            val v = snap(raw).coerceIn(min, hi)
            if (v != drag) {
                drag = v
                onValue(v)
            }
        },
        onValueChangeFinished = {
            dragging = false
            scope.launch {
                delay(250)
                if (!dragging) drag = null
            }
        },
        valueRange = min..hi,
        enabled = enabled && max > min,
        modifier = modifier,
        colors = sliderColors()
    )
}

/** Logarithmic slider that snaps to 1/3-stop steps (ISO, shutter speed). */
@Composable
fun LogSlider(
    value: Double,
    min: Double,
    max: Double,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit
) {
    val span = if (min > 0.0 && max > min) ln(max / min) else 0.0
    val n = if (span > 0.0) (span / ln(2.0) * 3.0).roundToInt().coerceAtLeast(1) else 1
    val t = if (span > 0.0) (ln(value.coerceIn(min, max) / min) / span).toFloat() else 0f
    BaseSlider(
        value = t, min = 0f, max = 1f, enabled = enabled && span > 0.0, modifier = modifier,
        snap = { (it * n).roundToInt() / n.toFloat() }
    ) { snapped ->
        onChange(min * (max / min).pow(snapped.toDouble()))
    }
}

@Composable
fun LinearSlider(
    value: Float,
    min: Float,
    max: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    onChange: (Float) -> Unit
) {
    BaseSlider(
        value = value, min = min, max = max, enabled = enabled, modifier = modifier,
        snap = { raw -> if (step > 0f) min + ((raw - min) / step).roundToInt() * step else raw },
        onValue = onChange
    )
}