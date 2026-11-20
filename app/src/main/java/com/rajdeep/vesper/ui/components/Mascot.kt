package com.rajdeep.vesper.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rajdeep.vesper.ui.theme.V
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * "Ember" — the Hall's little lantern creature: a soft rounded head with two antennae, glancing around and blinking.
 * Pupils spring toward random targets; antenna tips sway; the whole head breathes slightly.
 */
@Composable
fun HallMascot(size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val body = V.accent
    val dark = lerp(body, Color.Black, 0.55f)
    val eyeWhite = V.c.bg
    val lookX = remember { Animatable(0f) }
    val lookY = remember { Animatable(0f) }
    val blink = remember { Animatable(1f) }
    val wow = remember { Animatable(1f) }   // eyes widen in surprise now and then
    val tilt = remember { Animatable(0f) }  // curious head tilt
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(900, 2600))
            val tx = Random.nextFloat() * 2f - 1f
            val ty = Random.nextFloat() * 1.4f - 0.7f
            kotlinx.coroutines.coroutineScope {
                launch { lookX.animateTo(tx, spring(dampingRatio = 0.55f, stiffness = 260f)) }
                launch { lookY.animateTo(ty, spring(dampingRatio = 0.55f, stiffness = 260f)) }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2200, 4800))
            blink.animateTo(0.08f, tween(70)); blink.animateTo(1f, tween(110))
            if (Random.nextFloat() < 0.25f) { delay(140); blink.animateTo(0.08f, tween(70)); blink.animateTo(1f, tween(110)) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(6000, 11000))
            when (Random.nextInt(2)) {
                0 -> { wow.animateTo(1.45f, spring(dampingRatio = 0.35f, stiffness = 500f)); delay(650); wow.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 220f)) }
                else -> { tilt.animateTo(if (Random.nextBoolean()) 12f else -12f, spring(dampingRatio = 0.5f, stiffness = 180f)); delay(900); tilt.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 160f)) }
            }
        }
    }
    val inf = rememberInfiniteTransition(label = "mascot")
    val sway by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "sway")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")

    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        rotate(tilt.value, Offset(w / 2f, h * 0.75f)) {
        val headW = w * 0.80f; val headH = h * 0.54f * (1f + 0.025f * breathe)
        val left = (w - headW) / 2f; val top = h - headH - h * 0.06f
        val stroke = w * 0.06f
        // antennae
        listOf(-1f, 1f).forEach { side ->
            val base = Offset(w / 2f + side * headW * 0.22f, top + stroke)
            val tip = Offset(base.x + side * w * 0.09f + sway * w * 0.04f, (top - h * 0.20f).coerceAtLeast(w * 0.09f))
            drawLine(dark, base, tip, stroke, StrokeCap.Round)
            drawCircle(body, w * 0.075f, tip)
        }
        // head with soft top light
        drawRoundRect(
            Brush.verticalGradient(listOf(lerp(body, Color.White, 0.18f), body), startY = top, endY = top + headH),
            Offset(left, top), Size(headW, headH), CornerRadius(headH * 0.45f),
        )
        // eyes
        val eyeR = headW * 0.15f * wow.value
        val eyeY = top + headH * 0.48f
        listOf(-1f, 1f).forEach { side ->
            val c = Offset(w / 2f + side * headW * 0.21f, eyeY)
            val eh = eyeR * 2f * blink.value
            drawRoundRect(eyeWhite, Offset(c.x - eyeR, c.y - eh / 2f), Size(eyeR * 2f, eh.coerceAtLeast(1f)), CornerRadius(eyeR))
            if (blink.value > 0.35f) {
                val p = Offset(c.x + lookX.value * eyeR * 0.45f, c.y + lookY.value * eyeR * 0.40f * blink.value)
                drawCircle(body, eyeR * 0.52f, p)
                drawCircle(Color.White.copy(alpha = 0.85f), eyeR * 0.16f, p + Offset(-eyeR * 0.18f, -eyeR * 0.18f))
            }
        }
        // tiny smile
        drawArc(dark, 20f, 140f, false, Offset(w / 2f - headW * 0.09f, top + headH * 0.66f), Size(headW * 0.18f, headH * 0.14f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 0.7f, cap = StrokeCap.Round))
        }
    }
}

/**
 * Initial loading screen mascot: plays a single, smooth sequence on startup.
 * The mascot moves one ear slightly to the side with a natural bounce,
 * blinks its eyes exactly once, and completes so the application loads normally.
 */
@Composable
fun SplashAnimatedMascot(
    size: Dp = 90.dp,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    val body = V.c.hall
    val dark = lerp(body, Color.Black, 0.55f)
    val eyeWhite = V.c.bg

    val earTilt = remember { Animatable(0f) }
    val blink = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        delay(180)
        // Ear movement: right ear moves slightly to the side and bounces back
        earTilt.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
        delay(80)
        earTilt.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 280f))
        delay(60)
        // Blink eyes exactly once
        blink.animateTo(0.08f, tween(90, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        blink.animateTo(1f, tween(130, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        delay(220)
        onFinished()
    }

    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val headW = w * 0.80f; val headH = h * 0.54f
        val left = (w - headW) / 2f; val top = h - headH - h * 0.06f
        val stroke = w * 0.06f
        // antennae (ears)
        listOf(-1f, 1f).forEach { side ->
            val base = Offset(w / 2f + side * headW * 0.22f, top + stroke)
            // One ear (side == 1f, right ear) tilts and wiggles to the side
            val earShift = if (side == 1f) earTilt.value * w * 0.10f else 0f
            val earLift = if (side == 1f) -earTilt.value * h * 0.035f else 0f
            val tip = Offset(base.x + side * w * 0.09f + earShift, (top - h * 0.20f + earLift).coerceAtLeast(w * 0.09f))
            drawLine(dark, base, tip, stroke, StrokeCap.Round)
            drawCircle(body, w * 0.075f, tip)
        }
        // head with soft top light
        drawRoundRect(
            Brush.verticalGradient(listOf(lerp(body, Color.White, 0.18f), body), startY = top, endY = top + headH),
            Offset(left, top), Size(headW, headH), CornerRadius(headH * 0.45f),
        )
        // eyes
        val eyeR = headW * 0.15f
        val eyeY = top + headH * 0.48f
        listOf(-1f, 1f).forEach { side ->
            val c = Offset(w / 2f + side * headW * 0.21f, eyeY)
            val eh = eyeR * 2f * blink.value
            drawRoundRect(eyeWhite, Offset(c.x - eyeR, c.y - eh / 2f), Size(eyeR * 2f, eh.coerceAtLeast(1f)), CornerRadius(eyeR))
            if (blink.value > 0.35f) {
                val p = Offset(c.x, c.y)
                drawCircle(body, eyeR * 0.52f, p)
                drawCircle(Color.White.copy(alpha = 0.85f), eyeR * 0.16f, p + Offset(-eyeR * 0.18f, -eyeR * 0.18f))
            }
        }
        // tiny smile
        drawArc(dark, 20f, 140f, false, Offset(w / 2f - headW * 0.09f, top + headH * 0.66f), Size(headW * 0.18f, headH * 0.14f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 0.7f, cap = StrokeCap.Round))
    }
}
