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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
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
    val enter = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 150f)) }
    val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(modifier.fillMaxWidth()) {
        Aurora(gold, goldLight, Modifier.matchParentSize(), intensity = 1.3f)
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ChipEmblem(
                letter, gold, goldLight, goldDeep,
                Modifier.size(300.dp, 220.dp).graphicsLayer {
                    val k = 0.82f + 0.18f * enter.value; scaleX = k; scaleY = k; alpha = enter.value.coerceIn(0f, 1f)
                },
            )
            Column(
                Modifier.graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); translationY = (1f - enter.value) * 20.dp.toPx() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.rajdeep.vesper.ui.theme.Instrument, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.2).sp,
                    ),
                    color = V.c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
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

/**
 * Processor emblem: a gold-trimmed chip floating over a circuit board. Current runs along the traces into the pins,
 * and around the outline of the member's initial etched into the die. Slight 3D tilt + sway, scanning sheen.
 */
@Composable
private fun ChipEmblem(letter: String, gold: Color, goldLight: Color, goldDeep: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "chip")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "flow")
    val run by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3_400, easing = LinearEasing)), label = "run")
    val sway by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(5_200, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val scan by t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(4_200, easing = androidx.compose.animation.core.FastOutSlowInEasing)), label = "scan")
    val glow by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "glow")

    val density = androidx.compose.ui.platform.LocalDensity.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Glyph outline of the initial, split into contours so current can travel around every stroke.
    val glyph = androidx.compose.runtime.remember(letter) {
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, com.rajdeep.vesper.R.font.instrument_semibold) }.getOrNull()
                ?: android.graphics.Typeface.DEFAULT_BOLD
            textSize = with(density) { 66.sp.toPx() }
        }
        val path = android.graphics.Path()
        tp.getTextPath(letter, 0, letter.length, 0f, 0f, path)
        val contours = mutableListOf<Pair<android.graphics.PathMeasure, Float>>()
        val pm = android.graphics.PathMeasure(path, true)
        do {
            val len = pm.length
            if (len > 4f) {
                val c = android.graphics.Path(); pm.getSegment(0f, len, c, true); c.close()
                contours += android.graphics.PathMeasure(c, true) to len
            }
        } while (pm.nextContour())
        val b = android.graphics.RectF(); path.computeBounds(b, true)
        Triple(path, contours, b)
    }
    val seg = androidx.compose.runtime.remember { android.graphics.Path() }
    val paint = androidx.compose.runtime.remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val traceCache = androidx.compose.runtime.remember { arrayOfNulls<Any>(2) }

    Canvas(
        modifier.graphicsLayer {
            rotationX = 12f + sway * 2f
            rotationY = sway * 7f
            cameraDistance = 14f * this.density
        },
    ) {
        val nc = drawContext.canvas.nativeCanvas
        val w = size.width; val h = size.height
        val cx = w / 2; val cy = h * 0.47f
        val H = 50.dp.toPx()
        val pinLen = 7.dp.toPx()
        val bob = sway * 3.dp.toPx()

        // ---- circuit traces (built once per size), oriented from the via (outside) to the pin (chip)
        @Suppress("UNCHECKED_CAST")
        val traces: List<Pair<android.graphics.Path, android.graphics.PointF>> = (traceCache[1] as? List<Pair<android.graphics.Path, android.graphics.PointF>>)
            ?.takeIf { traceCache[0] == size } ?: run {
            val list = mutableListOf<Pair<android.graphics.Path, android.graphics.PointF>>()
            val dirs = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)
            dirs.forEachIndexed { si, (dx, dy) ->
                val ax = -dy; val ay = dx
                val room = if (dx != 0f) (w / 2 - H - pinLen) else if (dy < 0) (cy - H - pinLen) else (h - cy - H - pinLen)
                val n = 6
                for (i in 0 until n) {
                    if (dy != 0f && (i == 0 || i == n - 1)) continue
                    val o = -H * 0.72f + i * (H * 1.44f / (n - 1))
                    val sx = cx + dx * (H + pinLen) + ax * o
                    val sy = cy + dy * (H + pinLen) + ay * o
                    val L = room * (0.62f + 0.34f * (((i * 37 + si * 11) % 7) / 6f)) - 6.dp.toPx()
                    val s1 = L * (0.18f + 0.1f * (i % 3))
                    val side = if (o < 0) -1f else 1f
                    val dg = (L * 0.32f).coerceAtMost(26.dp.toPx())
                    val p = android.graphics.Path()
                    val x1 = sx + dx * s1; val y1 = sy + dy * s1
                    val x2 = x1 + dx * dg + ax * side * dg * 0.8f; val y2 = y1 + dy * dg + ay * side * dg * 0.8f
                    val rest = (L - s1 - dg).coerceAtLeast(4.dp.toPx())
                    val x3 = x2 + dx * rest; val y3 = y2 + dy * rest
                    p.moveTo(x3, y3); p.lineTo(x2, y2); p.lineTo(x1, y1); p.lineTo(sx, sy)
                    list += p to android.graphics.PointF(x3, y3)
                }
            }
            traceCache[0] = size; traceCache[1] = list; list
        }

        // board traces
        paint.reset(); paint.isAntiAlias = true; paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 1.4.dp.toPx(); paint.strokeJoin = android.graphics.Paint.Join.ROUND; paint.strokeCap = android.graphics.Paint.Cap.ROUND
        paint.color = gold.copy(alpha = 0.30f).toArgb()
        traces.forEach { (p, _) -> nc.drawPath(p, paint) }
        // vias
        traces.forEach { (_, v) ->
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.3.dp.toPx(); paint.color = gold.copy(alpha = 0.55f).toArgb()
            nc.drawCircle(v.x, v.y, 3.2.dp.toPx(), paint)
            paint.style = android.graphics.Paint.Style.FILL; paint.color = goldDeep.copy(alpha = 0.9f).toArgb()
            nc.drawCircle(v.x, v.y, 1.6.dp.toPx(), paint)
        }
        // electrons: comet heads travelling into the chip (and a few outward)
        val pmT = android.graphics.PathMeasure()
        traces.forEachIndexed { k, (p, _) ->
            pmT.setPath(p, false)
            val len = pmT.length
            val ph = (flow * (1f + (k % 3) * 0.18f) + k * 0.173f) % 1f
            val inward = k % 4 != 1
            val head = (if (inward) ph else 1f - ph) * len
            val tail = 0.26f * len
            val a0 = if (inward) (head - tail).coerceAtLeast(0f) else head
            val a1 = if (inward) head else (head + tail).coerceAtMost(len)
            seg.reset(); pmT.getSegment(a0, a1, seg, true)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.maskFilter = android.graphics.BlurMaskFilter(5.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            paint.strokeWidth = 4.dp.toPx(); paint.color = gold.copy(alpha = 0.85f).toArgb()
            nc.drawPath(seg, paint)
            paint.maskFilter = null
            paint.strokeWidth = 1.5.dp.toPx(); paint.color = goldLight.toArgb()
            nc.drawPath(seg, paint)
            val pos = FloatArray(2); pmT.getPosTan(head, pos, null)
            paint.style = android.graphics.Paint.Style.FILL; paint.color = android.graphics.Color.WHITE
            nc.drawCircle(pos[0], pos[1], 1.7.dp.toPx(), paint)
        }

        // ---- chip (floats above the board)
        val ccy = cy - 4.dp.toPx() + bob
        // shadow on the board
        paint.reset(); paint.isAntiAlias = true
        paint.maskFilter = android.graphics.BlurMaskFilter(16.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        paint.color = Color.Black.copy(alpha = 0.55f - 0.1f * sway).toArgb()
        val sh = H * (1.02f - 0.03f * sway)
        nc.drawRoundRect(cx - sh, cy - sh + 12.dp.toPx(), cx + sh, cy + sh + 14.dp.toPx(), 14.dp.toPx(), 14.dp.toPx(), paint)
        paint.maskFilter = null
        // halo
        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.32f * glow), Color.Transparent), Offset(cx, ccy), H * 2.1f), H * 2.1f, Offset(cx, ccy))
        // pins
        val pinW = 3.4.dp.toPx()
        for (side in 0 until 4) for (i in 0 until 6) {
            val o = -H * 0.72f + i * (H * 1.44f / 5)
            val (l, tp, r, b) = when (side) {
                0 -> listOf(cx + H - 1f, ccy + o - pinW / 2, cx + H + pinLen, ccy + o + pinW / 2)
                1 -> listOf(cx - H - pinLen, ccy + o - pinW / 2, cx - H + 1f, ccy + o + pinW / 2)
                2 -> listOf(cx + o - pinW / 2, ccy + H - 1f, cx + o + pinW / 2, ccy + H + pinLen)
                else -> listOf(cx + o - pinW / 2, ccy - H - pinLen, cx + o + pinW / 2, ccy - H + 1f)
            }
            drawRoundRect(Brush.linearGradient(listOf(goldLight, gold, goldDeep), Offset(l, tp), Offset(r, b)), Offset(l, tp),
                androidx.compose.ui.geometry.Size(r - l, b - tp), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
        }
        // package body: graphite with a warm tint, bevelled
        val pkgTl = Offset(cx - H, ccy - H); val pkgSz = androidx.compose.ui.geometry.Size(2 * H, 2 * H)
        val cr = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
        val graphite = androidx.compose.ui.graphics.lerp(goldDeep, Color.Black, 0.72f)
        drawRoundRect(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(graphite, gold, 0.18f), graphite, Color.Black), pkgTl, pkgTl + Offset(2 * H, 2 * H)), pkgTl, pkgSz, cr)
        drawRoundRect(Brush.linearGradient(listOf(goldLight.copy(alpha = 0.85f), gold.copy(alpha = 0.25f), goldDeep.copy(alpha = 0.6f)), pkgTl, pkgTl + Offset(2 * H, 2 * H)), pkgTl, pkgSz, cr, style = Stroke(1.4.dp.toPx()))
        // pin-1 marker
        drawCircle(gold.copy(alpha = 0.7f), 2.4.dp.toPx(), Offset(cx - H + 9.dp.toPx(), ccy - H + 9.dp.toPx()))
        // die
        val D = H - 13.dp.toPx()
        val dieTl = Offset(cx - D, ccy - D); val dieSz = androidx.compose.ui.geometry.Size(2 * D, 2 * D)
        val dcr = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
        drawRoundRect(Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(graphite, gold, 0.22f), Color.Black), Offset(cx - D * 0.4f, ccy - D * 0.5f), D * 2.4f), dieTl, dieSz, dcr)
        // micro grid on the die
        val step = D / 5
        for (gx in 1 until 10) for (gy in 1 until 10) {
            if ((gx + gy) % 2 == 0) drawCircle(gold.copy(alpha = 0.16f), 0.8.dp.toPx(), Offset(cx - D + gx * step, ccy - D + gy * step))
        }
        drawRoundRect(gold.copy(alpha = 0.55f), dieTl, dieSz, dcr, style = Stroke(1.dp.toPx()))
        // initial etched in gold, with current running around its outline
        val (gp, contours, gb) = glyph
        val gx0 = cx - gb.centerX(); val gy0 = ccy - gb.centerY()
        nc.save(); nc.translate(gx0, gy0)
        paint.reset(); paint.isAntiAlias = true; paint.style = android.graphics.Paint.Style.FILL
        paint.maskFilter = android.graphics.BlurMaskFilter(10.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        paint.color = gold.copy(alpha = 0.45f * glow).toArgb()
        nc.drawPath(gp, paint)
        paint.maskFilter = null
        paint.shader = android.graphics.LinearGradient(gb.left, gb.top, gb.right, gb.bottom,
            intArrayOf(goldLight.toArgb(), gold.toArgb(), goldDeep.toArgb()), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        nc.drawPath(gp, paint)
        paint.shader = null
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.dp.toPx(); paint.color = goldLight.copy(alpha = 0.7f).toArgb()
        nc.drawPath(gp, paint)
        contours.forEachIndexed { ci, (pm, len) ->
            for (j in 0 until 2) {
                val head = ((run + ci * 0.31f + j * 0.5f) % 1f) * len
                val tail = len * 0.22f
                seg.reset()
                if (head >= tail) pm.getSegment(head - tail, head, seg, true)
                else { pm.getSegment(len - (tail - head), len, seg, true); pm.getSegment(0f, head, seg, true) }
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.maskFilter = android.graphics.BlurMaskFilter(4.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                paint.strokeWidth = 3.6.dp.toPx(); paint.color = goldLight.toArgb()
                nc.drawPath(seg, paint)
                paint.maskFilter = null
                paint.strokeWidth = 1.4.dp.toPx(); paint.color = android.graphics.Color.WHITE
                nc.drawPath(seg, paint)
            }
        }
        nc.restore()
        // scanning sheen across the package
        clipPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(androidx.compose.ui.geometry.Rect(pkgTl, pkgSz), cr)) }) {
            val sx = cx - H + scan * 2 * H
            drawRect(
                Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), Offset(sx - 30.dp.toPx(), ccy - H), Offset(sx + 30.dp.toPx(), ccy + H)),
                pkgTl, pkgSz,
            )
        }
    }
}
