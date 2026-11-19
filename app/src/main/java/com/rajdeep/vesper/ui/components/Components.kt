package com.rajdeep.vesper.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import kotlin.math.abs

/** Press feedback: a gentle spring scale-down plus a light haptic on click. */
fun Modifier.pressable(enabled: Boolean = true, scaleTo: Float = 0.96f, role: Role = Role.Button, haptic: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, Motion.spring(), label = "press")
    val fb = V.fb
    this.scale(s).clickable(src, indication = null, enabled = enabled, role = role) { if (haptic) fb.press(); onClick() }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, color: Color = V.accent, onClick: () -> Unit) {
    val bg by animateColorAsState(if (enabled) color else V.c.s3, Motion.tw(Motion.S), label = "btn")
    Box(
        modifier
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .pressable(enabled && !loading, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(loading, transitionSpec = { fadeIn(tween(Motion.S)) togetherWith fadeOut(tween(Motion.XS)) }, label = "btn-loading") { l ->
            if (l) PulseDots(color = V.c.onAccent) else Text(text, style = V.t.subhead, color = if (enabled) V.c.onAccent else V.c.text3)
        }
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = V.c.text, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, V.c.hairline, RoundedCornerShape(16.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = V.t.label.copy(fontSize = 14.sp), color = color)
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = V.c.text2, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).pressable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** Minimal field: floating label, hairline that warms to the accent on focus, inline helper/error line. */
@Composable
fun VField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    onIme: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val line by animateColorAsState(
        when { error != null -> V.c.error; focused -> V.accent; else -> V.c.hairline }, Motion.tw(Motion.S), label = "line",
    )
    val lift by animateFloatAsState(if (focused || value.isNotEmpty()) 1f else 0f, Motion.spring(), label = "lift")
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 62.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(V.c.s1)
                .border(1.dp, line, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
        ) {
            Text(
                label,
                style = V.t.bodySmall.copy(fontSize = (16 - 4 * lift).sp),
                color = if (focused) V.accent else V.c.text2,
                modifier = Modifier.align(Alignment.TopStart).padding(top = (20 - 11 * lift).dp),
            )
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = V.t.body.copy(color = V.c.text),
                    cursorBrush = SolidColor(V.accent),
                    interactionSource = src,
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation('•') else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onAny = {
                        if (onIme != null) onIme() else if (ime == ImeAction.Next) focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) else focus.clearFocus()
                    }),
                    modifier = Modifier.weight(1f).padding(top = 18.dp),
                )
                if (password) {
                    Text(
                        if (reveal) "Hide" else "Show", style = V.t.label, color = V.c.text2,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).focusProperties { canFocus = false }.pressable { reveal = !reveal }.padding(6.dp),
                    )
                }
                trailing?.invoke()
            }
        }
        AnimatedContent(error ?: helper, transitionSpec = { fadeIn(tween(Motion.S)) togetherWith fadeOut(tween(Motion.XS)) }, label = "helper") { msg ->
            if (msg != null) Text(msg, style = V.t.label, color = if (error != null) V.c.error else V.c.text3, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            else Spacer(Modifier.height(0.dp))
        }
    }
}

/** Deterministic, muted two-tone monogram — no uploaded avatars (zero storage). */
@Composable
fun Monogram(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val h = abs(name.hashCode())
    val hue = (h % 360).toFloat()
    val dark = V.c.dark
    val a = Color.hsl(hue, if (dark) 0.28f else 0.32f, if (dark) 0.30f else 0.74f)
    val b = Color.hsl((hue + 38) % 360, if (dark) 0.22f else 0.26f, if (dark) 0.20f else 0.64f)
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset.Infinite))
            .then(if (ring != null) Modifier.border(1.5.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "·",
            style = V.t.subhead.copy(fontSize = (size.value * 0.40f).sp, lineHeight = (size.value * 0.5f).sp),
            color = if (dark) Color(0xFFF1ECE4) else Color(0xFF231F19),
        )
    }
}

@Composable
fun StatusDot(online: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(if (online) V.c.success else V.c.text3, Motion.tw(Motion.M), label = "dot")
    Box(modifier.size(9.dp).clip(CircleShape).background(V.c.bg).padding(1.5.dp).clip(CircleShape).background(color))
}

@Composable
fun PulseDots(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(560, delayMillis = i * 140), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = a)))
        }
    }
}

@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = Motion.Standard)), label = "x")
    val base = V.c.s2
    val hi = V.c.s3
    Box(modifier.drawBehind {
        drawRect(Brush.linearGradient(listOf(base, hi, base), start = Offset(size.width * x, 0f), end = Offset(size.width * (x + 0.6f), size.height)))
    })
}

@Composable
fun SkeletonRows(count: Int = 6) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Shimmer(Modifier.size(40.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Shimmer(Modifier.height(12.dp).width((110 + (i * 37) % 90).dp).clip(RoundedCornerShape(6.dp)))
                    Shimmer(Modifier.height(10.dp).width((170 + (i * 53) % 110).dp).clip(RoundedCornerShape(6.dp)))
                }
            }
        }
    }
}

/** Thin ring that drains over a message's lifetime; dims in the final hour. */
@Composable
fun ExpiryRing(fraction: Float, color: Color, modifier: Modifier = Modifier.size(11.dp)) {
    val track = color.copy(alpha = 0.18f)
    Canvas(modifier) {
        val sw = 1.6.dp.toPx()
        drawCircle(track, style = Stroke(sw))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, glyph: @Composable () -> Unit = { DuskGlyph() }, action: @Composable (() -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        glyph()
        Spacer(Modifier.height(20.dp))
        Text(title, style = V.t.heading, color = V.c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = V.t.bodySmall, color = V.c.text2, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(20.dp)); it() }
    }
}

/** Evening star over a horizon — the Vesper mark, drawn so it can breathe. */
@Composable
fun DuskGlyph(size: Dp = 64.dp, color: Color = V.accent, breathe: Boolean = true) {
    val t = rememberInfiniteTransition(label = "glyph")
    val glow by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2600, easing = Motion.Standard), RepeatMode.Reverse), label = "g")
    val g = if (breathe) glow else 1f
    val line = V.c.text3
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val cx = w / 2
        drawCircle(color.copy(alpha = 0.12f * g), radius = w * 0.34f, center = Offset(cx, w * 0.38f))
        drawCircle(color.copy(alpha = g), radius = w * 0.06f, center = Offset(cx, w * 0.38f))
        val arc = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, w * 0.72f); quadraticTo(cx, w * 0.52f, w * 0.88f, w * 0.72f)
        }
        drawPath(arc, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = V.t.caption, color = V.c.text3, modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
fun Surface(modifier: Modifier = Modifier, radius: Dp = 20.dp, color: Color = V.c.s1, border: BorderStroke? = null, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(color)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(radius)) else Modifier),
    ) { content() }
}

@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.pressable(scaleTo = 0.985f, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(14.dp)) }
        content()
        trailing?.let { Spacer(Modifier.width(10.dp)); it() }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(V.c.hairline))

/** Toggle drawn in the palette (no stock switch). */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (checked) 1f else 0f, Motion.bouncy(), label = "toggle")
    val track by animateColorAsState(if (checked) V.accent else V.c.s3, Motion.tw(Motion.S), label = "track")
    val fb = V.fb
    Box(
        Modifier.size(width = 46.dp, height = 28.dp).clip(CircleShape).background(track)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Switch) { fb.tick(); onChange(!checked) }
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = (18 * x).dp).size(22.dp).clip(CircleShape).background(if (checked) V.c.onAccent else V.c.text2))
    }
}

/** Inline banner for connectivity / waking state. */
@Composable
fun Banner(visible: Boolean, text: String, color: Color = V.c.s2, textColor: Color = V.c.text2) {
    AnimatedVisibility(visible, enter = slideInVertically(Motion.tw()) { -it } + fadeIn(), exit = slideOutVertically(Motion.tw()) { -it } + fadeOut()) {
        Row(Modifier.fillMaxWidth().background(color).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDots(textColor, Modifier.scale(0.7f))
            Spacer(Modifier.width(8.dp))
            Text(text, style = V.t.label, color = textColor)
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, accentDot: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accentDot) { Box(Modifier.size(7.dp).clip(CircleShape).background(V.accent)); Spacer(Modifier.width(10.dp)) }
                Text(title, style = V.t.title, color = V.c.text)
            }
            subtitle?.let { Text(it, style = V.t.label, color = V.c.text2, modifier = Modifier.padding(start = if (accentDot) 17.dp else 0.dp, top = 2.dp)) }
        }
        actions()
    }
}

val BodyStyle: TextStyle @Composable get() = V.t.body

/** AnimatedVisibility without a layout-scope receiver (usable anywhere). */
@Composable
fun Reveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: androidx.compose.animation.EnterTransition = fadeIn(),
    exit: androidx.compose.animation.ExitTransition = fadeOut(),
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit,
) = AnimatedVisibility(visible = visible, modifier = modifier, enter = enter, exit = exit, content = content)

/** One-shot entrance: rise + fade + slight scale, staggered by [index] (capped) — used for list rows on first show. */
@Composable
fun Modifier.staggerIn(index: Int, key: Any? = Unit): Modifier {
    val p = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(key) {
        kotlinx.coroutines.delay(minOf(index, 10) * 32L)
        p.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 380f))
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * 18.dp.toPx()
        scaleX = 0.97f + 0.03f * v; scaleY = scaleX
    }
}

/** Entrance for a freshly sent/received chat bubble: springs up from its own corner. Old messages render still. */
@Composable
fun Modifier.bubbleIn(fresh: Boolean, mine: Boolean): Modifier {
    if (!fresh) return this
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { p.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f)) }
    return this.graphicsLayer {
        val v = p.value
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (mine) 1f else 0f, 1f)
        alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * 14.dp.toPx()
        scaleX = 0.9f + 0.1f * v; scaleY = scaleX
    }
}

/**
 * iOS-style spring overscroll for any scrollable child: rubber-band pull past the edges while dragging, and a spring
 * bounce when a fling hits the end. Pair with `overscrollEffect = null` on the list so the system stretch doesn't double up.
 */
@Composable
fun Modifier.springOverscroll(): Modifier {
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val conn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                val o = offset.value
                // While stretched, a drag back toward rest first relaxes the stretch.
                if (o != 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && kotlin.math.sign(available.y) != kotlin.math.sign(o)) {
                    val next = if (kotlin.math.abs(available.y) >= kotlin.math.abs(o)) 0f else o + available.y
                    scope.launch { offset.snapTo(next) }
                    return androidx.compose.ui.geometry.Offset(0f, next - o)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y != 0f) {
                    val o = offset.value
                    val resistance = 1f / (1f + kotlin.math.abs(o) / 140f) // rubber band
                    scope.launch { offset.snapTo(o + available.y * 0.5f * resistance) }
                    return available
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (offset.value != 0f) {
                    offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 260f))
                    return available
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (kotlin.math.abs(available.y) > 50f) {
                    // Fling hit an edge: overshoot proportional to leftover velocity, then settle.
                    offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 220f), initialVelocity = available.y * 0.35f)
                } else if (offset.value != 0f) {
                    offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 260f))
                }
                return available
            }
        }
    }
    return this.nestedScroll(conn).graphicsLayer { translationY = offset.value }
}

/**
 * Chat backdrop: deep tonal gradient tinted by the space accent, a faint dot grid, and sparse dim stars.
 * Kept very low-contrast so bubbles and text stay perfectly readable. Drawn once and cached.
 */
@Composable
fun ChatBackdrop(modifier: Modifier = Modifier) {
    val accent = V.accent
    val bg = V.c.bg
    val dark = V.c.dark
    val dot = V.c.text.copy(alpha = if (dark) 0.045f else 0.06f)
    androidx.compose.foundation.layout.Spacer(
        modifier.fillMaxSize().drawWithCache {
            val glowTop = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(accent.copy(alpha = if (dark) 0.10f else 0.08f), androidx.compose.ui.graphics.Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.85f, 0f), radius = size.width * 1.1f,
            )
            val glowBottom = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(accent.copy(alpha = if (dark) 0.06f else 0.05f), androidx.compose.ui.graphics.Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(0f, size.height), radius = size.width * 0.9f,
            )
            val step = 26.dp.toPx(); val r = 0.9.dp.toPx()
            val rnd = java.util.Random(7)
            val stars = List(42) { androidx.compose.ui.geometry.Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height) to (0.6f + rnd.nextFloat() * 1.1f) }
            onDrawBehind {
                drawRect(bg)
                drawRect(glowTop); drawRect(glowBottom)
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) { drawCircle(dot, r, androidx.compose.ui.geometry.Offset(x, y)); x += step }
                    y += step
                }
                stars.forEach { (p, sz) -> drawCircle(accent.copy(alpha = if (dark) 0.16f else 0.10f), sz.dp.toPx(), p) }
            }
        },
    )
}
