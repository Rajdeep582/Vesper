package com.rajdeep.vesper.ui.chat

import com.rajdeep.vesper.ui.components.bubbleIn
import com.rajdeep.vesper.ui.components.Reveal
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rajdeep.vesper.core.db.MessageEntity
import com.rajdeep.vesper.core.db.MsgState
import com.rajdeep.vesper.core.db.Scope
import com.rajdeep.vesper.media.BlurHash
import com.rajdeep.vesper.media.VoicePlayer
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.MediaRef
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.VesperJson
import com.rajdeep.vesper.ui.components.ExpiryRing
import com.rajdeep.vesper.ui.components.IconAction
import com.rajdeep.vesper.ui.components.Monogram
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Media access for bubbles: decrypt-on-demand and the shared voice player. */
interface MediaHost {
    suspend fun open(ref: MediaRef): ByteArray
    fun cached(mediaId: String): ByteArray?
    val player: VoicePlayer
}

val LocalMediaHost = staticCompositionLocalOf<MediaHost> { error("no media host") }

val MessageEntity.ref: MediaRef? get() = mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it) }.getOrNull() }

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
fun clock(ms: Long): String = timeFmt.format(Date(ms))
fun duration(ms: Long): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

/** Ticking "now" used for expiry rings; coarse to avoid needless recomposition. */
@Composable
fun rememberNow(periodMs: Long = 20_000, now: () -> Long): Long {
    val v by produceState(now()) { while (true) { kotlinx.coroutines.delay(periodMs); value = now() } }
    return v
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    m: MessageEntity,
    now: Long,
    showAvatar: Boolean,
    showName: Boolean,
    groupedWithPrev: Boolean,
    groupedWithNext: Boolean,
    onLongPress: () -> Unit,
    onImage: (MediaRef) -> Unit,
    onRetry: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {},
) {
    if (m.state == MsgState.SYSTEM) { SystemLine(m.text ?: ""); return }
    val mine = m.outgoing
    val kind = MessageKind.valueOf(m.kind)
    val ttl = Limits.ttlFor(kind)
    val remaining = m.expiresAt?.let { it - now }
    val fraction = remaining?.let { it.toFloat() / ttl } ?: 1f
    val fading = remaining != null && remaining < 3_600_000
    val bubbleAlpha by animateFloatAsState(if (fading) 0.82f else 1f, tween(Motion.L), label = "fade")
    val accent = V.accent
    val bg = if (mine) accent.copy(alpha = if (V.c.dark) 0.16f else 0.14f) else V.c.s2
    val big = 20.dp; val small = 6.dp
    val shape = if (mine) RoundedCornerShape(big, if (groupedWithPrev) small else big, if (groupedWithNext) small else small, big)
    else RoundedCornerShape(if (groupedWithPrev) small else big, big, big, if (groupedWithNext) small else small)

    val fresh = remember(m.localId) { kotlin.math.abs(System.currentTimeMillis() - m.createdAt) < 4_000 }
    val selTint by androidx.compose.animation.animateColorAsState(if (selected) V.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, tween(Motion.M), label = "selTint")
    val pressScale by animateFloatAsState(if (selected) 0.965f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 520f), label = "selScale")
    Row(
        Modifier.fillMaxWidth().background(selTint)
            .then(if (selecting) Modifier.combinedClickable(remember { MutableInteractionSource() }, null, onClick = onToggle) else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = if (groupedWithPrev) 2.dp else 10.dp, bottom = if (selecting) 2.dp else 0.dp)
            .then(Modifier.bubbleIn(fresh, mine)),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        com.rajdeep.vesper.ui.components.Reveal(
            selecting,
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 420f)) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally(tween(Motion.M)) + androidx.compose.animation.fadeOut(tween(Motion.S)),
        ) { SelectDot(selected, Modifier.padding(end = 10.dp, bottom = 6.dp)) }
        if (selecting && mine) Spacer(Modifier.weight(1f))
        if (!mine && showAvatar) {
            Box(Modifier.width(36.dp)) { if (!groupedWithNext) Monogram(m.senderName, 30.dp) }
        }
        val maxW = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.76f).dp.coerceAtMost(420.dp)
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = maxW)) {
            if (showName && !mine && !groupedWithPrev) {
                Text(m.senderName, style = V.t.label, color = accent.copy(alpha = 0.9f), modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
            }
            Box(
                Modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale }.alpha(bubbleAlpha).clip(shape).background(bg)
                    .then(if (selected) Modifier.border(1.5.dp, V.accent.copy(alpha = 0.7f), shape) else Modifier)
                    .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongPress, onClick = { if (selecting) onToggle() else if (m.state == MsgState.FAILED) onRetry() }),
            ) {
                when (kind) {
                    MessageKind.TEXT -> Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 7.dp)) {
                        Text(m.text ?: "", style = V.t.body, color = V.c.text)
                        Meta(m, fraction, Modifier.align(Alignment.End))
                    }
                    MessageKind.IMAGE -> Column {
                        m.ref?.let { ImageContent(it, onClick = { onImage(it) }) }
                        Meta(m, fraction, Modifier.align(Alignment.End).padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    MessageKind.VOICE -> Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                        m.ref?.let { VoiceContent(m.localId, it, mine) }
                        Meta(m, fraction, Modifier.align(Alignment.End))
                    }
                }
            }
            if (m.state == MsgState.FAILED) {
                Text(
                    (m.error ?: "Not sent") + " · tap to retry", style = V.t.label, color = V.c.error,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp).pressable(onClick = onRetry),
                )
            }
        }
    }
}

@Composable
private fun Meta(m: MessageEntity, fraction: Float, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(clock(m.acceptedAt ?: m.createdAt), style = V.t.mono.copy(fontSize = V.t.caption.fontSize), color = V.c.text3)
        if (m.outgoing) {
            // WhatsApp rules: clock = sending, ✓ = sent (server has it), ✓✓ = delivered, blue ✓✓ = seen. Hall stops at ✓.
            // Hall: one tick once the server has it — no delivered/seen there.
            val st = if (m.scope == Scope.HALL && (m.state == MsgState.DELIVERED || m.state == MsgState.READ)) MsgState.SENT else m.state
            val (icon, tint) = when (st) {
                MsgState.PENDING -> Icons.Rounded.Schedule to V.c.text3
                MsgState.SENT -> Icons.Rounded.Done to V.c.text3
                MsgState.DELIVERED -> Icons.Rounded.DoneAll to V.c.text3
                MsgState.READ -> Icons.Rounded.DoneAll to SeenBlue
                else -> Icons.Rounded.ErrorOutline to V.c.error
            }
            val animTint by animateColorAsState(tint, tween(Motion.L), label = "tick")
            AnimatedContent(icon, transitionSpec = { (scaleIn(Motion.bouncy(), initialScale = 0.4f) + fadeIn()) togetherWith fadeOut(tween(Motion.XS)) }, label = "state") { ic ->
                Icon(ic, when (st) { MsgState.PENDING -> "Sending"; MsgState.SENT -> "Sent"; MsgState.DELIVERED -> "Delivered"; MsgState.READ -> "Seen"; else -> "Not sent" },
                    tint = animTint, modifier = Modifier.size(15.dp))
            }
        }
    }
}

val SeenBlue = Color(0xFF53BDEB)

@Composable
private fun SystemLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = V.t.label, color = V.c.text2, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(V.c.s1).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun ImageContent(ref: MediaRef, onClick: () -> Unit) {
    val host = LocalMediaHost.current
    val ratio = ((ref.width ?: 4).toFloat() / (ref.height ?: 3).coerceAtLeast(1)).coerceIn(0.62f, 1.7f)
    val placeholder = remember(ref.blurhash) { ref.blurhash?.let { BlurHash.decode(it, 24, 24)?.asImageBitmap() } }
    var failed by remember(ref.mediaId) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, ref.mediaId) {
        if (ref.mediaId.isEmpty()) return@produceState
        value = runCatching {
            val bytes = host.open(ref)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.onFailure { failed = true }.getOrNull()
    }
    val reveal by animateFloatAsState(if (image != null) 1f else 0f, tween(Motion.L, easing = Motion.Emphasized), label = "reveal")
    val w = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.66f).dp.coerceAtMost(300.dp)
    Box(Modifier.width(w).aspectRatio(ratio).background(V.c.s3).pressable(scaleTo = 0.98f, onClick = onClick)) {
        placeholder?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        image?.let { Image(it, "Photo", Modifier.fillMaxSize().graphicsLayer { alpha = reveal; scaleX = 1.04f - 0.04f * reveal; scaleY = scaleX }, contentScale = ContentScale.Crop) }
        if (image == null) {
            Box(Modifier.align(Alignment.Center)) {
                if (failed) Text("Unavailable", style = V.t.label, color = V.c.text2)
                else com.rajdeep.vesper.ui.components.PulseDots(V.c.text.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun VoiceContent(localId: String, ref: MediaRef, mine: Boolean) {
    val host = LocalMediaHost.current
    val state by host.player.state.collectAsState()
    val active = state.id == ref.mediaId
    val playing = active && state.playing
    val dur = ref.durationMs ?: 0L
    val rawProgress = if (active && dur > 0) (state.positionMs.toFloat() / (if (state.durationMs > 0) state.durationMs else dur)).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(rawProgress, if (playing) tween(260, easing = androidx.compose.animation.core.LinearEasing) else Motion.spring(), label = "progress")
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fb = V.fb
    val accent = V.accent
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(accent, lerp(accent, Color.Black, 0.25f))))
                .pressable(enabled = ref.mediaId.isNotEmpty()) {
                    scope.launch {
                        loading = true
                        runCatching { host.open(ref) }.onSuccess { host.player.play(ref.mediaId, it, dur) }.onFailure { fb.error() }
                        loading = false
                    }
                }.semantics { contentDescription = if (playing) "Pause voice message" else "Play voice message" },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(if (loading) 2 else if (playing) 1 else 0, transitionSpec = { scaleIn(Motion.bouncy()) togetherWith scaleOut(tween(Motion.XS)) }, label = "play") { s ->
                when (s) {
                    2 -> com.rajdeep.vesper.ui.components.PulseDots(V.c.onAccent, Modifier.scale(0.55f))
                    1 -> Icon(Icons.Rounded.Pause, null, tint = V.c.onAccent)
                    else -> Icon(Icons.Rounded.PlayArrow, null, tint = V.c.onAccent)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Waveform(ref.waveform ?: List(48) { 30 }, progress, accent, V.c.text3, Modifier.width(140.dp).height(30.dp)) { f ->
                fb.tick(); host.player.seek(ref.mediaId, f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(duration(if (active && (playing || state.positionMs > 0)) state.positionMs else dur), style = V.t.mono, color = V.c.text2)
                Reveal(active, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                    Text(
                        "${state.speed.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}×",
                        style = V.t.mono.copy(fontWeight = FontWeight.Medium), color = accent,
                        modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(6.dp)).pressable { host.player.cycleSpeed() }.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun Waveform(levels: List<Int>, progress: Float, active: Color, idle: Color, modifier: Modifier, onSeek: ((Float) -> Unit)? = null) {
    var w by remember { mutableFloatStateOf(1f) }
    Canvas(
        modifier.then(
            if (onSeek != null) Modifier.pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }.pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) }
            } else Modifier,
        ),
    ) {
        w = size.width
        val n = levels.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        levels.forEachIndexed { i, l ->
            val h = (size.height * (0.14f + 0.86f * (l / 100f))).coerceAtLeast(2f)
            val x = i * (bw + gap)
            val played = (i + 0.5f) / n <= progress
            drawRoundRect(if (played) active else idle, Offset(x, (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2))
        }
    }
}

// ------------------------------------------------------------------ composer

sealed interface RecordUi {
    data object Idle : RecordUi
    data class Holding(val dragX: Float, val dragY: Float) : RecordUi
    data object Locked : RecordUi
}

@Composable
fun Composer(
    enabled: Boolean,
    disabledReason: String?,
    placeholder: String,
    level: Float,
    elapsedMs: Long,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onRecordStart: () -> Boolean,
    onRecordFinish: (send: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var rec by remember { mutableStateOf<RecordUi>(RecordUi.Idle) }
    val fb = V.fb
    val accent = V.accent
    val density = LocalDensity.current
    val cancelPx = with(density) { 110.dp.toPx() }
    val lockPx = with(density) { 90.dp.toPx() }
    if (!enabled) {
        Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(V.c.s1).padding(16.dp), contentAlignment = Alignment.Center) {
            Text(disabledReason ?: "You can't send messages here right now", style = V.t.bodySmall, color = V.c.text2)
        }
        return
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedContent(rec == RecordUi.Idle, transitionSpec = { fadeIn(tween(Motion.S)) togetherWith fadeOut(tween(Motion.XS)) }, modifier = Modifier.weight(1f), label = "composer") { idle ->
                if (idle) {
                    val focus = remember { MutableInteractionSource() }
                    val focused by focus.collectIsFocusedAsState()
                    val ring by animateColorAsState(if (focused) accent.copy(alpha = 0.55f) else V.c.hairline, tween(Motion.M), label = "ring")
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp))
                            .background(Brush.verticalGradient(listOf(V.c.s2, V.c.s1)))
                            .border(1.dp, ring, RoundedCornerShape(25.dp))
                            .animateContentSize(Motion.spring())
                            .padding(start = 4.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAction(Icons.Rounded.Add, "Attach photo", tint = V.c.text2, size = 42.dp, onClick = onAttach)
                        Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                            if (text.isEmpty()) Text(placeholder, style = V.t.body, color = V.c.text3)
                            BasicTextField(
                                text, { if (it.length <= Limits.MAX_TEXT_CHARS * 3) text = it },
                                textStyle = V.t.body.copy(color = V.c.text), cursorBrush = SolidColor(accent), maxLines = 6,
                                interactionSource = focus,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    RecordingStrip(rec, level, elapsedMs, cancelPx, onCancel = { rec = RecordUi.Idle; fb.error(); onRecordFinish(false) })
                }
            }
            Spacer(Modifier.width(8.dp))
            val hasText = text.isNotBlank()
            val locked = rec == RecordUi.Locked
            val holding = rec is RecordUi.Holding
            val grow by animateFloatAsState(if (holding) 1.28f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 420f), label = "grow")
            val halo by animateFloatAsState(if (holding || locked) 1f + level.coerceIn(0f, 1f) * 0.9f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f), label = "halo")
            Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            // Voice halo: breathes with the mic level while recording.
            Box(Modifier.size(50.dp).graphicsLayer { scaleX = halo * grow; scaleY = halo * grow; alpha = if (holding || locked) 0.22f else 0f }.clip(CircleShape).background(accent))
            Box(
                Modifier.size(50.dp)
                    .offset { val r = rec; if (r is RecordUi.Holding) IntOffset(r.dragX.roundToInt().coerceAtMost(0), r.dragY.roundToInt().coerceAtMost(0)) else IntOffset.Zero }
                    .graphicsLayer { scaleX = grow; scaleY = grow }
                    .clip(CircleShape).background(Brush.linearGradient(listOf(accent, lerp(accent, Color.Black, 0.28f))))
                    .semantics { contentDescription = if (hasText || locked) "Send" else "Hold to record a voice message" }
                    .pointerInput(hasText, locked) {
                        if (hasText || locked) {
                            detectTapGestures {
                                if (locked) { rec = RecordUi.Idle; fb.recordStop(); onRecordFinish(true) }
                                else { fb.send(); onSend(text); text = "" }
                            }
                        } else {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!onRecordStart()) { fb.error(); return@awaitEachGesture }
                                fb.recordStart()
                                rec = RecordUi.Holding(0f, 0f)
                                var lockedNow = false
                                var cancelled = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = ch.position.x - down.position.x
                                    val dy = ch.position.y - down.position.y
                                    if (!ch.pressed) break
                                    if (dx < -cancelPx) { cancelled = true; break }
                                    if (dy < -lockPx) { lockedNow = true; fb.tick(); break }
                                    rec = RecordUi.Holding(dx, dy)
                                    ch.consume()
                                }
                                when {
                                    cancelled -> { rec = RecordUi.Idle; fb.error(); onRecordFinish(false) }
                                    lockedNow -> rec = RecordUi.Locked
                                    else -> { rec = RecordUi.Idle; fb.recordStop(); onRecordFinish(true) }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(if (hasText || locked) 1 else 0, transitionSpec = {
                    (scaleIn(Motion.bouncy(), initialScale = 0.3f) + fadeIn() + androidx.compose.animation.slideInVertically(Motion.spring()) { it / 2 }) togetherWith
                        (scaleOut(tween(Motion.XS), targetScale = 0.3f) + fadeOut(tween(Motion.XS)))
                }, label = "action") { s ->
                    val tilt by animateFloatAsState(if (s == 1) -18f else 0f, Motion.spring(), label = "tilt")
                    Icon(if (s == 1) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic, null, tint = V.c.onAccent, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = tilt })
                }
            }
            }
        }
        // Lock hint floats above the mic while holding.
        Reveal(rec is RecordUi.Holding, modifier = Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-64).dp), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            val dy = (rec as? RecordUi.Holding)?.dragY ?: 0f
            val p = (-dy / lockPx).coerceIn(0f, 1f)
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(V.c.s3).padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.Lock, "Slide up to lock", tint = if (p > 0.9f) accent else V.c.text2, modifier = Modifier.size(18.dp).offset(y = (-8 * p).dp))
            }
        }
    }
}

@Composable
private fun RecordingStrip(rec: RecordUi, level: Float, elapsedMs: Long, cancelPx: Float, onCancel: () -> Unit) {
    val dx = (rec as? RecordUi.Holding)?.dragX ?: 0f
    val cancelP = (-dx / cancelPx).coerceIn(0f, 1f)
    val history = remember { ArrayDeque<Int>() }
    LaunchedEffect(level) { history.addLast((level * 100).roundToInt()); while (history.size > 40) history.removeFirst() }
    val dot by animateColorAsState(if (elapsedMs / 500 % 2 == 0L) V.c.error else V.c.error.copy(alpha = 0.35f), tween(240), label = "rec")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(V.c.s2).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(duration(elapsedMs), style = V.t.mono, color = V.c.text)
        Spacer(Modifier.width(12.dp))
        Waveform(history.toList().ifEmpty { listOf(0) }, 1f, V.accent.copy(alpha = 1f - cancelP * 0.7f), V.c.text3, Modifier.weight(1f).height(26.dp))
        Spacer(Modifier.width(12.dp))
        if (rec == RecordUi.Locked) {
            Icon(Icons.Rounded.Stop, "Discard recording", tint = V.c.error, modifier = Modifier.size(28.dp).clip(CircleShape).pressable(onClick = onCancel))
        } else {
            Text("‹ slide to cancel", style = V.t.label, color = V.c.text2.copy(alpha = 1f - cancelP), modifier = Modifier.graphicsLayer { translationX = dx * 0.4f })
        }
    }
}


/** Selection marker: hollow ring that springs into a filled accent disc with a check. */
@Composable
fun SelectDot(selected: Boolean, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 600f), label = "dot")
    Box(
        modifier.size(22.dp).clip(CircleShape)
            .border(1.5.dp, if (selected) V.accent else V.c.text3, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).graphicsLayer { scaleX = fill; scaleY = fill; alpha = fill.coerceIn(0f, 1f) }.clip(CircleShape).background(V.accent), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = V.c.bg, modifier = Modifier.size(14.dp))
        }
    }
}
