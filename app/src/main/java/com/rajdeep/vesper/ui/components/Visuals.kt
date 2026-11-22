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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.text.drawText
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

/**
 * You-tab hero in the Hall's gold: a glossy 3D orb (radial light, specular highlight, rim glow, soft floor shadow)
 * floating gently, circled by two tilted orbits of gold particles that pass in front of and behind it.
 */
@Composable
fun ProfileHero(name: String, isAdmin: Boolean, joinedLabel: String, modifier: Modifier = Modifier) {
    val gold = V.c.hall
    val goldLight = androidx.compose.ui.graphics.lerp(gold, Color.White, 0.55f)
    val goldDeep = androidx.compose.ui.graphics.lerp(gold, Color.Black, 0.55f)
    val t = rememberInfiniteTransition(label = "hero")
    val a by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "a")
    val b by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(11_000, easing = LinearEasing)), label = "b")
    val float by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3_200, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val glow by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2_600), RepeatMode.Reverse), label = "glow")
    val sheen by t.animateFloat(-0.35f, 0.35f, infiniteRepeatable(tween(5_000, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "sheen")
    val enter = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 160f)) }
    val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val letterStyle = V.t.display.copy(fontSize = 64.sp, lineHeight = 64.sp)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val letterLayout = androidx.compose.runtime.remember(letter) { measurer.measure(letter, letterStyle.copy(color = goldDeep)) }

    Box(modifier.fillMaxWidth()) {
        Aurora(gold, goldLight, Modifier.matchParentSize(), intensity = 1.4f)
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier.size(240.dp, 200.dp).graphicsLayer {
                    val k = 0.75f + 0.25f * enter.value; scaleX = k; scaleY = k; alpha = enter.value.coerceIn(0f, 1f)
                },
            ) {
                val cx = size.width / 2
                val bob = float * 5.dp.toPx()
                val cy = size.height * 0.45f + bob
                val r = 58.dp.toPx()
                // Particles on two tilted elliptical orbits; depth decides size/brightness and front/back order.
                data class P(val x: Float, val y: Float, val z: Float)
                fun orbit(phase: Float, n: Int, rx: Float, ry: Float, tilt: Float): List<P> = List(n) { i ->
                    val th = phase + i * (2f * Math.PI.toFloat() / n)
                    val ex = rx * cos(th); val ey = ry * sin(th); val z = sin(th)
                    val ct = cos(tilt); val st = sin(tilt)
                    P(cx + ex * ct - ey * st, cy + ex * st + ey * ct, z)
                }
                val ps = orbit(a, 7, r * 1.75f, r * 0.42f, -0.32f) + orbit(-b, 5, r * 1.5f, r * 0.36f, 0.42f)
                fun dot(p: P) {
                    val d = (p.z + 1f) / 2f
                    val pr = (2.2f + 3.2f * d).dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(goldLight.copy(alpha = 0.55f * (0.35f + 0.65f * d)), Color.Transparent), Offset(p.x, p.y), pr * 3.2f), pr * 3.2f, Offset(p.x, p.y))
                    drawCircle(Color.White.copy(alpha = 0.55f + 0.45f * d), pr * 0.55f, Offset(p.x, p.y))
                }
                // floor shadow
                val sw = r * (1.35f - 0.08f * float)
                val floorY = size.height * 0.45f + r * 1.38f
                drawOval(
                    Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.42f - 0.08f * float), Color.Transparent), Offset(cx, floorY), sw),
                    topLeft = Offset(cx - sw, floorY - sw * 0.14f), size = androidx.compose.ui.geometry.Size(sw * 2, sw * 0.28f),
                )
                // back particles + faint orbit paths
                ps.filter { it.z < 0 }.forEach(::dot)
                // outer glow
                drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.45f * glow), gold.copy(alpha = 0.12f * glow), Color.Transparent), Offset(cx, cy), r * 2.0f), r * 2.0f, Offset(cx, cy))
                // sphere body: light from top-left
                drawCircle(
                    Brush.radialGradient(listOf(goldLight, gold, goldDeep), Offset(cx - r * 0.38f, cy - r * 0.42f), r * 1.55f),
                    r, Offset(cx, cy),
                )
                // rim light (bottom-right)
                drawCircle(
                    Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, goldLight.copy(alpha = 0.55f)), Offset(cx - r * 0.25f, cy - r * 0.25f), r * 1.3f),
                    r, Offset(cx, cy),
                )
                // letter, embossed: highlight then body
                val tl = Offset(cx - letterLayout.size.width / 2f, cy - letterLayout.size.height / 2f)
                drawText(letterLayout, color = Color.White.copy(alpha = 0.35f), topLeft = tl + Offset(-1.dp.toPx(), -1.5.dp.toPx()))
                drawText(letterLayout, color = goldDeep, topLeft = tl)
                // moving specular sheen + fixed highlight
                drawOval(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0f)), Offset(cx - r * (0.42f - sheen * 0.25f), cy - r * 0.52f), r * 0.42f),
                    topLeft = Offset(cx - r * (0.42f - sheen * 0.25f) - r * 0.36f, cy - r * 0.52f - r * 0.2f), size = androidx.compose.ui.geometry.Size(r * 0.72f, r * 0.4f),
                )
                // thin bright rim
                drawCircle(goldLight.copy(alpha = 0.6f), r, Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
                // front particles
                ps.filter { it.z >= 0 }.forEach(::dot)
            }
            Column(
                Modifier.graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); translationY = (1f - enter.value) * 20.dp.toPx() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name, style = V.t.display.copy(fontSize = 34.sp, lineHeight = 40.sp), color = V.c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .then(
                                if (isAdmin) Modifier.background(Brush.horizontalGradient(listOf(goldLight, gold, goldDeep)))
                                else Modifier.background(gold.copy(alpha = 0.14f)).border(1.dp, gold.copy(alpha = 0.5f), RoundedCornerShape(50)),
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(if (isAdmin) "Administrator" else "Member", style = V.t.label, color = if (isAdmin) goldDeep.copy(alpha = 0.95f) else gold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("since $joinedLabel", style = V.t.label, color = V.c.text2)
                }
            }
        }
    }
}
