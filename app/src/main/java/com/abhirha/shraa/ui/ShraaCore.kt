package com.abhirha.shraa.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.abhirha.shraa.AssistantState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Look of the orb for one assistant state. */
private class CoreStyle(val degPerSec: Float, val pulseMs: Int, val color: Color)

private fun styleFor(state: AssistantState) = when (state) {
    AssistantState.READY -> CoreStyle(40f, 3200, ShCyan)
    AssistantState.STANDBY -> CoreStyle(28f, 4200, ShBlue)
    AssistantState.LISTENING -> CoreStyle(103f, 900, ShGreen)
    AssistantState.PROCESSING -> CoreStyle(257f, 700, ShBlue)
    AssistantState.EXECUTING -> CoreStyle(164f, 800, ShCyan)
    AssistantState.SPEAKING -> CoreStyle(120f, 600, ShCyan)
}

/**
 * The animated SHRAA orb. One angle keeps counting up smoothly and every ring uses a fixed
 * multiple of it, so a state change only changes the speed gradually, never the position.
 */
@Composable
fun ShraaCore(state: AssistantState, modifier: Modifier = Modifier) {
    val style = styleFor(state)
    val color = style.color
    val speed by animateFloatAsState(style.degPerSec, tween(700), label = "speed")

    var angle by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            angle += speed.toDouble() * (now - last) / 1_000_000_000.0
            last = now
        }
    }

    val transition = rememberInfiniteTransition(label = "core")
    val pulse by transition.animateFloat(
        0.94f, 1.06f,
        infiniteRepeatable(tween(style.pulseMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Canvas(modifier.aspectRatio(1f)) {
        val c = center
        val r = size.minDimension / 2f
        val rad = (PI / 180.0).toFloat()
        val t = angle
        fun turn(mult: Double): Float = ((t * mult) % 360.0).toFloat()

        // soft glow
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.32f), Color.Transparent), center = c, radius = r),
            radius = r
        )
        // HUD ring with tick marks
        drawCircle(color.copy(alpha = 0.35f), radius = r * 0.92f, style = Stroke(1.dp.toPx()))
        val ticksTurn = turn(0.15)
        for (i in 0 until 72) {
            val a = (i * 5f + ticksTurn) * rad
            val major = i % 6 == 0
            val len = if (major) 0.07f else 0.035f
            val dir = Offset(cos(a), sin(a))
            drawLine(
                color.copy(alpha = if (major) 0.7f else 0.35f),
                start = c + dir * (r * 0.92f),
                end = c + dir * (r * (0.92f - len)),
                strokeWidth = 1.dp.toPx()
            )
        }
        // rotating arcs
        val outerTl = Offset(c.x - r * 0.72f, c.y - r * 0.72f)
        val outerSize = Size(r * 1.44f, r * 1.44f)
        drawArc(color, turn(1.0), 95f, false, outerTl, outerSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawArc(color.copy(alpha = 0.55f), turn(-1.4) + 180f, 60f, false, outerTl, outerSize, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        val innerTl = Offset(c.x - r * 0.58f, c.y - r * 0.58f)
        val innerSize = Size(r * 1.16f, r * 1.16f)
        drawArc(color.copy(alpha = 0.8f), turn(0.7) + 90f, 45f, false, innerTl, innerSize, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color.copy(alpha = 0.25f), radius = r * 0.5f, style = Stroke(1.dp.toPx()))
        // core
        val cr = r * 0.4f * pulse
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), color, color.copy(alpha = 0.15f)), center = c, radius = cr),
            radius = cr
        )
    }
}

/** Voice waveform bars: flat when idle, animated while listening or speaking. */
@Composable
fun VoiceWave(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val bars = 32
        val gap = size.width / bars
        for (i in 0 until bars) {
            val amp = if (active) 0.25f + 0.75f * abs(sin(phase + i * 0.45f)) else 0.08f
            val h = size.height * amp
            val x = gap * i + gap / 2
            drawLine(
                ShCyan.copy(alpha = if (active) 0.9f else 0.3f),
                Offset(x, size.height / 2 - h / 2),
                Offset(x, size.height / 2 + h / 2),
                strokeWidth = gap * 0.4f,
                cap = StrokeCap.Round
            )
        }
    }
}
