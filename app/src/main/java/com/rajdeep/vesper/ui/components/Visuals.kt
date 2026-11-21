package com.rajdeep.vesper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slow drifting light behind a screen: three soft radial glows that orbit over ~20 s, plus a top wash.
 * Cheap: one Canvas, no blur, drawn behind content.
 */
@Composable
fun Aurora(primary: Color, secondary: Color, modifier: Modifier = Modifier, intensity: Float = 1f) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(22_000, easing = LinearEasing)), label = "a")
    val breathe by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(6_000), RepeatMode.Reverse), label = "b")
    val dark = V.c.dark
    val k = (if (dark) 1f else 0.7f) * intensity
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(0f to primary.copy(alpha = 0.16f * k), 0.45f to Color.Transparent))
        fun glow(cx: Float, cy: Float, r: Float, c: Color, alpha: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha * k), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        glow(w * (0.82f + 0.10f * cos(a)), h * (0.08f + 0.05f * sin(a)), w * 0.70f * breathe, primary, 0.22f)
        glow(w * (0.12f + 0.12f * sin(a * 0.7f)), h * (0.30f + 0.06f * cos(a * 0.7f)), w * 0.62f, secondary, 0.14f)
        glow(w * (0.60f + 0.15f * cos(a * 1.3f + 1f)), h * (0.78f + 0.05f * sin(a * 1.3f)), w * 0.80f / breathe, primary, 0.08f)
    }
}

/** Avatar with an animated sweep-gradient ring (story style) and a presence dot. */
@Composable
fun RingAvatar(name: String, size: Dp, ring: Color, online: Boolean, modifier: Modifier = Modifier, spinning: Boolean = true) {
    val t = rememberInfiniteTransition(label = "ring")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(5_000, easing = LinearEasing)), label = "rot")
    val ringA by animateFloatAsState(if (online) 1f else 0.35f, Motion.tw(), label = "ra")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            rotate(if (spinning && online) rot else 0f) {
                drawCircle(
                    Brush.sweepGradient(listOf(ring.copy(alpha = ringA), ring.copy(alpha = 0.15f * ringA), V_SUCCESS.copy(alpha = ringA), ring.copy(alpha = ringA))),
                    radius = this.size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(2.2.dp.toPx()),
                )
            }
        }
        PresenceAvatar(name, size - 9.dp, online)
    }
}

private val V_SUCCESS = Color(0xFF7FB89A)

/** Horizontal "Active now" strip of people with live rings. */
@Composable
fun ActiveStrip(people: List<Pair<String, String>>, ring: Color, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(people, key = { it.first }) { (id, name) ->
            Column(
                Modifier.animateItem().widthIn(max = 68.dp).clip(RoundedCornerShape(16.dp)).pressable(scaleTo = 0.92f) { onOpen(id) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RingAvatar(name, 60.dp, ring, online = true)
                Spacer(Modifier.height(6.dp))
                Text(name, style = V.t.caption, color = V.c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Small glassy stat chip: icon/dot + animated value + label. */
@Composable
fun StatChip(label: String, tint: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, pulse: Boolean = false) {
    val t = rememberInfiniteTransition(label = "chip")
    val p by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "p")
    val bg by animateColorAsState(tint.copy(alpha = 0.12f), Motion.tw(), label = "bg")
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        else Box(Modifier.size(7.dp).graphicsLayer { alpha = if (pulse) p else 1f }.clip(CircleShape).background(tint))
        Spacer(Modifier.width(6.dp))
        Text(label, style = V.t.caption, color = tint)
    }
}

/** Section label with a fading hairline. */
@Composable
fun FadeLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = V.t.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.4f, androidx.compose.ui.unit.TextUnitType.Sp)), color = V.c.text3)
        Spacer(Modifier.width(10.dp))
        val line = V.c.hairline
        Box(Modifier.weight(1f).height(1.dp).drawBehind { drawRect(Brush.horizontalGradient(listOf(line, Color.Transparent))) })
    }
}

/** Card surface used by list rows: translucent surface, hairline edge, optional accent glow on the left. */
fun Modifier.glassCard(surface: Color, edge: Color, glow: Color? = null, corner: Dp = 20.dp): Modifier =
    this.clip(RoundedCornerShape(corner))
        .background(surface)
        .then(if (glow != null) Modifier.background(Brush.horizontalGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent))) else Modifier)
        .border(1.dp, edge, RoundedCornerShape(corner))

fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Up late"
}
