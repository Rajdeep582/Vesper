package com.rajdeep.vesper.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.sp
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

/** You-tab hero: aurora backdrop, living ring avatar with an orbiting spark, badges and count-up stats. */
@Composable
fun ProfileHero(
    name: String,
    isAdmin: Boolean,
    joinedLabel: String,
    days: Int,
    chats: Int,
    devices: Int,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (isAdmin) V.c.console else V.accent
    val second = V.c.direct
    val t = rememberInfiniteTransition(label = "hero")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "spin")
    val orbit by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(4_200, easing = LinearEasing)), label = "orbit")
    val breathe by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "breathe")
    // Entrance: avatar pops, then text and stats rise in.
    val enter = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 180f)) }
    Box(modifier.fillMaxWidth()) {
        Aurora(accent, second, Modifier.matchParentSize(), intensity = 1.6f)
        Column(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(168.dp).graphicsLayer { val k = 0.7f + 0.3f * enter.value; scaleX = k; scaleY = k; alpha = enter.value.coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center; val r = size.minDimension / 2
                    // soft halo
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.32f * breathe), Color.Transparent), c, r), r, c)
                    // conic ring
                    rotate(spin) {
                        drawCircle(
                            Brush.sweepGradient(listOf(accent, second, V_SUCCESS, accent.copy(alpha = 0.2f), accent)),
                            radius = r * 0.70f, style = Stroke(3.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        )
                    }
                    // thin outer track with dashes
                    drawCircle(accent.copy(alpha = 0.18f), radius = r * 0.82f, style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 7.dp.toPx()))))
                    // orbiting spark
                    val spark = Offset(c.x + r * 0.82f * cos(orbit), c.y + r * 0.82f * sin(orbit))
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), accent.copy(alpha = 0.0f)), spark, 9.dp.toPx()), 9.dp.toPx(), spark)
                    drawCircle(Color.White, 2.2.dp.toPx(), spark)
                }
                Monogram(name.ifEmpty { "?" }, 104.dp)
                if (online) Box(
                    Modifier.align(Alignment.BottomEnd).offset((-30).dp, (-30).dp).size(22.dp).clip(CircleShape).background(V.c.bg).padding(3.dp)
                        .clip(CircleShape).background(V_SUCCESS).graphicsLayer { alpha = 0.6f + 0.4f * breathe },
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); translationY = (1f - enter.value) * 24.dp.toPx() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, style = V.t.display.copy(fontSize = 32.sp, lineHeight = 36.sp), color = V.c.text)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isAdmin) Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Brush.horizontalGradient(listOf(accent, second))).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("★", style = V.t.caption, color = Color.White)
                        Spacer(Modifier.width(5.dp))
                        Text("Administrator", style = V.t.caption, color = Color.White)
                    }
                    StatChip("Since $joinedLabel", accent)
                    StatChip(if (online) "Online" else "Offline", if (online) V_SUCCESS else V.c.text3, pulse = online)
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(V.c.s1.copy(alpha = 0.72f))
                        .border(1.dp, V.c.hairline, RoundedCornerShape(22.dp)).padding(vertical = 14.dp),
                ) {
                    HeroStat(days, if (days == 1) "day here" else "days here", accent, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(38.dp).background(V.c.hairline).align(Alignment.CenterVertically))
                    HeroStat(chats, if (chats == 1) "chat" else "chats", second, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(38.dp).background(V.c.hairline).align(Alignment.CenterVertically))
                    HeroStat(devices, if (devices == 1) "device" else "devices", V_SUCCESS, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: Int, label: String, tint: Color, modifier: Modifier = Modifier) {
    val shown by androidx.compose.animation.core.animateIntAsState(value, tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "count")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(shown.toString(), style = V.t.title.copy(fontSize = 24.sp), color = tint)
        Text(label, style = V.t.caption, color = V.c.text2)
    }
}
