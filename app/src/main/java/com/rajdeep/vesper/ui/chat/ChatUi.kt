package com.rajdeep.vesper.ui.chat

import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
val MessageEntity.replyRef: com.rajdeep.vesper.shared.ReplyRef? get() = replyJson?.let { runCatching { VesperJson.decodeFromString(com.rajdeep.vesper.shared.ReplyRef.serializer(), it) }.getOrNull() }

fun MessageEntity.toReplyRef(): com.rajdeep.vesper.shared.ReplyRef = com.rajdeep.vesper.shared.ReplyRef(
    messageId = serverId ?: localId,
    senderName = senderName,
    text = text,
    kind = MessageKind.valueOf(kind),
)

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
    onReply: ((MessageEntity) -> Unit)? = null,
    onJumpToMessage: ((String) -> Unit)? = null,
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

    val swipeOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fb = V.fb
    val density = androidx.compose.ui.platform.LocalDensity.current
    val triggerPx = with(density) { 56.dp.toPx() }
    var triggered by remember { mutableStateOf(false) }

    val draggableState = rememberDraggableState { delta ->
        val current = swipeOffset.value
        // Swipe right only (like WhatsApp). Past the trigger, resistance grows — a rubber-band feel.
        val resistance = if (current < triggerPx) 0.75f else 0.75f / (1f + (current - triggerPx) / (triggerPx * 0.6f))
        val newOffset = (current + delta * resistance).coerceIn(0f, triggerPx * 1.8f)
        scope.launch { swipeOffset.snapTo(newOffset) }
        val passed = kotlin.math.abs(newOffset) >= triggerPx
        if (passed && !triggered) {
            triggered = true
            fb.tick()
        } else if (!passed && triggered) {
            triggered = false
        }
    }

    val swipeDraggable = if (onReply != null && !selecting && m.state != MsgState.FAILED) {
        Modifier.draggable(
            state = draggableState,
            orientation = Orientation.Horizontal,
            onDragStarted = { triggered = false },
            onDragStopped = { velocity ->
                if (triggered) {
                    fb.press()
                    onReply(m)
                }
                triggered = false
                // Snap home with a little overshoot, carrying the release velocity.
                scope.launch { swipeOffset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.58f, stiffness = 520f), initialVelocity = velocity * 0.4f) }
            }
        )
    } else Modifier

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
        val drag = swipeOffset.value
        val isSwiping = kotlin.math.abs(drag) > 2f
        val swipeFraction = (kotlin.math.abs(drag) / triggerPx).coerceIn(0f, 1f)

        Box(contentAlignment = if (drag >= 0) Alignment.CenterStart else Alignment.CenterEnd) {
            if (isSwiping) {
                val iconScale by animateFloatAsState(if (triggered) 1.25f else (swipeFraction * 0.95f), Motion.spring(), label = "replyIconScale")
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(36.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                            alpha = swipeFraction
                        }
                        .clip(CircleShape)
                        .background(if (triggered) V.accent else V.c.s3),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Reply,
                        contentDescription = "Reply",
                        tint = if (triggered) V.c.onAccent else V.c.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.graphicsLayer { translationX = drag },
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                if (showName && !mine && !groupedWithPrev) {
                    Text(m.senderName, style = V.t.label, color = accent.copy(alpha = 0.9f), modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
                }
                Box(
                    Modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                        .alpha(bubbleAlpha)
                        .widthIn(max = maxW)
                        .clip(shape)
                        .background(bg)
                        .then(if (selected) Modifier.border(1.5.dp, V.accent.copy(alpha = 0.7f), shape) else Modifier)
                        .then(swipeDraggable)
                        .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongPress, onClick = { if (selecting) onToggle() else if (m.state == MsgState.FAILED) onRetry() }),
                ) {
                    // WhatsApp-style text layout: the time sits in the bottom-right corner — on the last line when it fits, else on its own line.
                    val textMeasurer = rememberTextMeasurer()
                    val q2 = V.c.text2; val s3 = V.c.s3
                    val bodyStyle = V.t.body
                    val metaStyle = V.t.mono.copy(fontSize = V.t.caption.fontSize)
                    val shown = remember(m.text, accent, q2) { if (kind == MessageKind.TEXT) com.rajdeep.vesper.ui.text.formatMessage(m.text ?: "", com.rajdeep.vesper.ui.theme.Mono, accent, q2, s3) else null }
                    val padH = 26.dp
                    val textPlan = remember(shown, maxW, bodyStyle, m.outgoing, m.acceptedAt, m.createdAt, m.replyRef != null, density) {
                        if (shown == null) null else with(density) {
                            val availPx = (maxW - padH).toPx().toInt().coerceAtLeast(1)
                            val layout = textMeasurer.measure(shown, bodyStyle, constraints = Constraints(maxWidth = availPx))
                            val clockW = textMeasurer.measure(clock(m.acceptedAt ?: m.createdAt), metaStyle).size.width.toFloat()
                            val forever = m.text != null && com.rajdeep.vesper.ui.text.MessageText.isForever(m.text)
                            val metaW = clockW + (if (m.outgoing) (15.dp + 5.dp).toPx() else 0f) + (if (forever) (14.dp + 5.dp).toPx() else 0f)
                            val last = layout.lineCount - 1
                            val lastW = layout.getLineRight(last) - layout.getLineLeft(last)
                            val maxLine = (0..last).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
                            val gap = 10.dp.toPx()
                            val inline = lastW + gap + metaW <= availPx
                            var content = if (inline) maxOf(maxLine, lastW + gap + metaW) else maxOf(maxLine, metaW)
                            if (m.replyRef != null) content = maxOf(content, 168.dp.toPx())
                            inline to (kotlin.math.ceil(content).toDp() + 2.dp + padH).coerceAtMost(maxW)
                        }
                    }
                    Column(if (textPlan != null) Modifier.width(textPlan.second) else Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                        m.replyRef?.let { rep ->
                            QuotedReplyCard(rep) { onJumpToMessage?.invoke(rep.messageId) }
                        }
                        when (kind) {
                            MessageKind.TEXT -> {
                                val inline = textPlan?.first == true
                                Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = if (m.replyRef != null) 3.dp else 8.dp, bottom = 7.dp)) {
                                    if (inline) {
                                        Text(shown!!, style = bodyStyle, color = V.c.text, modifier = Modifier.align(Alignment.TopStart))
                                        Meta(m, fraction, Modifier.align(Alignment.BottomEnd).offset(y = 3.dp))
                                    } else Column(Modifier.fillMaxWidth()) {
                                        Text(shown!!, style = bodyStyle, color = V.c.text)
                                        Meta(m, fraction, Modifier.align(Alignment.End))
                                    }
                                }
                            }
                            MessageKind.IMAGE -> Column {
                                m.ref?.let { ImageContent(it, onClick = { onImage(it) }, onLongPress = onLongPress) }
                                Meta(m, fraction, Modifier.align(Alignment.End).padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                            MessageKind.VOICE -> Column(Modifier.padding(start = 8.dp, end = 12.dp, top = if (m.replyRef != null) 3.dp else 8.dp, bottom = 6.dp)) {
                                m.ref?.let { VoiceContent(m.localId, it, mine, onLongPress = onLongPress) }
                                Meta(m, fraction, Modifier.align(Alignment.End))
                            }
                        }
                    }
                    // Selection mode: one shield over the whole bubble so text, photo and voice all toggle the same way.
                    if (selecting) Box(
                        Modifier.matchParentSize().combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onToggle, onClick = onToggle)
                    )
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
}

@Composable
private fun Meta(m: MessageEntity, fraction: Float, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (m.text != null && com.rajdeep.vesper.ui.text.MessageText.isForever(m.text)) {
            Icon(androidx.compose.material.icons.Icons.Rounded.AllInclusive, "Kept forever", tint = V.accent, modifier = Modifier.size(14.dp))
        }
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
private fun QuotedReplyCard(reply: com.rajdeep.vesper.shared.ReplyRef, onClick: () -> Unit) {
    val accent = V.accent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .pressable(scaleTo = 0.98f, onClick = onClick)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.5.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                text = reply.senderName,
                style = V.t.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
            )
            val sub = when (reply.kind) {
                MessageKind.TEXT -> reply.text ?: ""
                MessageKind.VOICE -> "Voice message"
                MessageKind.IMAGE -> "Photo"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reply.kind == MessageKind.VOICE) {
                    Icon(Icons.Rounded.Mic, null, tint = V.c.text3, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                } else if (reply.kind == MessageKind.IMAGE) {
                    Icon(Icons.Rounded.Image, null, tint = V.c.text3, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = sub,
                    style = V.t.caption.copy(fontSize = 11.sp),
                    color = V.c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ReplyPreviewBar(
    reply: MessageEntity,
    onDismiss: () -> Unit,
) {
    val accent = V.accent
    val mine = reply.outgoing
    val name = if (mine) "You" else reply.senderName
    val kind = MessageKind.valueOf(reply.kind)
    val textSnippet = when (kind) {
        MessageKind.TEXT -> reply.text ?: ""
        MessageKind.VOICE -> "Voice message" + (reply.ref?.durationMs?.let { " (${duration(it)})" } ?: "")
        MessageKind.IMAGE -> "Photo"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(V.c.s2)
            .border(0.5.dp, V.c.hairline, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.5.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Replying to $name",
                style = V.t.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = accent,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kind == MessageKind.VOICE) {
                    Icon(Icons.Rounded.Mic, null, tint = V.c.text3, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                } else if (kind == MessageKind.IMAGE) {
                    Icon(Icons.Rounded.Image, null, tint = V.c.text3, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                }
                val c2 = V.c.text2; val c3 = V.c.s3
                Text(
                    text = remember(textSnippet) { com.rajdeep.vesper.ui.text.formatMessage(textSnippet, com.rajdeep.vesper.ui.theme.Mono, accent, c2, c3) },
                    style = V.t.caption.copy(fontSize = 12.sp),
                    color = V.c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(V.c.s3.copy(alpha = 0.6f))
                .pressable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Cancel reply", tint = V.c.text2, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SystemLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = V.t.label, color = V.c.text2, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(V.c.s1).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ImageContent(ref: MediaRef, onClick: () -> Unit, onLongPress: () -> Unit = {}) {
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
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val pScale by animateFloatAsState(if (down) 0.975f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 700f), label = "imgPress")
    Box(Modifier.width(w).aspectRatio(ratio).graphicsLayer { scaleX = pScale; scaleY = pScale }.background(V.c.s3).combinedClickable(src, null, onClick = onClick, onLongClick = onLongPress)) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceContent(localId: String, ref: MediaRef, mine: Boolean, onLongPress: (() -> Unit)? = null) {
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
                .combinedClickable(
                    enabled = ref.mediaId.isNotEmpty(),
                    onClick = {
                        if (playing) {
                            fb.tick()
                            host.player.pause()
                        } else {
                            scope.launch {
                                loading = true
                                runCatching { host.open(ref) }
                                    .onSuccess { bytes ->
                                        host.player.play(ref.mediaId, bytes, dur)
                                    }
                                    .onFailure { fb.error() }
                                loading = false
                            }
                        }
                    },
                    onLongClick = onLongPress,
                )
                .semantics { contentDescription = if (playing) "Pause voice message" else "Play voice message" },
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
            Waveform(ref.waveform ?: List(48) { 30 }, progress, accent, V.c.text3.copy(alpha = 0.45f), Modifier.width(140.dp).height(30.dp), onLongPress = onLongPress) { f ->
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
fun Waveform(levels: List<Int>, progress: Float, active: Color, idle: Color, modifier: Modifier, onLongPress: (() -> Unit)? = null, onSeek: ((Float) -> Unit)? = null) {
    var w by remember { mutableFloatStateOf(1f) }
    // Scrubbing previews locally while the finger moves and seeks once on release — no stutter from seek spam.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fbW = V.fb
    val thumb by animateFloatAsState(if (scrub != null) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 600f), label = "thumb")
    val shown = scrub ?: progress
    val longPressNow by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    val seekNow by androidx.compose.runtime.rememberUpdatedState(onSeek)
    Canvas(
        modifier.then(
            if (onSeek != null || onLongPress != null) Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap = { fbW.tick(); seekNow?.invoke((it.x / size.width).coerceIn(0f, 1f)) },
                    onLongPress = { longPressNow?.invoke() },
                )
            }.then(
                if (onSeek != null) Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f); fbW.tick() },
                        onDragEnd = { scrub?.let { f -> seekNow?.invoke(f) }; scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ -> change.consume(); scrub = (change.position.x / size.width).coerceIn(0f, 1f) }
                } else Modifier
            ) else Modifier,
        ),
    ) {
        w = size.width
        val n = levels.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        levels.forEachIndexed { i, l ->
            val h = (size.height * (0.14f + 0.86f * (l / 100f))).coerceAtLeast(2f)
            val x = i * (bw + gap)
            val played = (i + 0.5f) / n <= shown
            // Bars near the finger lift slightly while scrubbing.
            val near = if (scrub != null) (1f - kotlin.math.abs((i + 0.5f) / n - shown) * 8f).coerceIn(0f, 1f) * thumb else 0f
            val hh = (h * (1f + 0.35f * near)).coerceAtMost(size.height)
            drawRoundRect(if (played) active else idle, Offset(x, (size.height - hh) / 2), Size(bw, hh), CornerRadius(bw / 2))
        }
        if (thumb > 0.01f) drawCircle(active, 6.dp.toPx() * thumb, Offset(shown * size.width, size.height / 2))
    }
}

// ------------------------------------------------------------------ composer

sealed interface RecordUi {
    data object Idle : RecordUi
    data class Holding(val dragX: Float, val dragY: Float) : RecordUi
    data object Locked : RecordUi
    data object Paused : RecordUi
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
    onRecordPause: () -> Boolean = { false },
    onRecordResume: () -> Boolean = { false },
    onRecordFinish: (send: Boolean) -> Unit,
    getPreviewBytes: () -> ByteArray? = { null },
    getRecordedWaveform: () -> List<Int> = { emptyList() },
    onTyping: (Boolean) -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    val typingNow by androidx.compose.runtime.rememberUpdatedState(onTyping)
    // Each keystroke restarts this: "typing" while active, "stopped" after 4 s idle or when cleared/sent.
    LaunchedEffect(text) {
        if (text.isNotBlank()) { typingNow(true); kotlinx.coroutines.delay(4_000); typingNow(false) } else typingNow(false)
    }
    var rec by remember { mutableStateOf<RecordUi>(RecordUi.Idle) }
    var isTouching by remember { mutableStateOf(false) }
    // WhatsApp-style hint after a too-short tap on the mic.
    var holdHint by remember { mutableStateOf(0) }
    LaunchedEffect(holdHint) { if (holdHint > 0) { kotlinx.coroutines.delay(1800); holdHint = 0 } }
    val fb = V.fb
    val accent = V.accent
    val density = LocalDensity.current
    val cancelPx = with(density) { 75.dp.toPx() }
    val lockPx = with(density) { 96.dp.toPx() }

    val currentOnRecordStart by rememberUpdatedState(onRecordStart)
    val currentOnRecordPause by rememberUpdatedState(onRecordPause)
    val currentOnRecordResume by rememberUpdatedState(onRecordResume)
    val currentOnRecordFinish by rememberUpdatedState(onRecordFinish)

    val player = LocalMediaHost.current.player
    val playerState by player.state.collectAsState()
    val isPlayingPreview = playerState.id == "rec_preview" && playerState.playing

    // Live audio level history for recording visualizer
    val liveHistory = remember { ArrayDeque<Int>() }
    LaunchedEffect(level, rec) {
        if (rec is RecordUi.Holding || rec == RecordUi.Locked) {
            liveHistory.addLast((level * 100).roundToInt())
            while (liveHistory.size > 36) liveHistory.removeFirst()
        }
    }

    if (!enabled) {
        Box(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(V.c.s1).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(disabledReason ?: "You can't send messages here right now", style = V.t.bodySmall, color = V.c.text2)
        }
        return
    }

    val cmdQuery = text.takeIf { it.startsWith("/") && !it.contains(' ') && !it.contains('\n') }
    val matches = cmdQuery?.let { q -> com.rajdeep.vesper.ui.text.SLASH_COMMANDS.filter { it.name.startsWith(q, ignoreCase = true) } }.orEmpty()

    Column(Modifier.fillMaxWidth()) {
        com.rajdeep.vesper.ui.components.Reveal(
            matches.isNotEmpty(),
            enter = fadeIn(tween(Motion.S)) + androidx.compose.animation.expandVertically(Motion.spring()),
            exit = fadeOut(tween(Motion.XS)) + androidx.compose.animation.shrinkVertically(tween(Motion.S)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp).clip(RoundedCornerShape(20.dp))
                    .background(V.c.s2).border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp)).padding(vertical = 6.dp),
            ) {
                matches.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().pressable(scaleTo = 0.98f) { fb.tick(); text = c.name + if (c.args.isNotEmpty()) " " else "" }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.name, style = V.t.mono.copy(fontSize = V.t.bodySmall.fontSize, fontWeight = FontWeight.Medium), color = accent)
                        if (c.args.isNotEmpty()) Text("  " + c.args, style = V.t.mono.copy(fontSize = V.t.label.fontSize), color = V.c.text3)
                        Spacer(Modifier.width(12.dp))
                        Text(c.summary, style = V.t.label, color = V.c.text2, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            AnimatedContent(
                targetState = when (rec) {
                    RecordUi.Locked -> 1
                    RecordUi.Paused -> 2
                    else -> 0
                },
                transitionSpec = {
                    (fadeIn(tween(Motion.S)) + scaleIn(Motion.spring(), initialScale = 0.95f)) togetherWith
                        (fadeOut(tween(Motion.XS)) + scaleOut(tween(Motion.XS), targetScale = 0.95f))
                },
                label = "composer_bar",
            ) { mode ->
                when (mode) {
                    1 -> androidx.compose.runtime.CompositionLocalProvider(LocalLiveLevel provides level) {
                        LockedRecordingBar(
                            elapsedMs = elapsedMs,
                            liveHistory = liveHistory.toList().ifEmpty { listOf(15, 30, 20) },
                            onDelete = {
                                fb.error()
                                currentOnRecordFinish(false)
                                rec = RecordUi.Idle
                            },
                            onPause = {
                                fb.tick()
                                currentOnRecordPause()
                                rec = RecordUi.Paused
                            },
                            onSend = {
                                if (elapsedMs < 1000L) {
                                    fb.error()
                                    currentOnRecordFinish(false)
                                    rec = RecordUi.Idle
                                } else {
                                    currentOnRecordFinish(true)
                                    fb.send()
                                    rec = RecordUi.Idle
                                }
                            },
                        )
                    }
                    2 -> {
                        PausedRecordingBar(
                            elapsedMs = elapsedMs,
                            recordedWaveform = getRecordedWaveform().ifEmpty { liveHistory.toList().ifEmpty { listOf(20, 45, 60, 30, 75, 40, 25) } },
                            isPlayingPreview = isPlayingPreview,
                            previewPositionMs = playerState.positionMs,
                            previewDurationMs = if (playerState.durationMs > 0) playerState.durationMs else elapsedMs,
                            onTogglePreview = {
                                fb.tick()
                                if (isPlayingPreview) {
                                    player.pause()
                                } else {
                                    val bytes = getPreviewBytes()
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        player.play("rec_preview", bytes, elapsedMs)
                                    }
                                }
                            },
                            onDelete = {
                                player.stop()
                                fb.error()
                                currentOnRecordFinish(false)
                                rec = RecordUi.Idle
                            },
                            onResume = {
                                player.stop()
                                fb.tick()
                                currentOnRecordResume()
                                rec = RecordUi.Locked
                            },
                            onSend = {
                                player.stop()
                                if (elapsedMs < 1000L) {
                                    fb.error()
                                    currentOnRecordFinish(false)
                                    rec = RecordUi.Idle
                                } else {
                                    currentOnRecordFinish(true)
                                    fb.send()
                                    rec = RecordUi.Idle
                                }
                            },
                        )
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnimatedContent(
                                rec == RecordUi.Idle,
                                transitionSpec = {
                                    if (targetState) (fadeIn(tween(Motion.M)) + scaleIn(Motion.spring(), initialScale = 0.97f)) togetherWith (fadeOut(tween(Motion.S)) + androidx.compose.animation.slideOutHorizontally(tween(Motion.S)) { it / 6 })
                                    else (fadeIn(tween(Motion.S)) + androidx.compose.animation.slideInHorizontally(Motion.spring()) { it / 4 }) togetherWith fadeOut(tween(Motion.XS))
                                },
                                modifier = Modifier.weight(1f),
                                label = "input_or_strip",
                            ) { idle ->
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
                                    HoldingRecordingStrip(
                                        dragX = (rec as? RecordUi.Holding)?.dragX ?: 0f,
                                        level = level,
                                        elapsedMs = elapsedMs,
                                        cancelPx = cancelPx,
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))
                            val hasText = text.isNotBlank()
                            val holding = rec is RecordUi.Holding
                            val grow by animateFloatAsState(
                                when {
                                    holding -> 1.38f
                                    isTouching -> 1.10f
                                    else -> 1f
                                },
                                androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 420f),
                                label = "grow",
                            )
                            val halo by animateFloatAsState(if (holding) 1f + level.coerceIn(0f, 1f) * 0.85f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f), label = "halo")
                            val dragOffset = (rec as? RecordUi.Holding)?.let {
                                IntOffset(
                                    // Mic tracks the finger: left toward cancel, up toward the lock (dominant axis only).
                                    if (-it.dragX > -it.dragY) (it.dragX * 0.85f).roundToInt().coerceIn(-cancelPx.roundToInt(), 0) else 0,
                                    if (-it.dragY >= -it.dragX) (it.dragY * 0.75f).roundToInt().coerceIn(-lockPx.roundToInt(), 0) else 0,
                                )
                            } ?: IntOffset.Zero
                            // Smooth the finger-follow (and the snap home on release) instead of jumping per touch sample.
                            val micOffset by androidx.compose.animation.core.animateIntOffsetAsState(
                                dragOffset,
                                androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = if (holding) 1400f else 500f),
                                label = "micOffset",
                            )
                            val ripple = androidx.compose.animation.core.rememberInfiniteTransition(label = "ripple")
                            val r1 by ripple.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "r1")
                            val sendFly = remember { androidx.compose.animation.core.Animatable(0f) }
                            val flyScope = rememberCoroutineScope()

                            Box(
                                Modifier.size(50.dp)
                                    .semantics { contentDescription = if (hasText) "Send" else "Hold to record a voice message" }
                                    .pointerInput(hasText) {
                                        if (hasText) {
                                            detectTapGestures {
                                                fb.send()
                                                onSend(text)
                                                text = ""
                                                // Paper plane takes off, a fresh one pops back in.
                                                flyScope.launch { sendFly.snapTo(0f); sendFly.animateTo(1f, tween(320, easing = Motion.Emphasized)); sendFly.snapTo(0f) }
                                            }
                                        } else {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val downUptime = android.os.SystemClock.uptimeMillis()
                                                // Starts almost instantly (like WhatsApp); a tiny delay filters accidental brushes.
                                                val activationDelayMs = 140L
                                                val touchSlop = viewConfiguration.touchSlop
                                                var activated = false
                                                var released = false
                                                isTouching = true

                                                try {
                                                    while (android.os.SystemClock.uptimeMillis() - downUptime < activationDelayMs) {
                                                        val remaining = activationDelayMs - (android.os.SystemClock.uptimeMillis() - downUptime)
                                                        if (remaining <= 0L) {
                                                            activated = true
                                                            break
                                                        }
                                                        val event = withTimeoutOrNull(remaining) {
                                                            awaitPointerEvent()
                                                        }
                                                        if (event == null) {
                                                            activated = true
                                                            break
                                                        }
                                                        val change = event.changes.firstOrNull { it.id == down.id }
                                                        if (change == null || !change.pressed) {
                                                            released = true
                                                            break
                                                        }
                                                        val currentX = change.position.x - down.position.x
                                                        val currentY = change.position.y - down.position.y

                                                        if (currentY < -lockPx) {
                                                            fb.recordStart()
                                                            fb.tick()
                                                            rec = RecordUi.Locked
                                                            withTimeoutOrNull(70L) { awaitPointerEvent() }
                                                            if (!currentOnRecordStart()) {
                                                                fb.error()
                                                                rec = RecordUi.Idle
                                                            }
                                                            return@awaitEachGesture
                                                        }
                                                        if (currentX < -cancelPx) {
                                                            fb.recordStart()
                                                            fb.tick()
                                                            rec = RecordUi.Paused
                                                            withTimeoutOrNull(70L) { awaitPointerEvent() }
                                                            if (currentOnRecordStart()) {
                                                                currentOnRecordPause()
                                                            } else {
                                                                fb.error()
                                                                rec = RecordUi.Idle
                                                            }
                                                            return@awaitEachGesture
                                                        }
                                                        if (currentY > 120f || currentX > 120f) {
                                                            break
                                                        }
                                                    }
                                                } finally {
                                                    isTouching = false
                                                }

                                                // Finger kept moving the whole delay (no idle gap) but never lifted: still a hold.
                                                if (!activated && !released && android.os.SystemClock.uptimeMillis() - downUptime >= activationDelayMs) activated = true
                                                if (!activated) {
                                                    if (released) { fb.tick(); holdHint = holdHint + 1 }
                                                    return@awaitEachGesture
                                                }
                                                if (false) {
                                                    fb.tick(); holdHint = holdHint + 1
                                                    return@awaitEachGesture
                                                }

                                                // Play activation feedback (chime + haptics) immediately for the user
                                                fb.recordStart()
                                                rec = RecordUi.Holding(0f, 0f)

                                                // Allow the brief (60ms) chime to finish playing through the loudspeaker
                                                // before opening the microphone so the chime is never captured in the audio.
                                                val earlyEvent = withTimeoutOrNull(70L) { awaitPointerEvent() }
                                                if (earlyEvent != null) {
                                                    val earlyChange = earlyEvent.changes.firstOrNull { it.id == down.id }
                                                    if (earlyChange == null || !earlyChange.pressed) {
                                                        rec = RecordUi.Idle
                                                        return@awaitEachGesture
                                                    }
                                                }

                                                val recordStartUptime = android.os.SystemClock.uptimeMillis()
                                                if (!currentOnRecordStart()) {
                                                    fb.error()
                                                    rec = RecordUi.Idle
                                                    return@awaitEachGesture
                                                }

                                                var lockedNow = false
                                                var pausedNow = false
                                                var cancelledNow = false
                                                var lastX = 0f

                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!change.pressed) break

                                                    val currentX = change.position.x - down.position.x
                                                    val currentY = change.position.y - down.position.y
                                                    change.consume()
                                                    lastX = currentX

                                                    if (currentY < -lockPx) {
                                                        fb.tick()
                                                        rec = RecordUi.Locked
                                                        lockedNow = true
                                                        break
                                                    } else if (currentX < -cancelPx) {
                                                        // Slide left far enough = cancel, exactly like WhatsApp.
                                                        fb.error()
                                                        currentOnRecordFinish(false)
                                                        rec = RecordUi.Idle
                                                        cancelledNow = true
                                                        break
                                                    }

                                                    rec = RecordUi.Holding(currentX.coerceAtMost(0f), currentY.coerceAtMost(0f))
                                                }

                                                if (!lockedNow && !pausedNow && !cancelledNow) {
                                                    val recordedDuration = android.os.SystemClock.uptimeMillis() - recordStartUptime
                                                    if (recordedDuration < 700L) {
                                                        // Too short to be a voice note: discard and show the hint.
                                                        currentOnRecordFinish(false)
                                                        rec = RecordUi.Idle
                                                        holdHint = holdHint + 1
                                                    } else {
                                                        currentOnRecordFinish(true)
                                                        fb.recordStop()
                                                        rec = RecordUi.Idle
                                                    }
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                // Two expanding ripples while recording + a halo that breathes with the voice level.
                                if (holding) {
                                    listOf(r1, (r1 + 0.5f) % 1f).forEach { f ->
                                        Box(
                                            Modifier.size(50.dp).offset { micOffset }
                                                .graphicsLayer { val sc = grow * (1f + f * 0.9f); scaleX = sc; scaleY = sc; alpha = (1f - f) * 0.28f }
                                                .border(2.dp, accent, CircleShape),
                                        )
                                    }
                                }
                                Box(
                                    Modifier.size(50.dp).offset { micOffset }
                                        .graphicsLayer {
                                            scaleX = halo * grow
                                            scaleY = halo * grow
                                            alpha = if (holding) 0.22f else 0f
                                        }
                                        .clip(CircleShape).background(accent),
                                )
                                Box(
                                    Modifier.size(50.dp)
                                        .offset { micOffset }
                                        .graphicsLayer { scaleX = grow; scaleY = grow }
                                        .clip(CircleShape).background(Brush.linearGradient(listOf(accent, lerp(accent, Color.Black, 0.28f)))),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AnimatedContent(
                                        if (hasText) 1 else 0,
                                        transitionSpec = {
                                            (scaleIn(Motion.bouncy(), initialScale = 0.3f) + fadeIn() + androidx.compose.animation.slideInVertically(Motion.spring()) { it / 2 }) togetherWith
                                                (scaleOut(tween(Motion.XS), targetScale = 0.3f) + fadeOut(tween(Motion.XS)))
                                        },
                                        label = "mic_action",
                                    ) { s ->
                                        val tilt by animateFloatAsState(if (s == 1) -18f else 0f, Motion.spring(), label = "tilt")
                                        Icon(
                                            if (s == 1) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic,
                                            null,
                                            tint = V.c.onAccent,
                                            modifier = Modifier.size(22.dp).graphicsLayer {
                                                val f = sendFly.value
                                                rotationZ = tilt
                                                translationX = f * 34.dp.toPx(); translationY = -f * 22.dp.toPx()
                                                alpha = 1f - f; scaleX = 1f - 0.3f * f; scaleY = scaleX
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // "Hold to record" hint after a quick tap.
            Reveal(
                holdHint > 0,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-46).dp),
                enter = fadeIn(tween(Motion.S)) + scaleIn(Motion.bouncy(), initialScale = 0.8f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
                exit = fadeOut(tween(Motion.S)),
            ) {
                Text(
                    "Hold to record, release to send",
                    style = V.t.label, color = V.c.text,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(V.c.s3).border(1.dp, V.c.hairline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            // Lock hint floats above the mic while holding.
            Reveal(
                rec is RecordUi.Holding,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (-86).dp),
                enter = fadeIn(tween(Motion.S)) + scaleIn(Motion.spring()),
                exit = fadeOut(tween(Motion.XS)) + scaleOut(tween(Motion.XS)),
            ) {
                val dy = (rec as? RecordUi.Holding)?.dragY ?: 0f
                val lockProgress = (-dy / lockPx).coerceIn(0f, 1f)
                val bobT = androidx.compose.animation.core.rememberInfiniteTransition(label = "bob")
                val bob by bobT.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(650), androidx.compose.animation.core.RepeatMode.Reverse), label = "bob")
                val lockBounce by animateFloatAsState(
                    if (lockProgress >= 0.85f) 1.2f else 1f,
                    androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 400f),
                    label = "lock_bounce",
                )

                Column(
                    Modifier
                        .graphicsLayer { translationY = -lockProgress * 26.dp.toPx() }
                        .clip(RoundedCornerShape(22.dp))
                        .background(V.c.s3)
                        .border(1.dp, if (lockProgress > 0.8f) accent else V.c.hairline, RoundedCornerShape(22.dp))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        "Slide up to lock",
                        tint = if (lockProgress > 0.8f) accent else V.c.text2,
                        modifier = Modifier
                            .size(19.dp)
                            .graphicsLayer {
                                scaleX = lockBounce
                                scaleY = lockBounce
                                translationY = -6f * lockProgress
                            },
                    )
                    Spacer(Modifier.height(3.dp))
                    Icon(
                        Icons.Rounded.KeyboardArrowUp,
                        null,
                        tint = if (lockProgress > 0.8f) accent else V.c.text3,
                        modifier = Modifier.size(14.dp).graphicsLayer { translationY = -bob * 3.dp.toPx() * (1f - lockProgress); alpha = 1f - lockProgress },
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldingRecordingStrip(
    dragX: Float,
    level: Float,
    elapsedMs: Long,
    cancelPx: Float,
) {
    val pauseProgress = (-dragX / cancelPx).coerceIn(0f, 1f)
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val dotAlpha by pulse.animateFloat(1f, 0.25f, androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot")
    val dot = V.c.error.copy(alpha = dotAlpha)
    val history = remember { ArrayDeque<Int>() }
    LaunchedEffect(level) {
        history.addLast((level * 100).roundToInt())
        while (history.size > 28) history.removeFirst()
    }

    val textAlpha = (1f - pauseProgress * 1.5f).coerceIn(0f, 1f)
    val releaseAlpha = ((pauseProgress - 0.75f) / 0.25f).coerceIn(0f, 1f)
    val nudgeX = (dragX * 0.6f).coerceIn(-cancelPx, 0f)

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(V.c.s2)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(duration(elapsedMs), style = V.t.mono, color = V.c.text)
        Spacer(Modifier.width(10.dp))
        LiveBars(level, V.accent.copy(alpha = 1f - pauseProgress * 0.5f), Modifier.weight(1f).height(26.dp))
        Spacer(Modifier.width(8.dp))
        Box(contentAlignment = Alignment.CenterEnd) {
            if (textAlpha > 0f) {
                Text(
                    "‹  Slide to cancel",
                    style = V.t.caption.copy(fontSize = 11.5.sp),
                    color = V.c.text2,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        translationX = nudgeX
                        alpha = textAlpha
                    },
                )
            }
            if (releaseAlpha > 0f) {
                Text(
                    "Release… or keep sliding",
                    style = V.t.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                    color = V.accent,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        alpha = releaseAlpha
                    },
                )
            }
        }
    }
}

@Composable
private fun LockedRecordingBar(
    elapsedMs: Long,
    liveHistory: List<Int>,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onSend: () -> Unit,
) {
    val lp = androidx.compose.animation.core.rememberInfiniteTransition(label = "lrec")
    val la by lp.animateFloat(1f, 0.25f, androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "la")
    val dot = V.c.error.copy(alpha = la)

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(V.c.s2, V.c.s1)))
            .border(1.dp, V.c.hairline, RoundedCornerShape(26.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // WhatsApp-style Discard/Delete Button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(V.c.error.copy(alpha = 0.12f))
                .pressable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = "Discard recording", tint = V.c.error, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(duration(elapsedMs), style = V.t.mono, color = V.c.text)
        Spacer(Modifier.width(10.dp))

        // Live Waveform
        LiveBars(LocalLiveLevel.current, V.accent, Modifier.weight(1f).height(26.dp))

        Spacer(Modifier.width(10.dp))

        // Pause Recording Button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(V.c.s3)
                .pressable(onClick = onPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Pause, contentDescription = "Pause recording", tint = V.accent, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(8.dp))

        // Send Button
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(V.accent, lerp(V.accent, Color.Black, 0.22f))))
                .pressable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send voice message", tint = V.c.onAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PausedRecordingBar(
    elapsedMs: Long,
    recordedWaveform: List<Int>,
    isPlayingPreview: Boolean,
    previewPositionMs: Long,
    previewDurationMs: Long,
    onTogglePreview: () -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
    onSend: () -> Unit,
) {
    val progress = if (isPlayingPreview && previewDurationMs > 0) {
        (previewPositionMs.toFloat() / previewDurationMs).coerceIn(0f, 1f)
    } else 0f
    val displayTime = if (isPlayingPreview) previewPositionMs else elapsedMs

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(V.c.s2, V.c.s1)))
            .border(1.dp, V.c.hairline, RoundedCornerShape(26.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // WhatsApp-style Discard/Delete Button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(V.c.error.copy(alpha = 0.12f))
                .pressable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = "Discard recording", tint = V.c.error, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(8.dp))

        // Play / Pause Preview Button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isPlayingPreview) V.accent else V.c.s3)
                .pressable(onClick = onTogglePreview),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlayingPreview) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlayingPreview) "Pause preview" else "Play preview",
                tint = if (isPlayingPreview) V.c.onAccent else V.accent,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Recorded Waveform with preview playback progress
        val player = LocalMediaHost.current.player
        val fb = V.fb
        Waveform(
            recordedWaveform,
            progress,
            V.accent,
            V.c.text3.copy(alpha = 0.45f),
            Modifier.weight(1f).height(26.dp),
            onSeek = { f ->
                fb.tick()
                player.seek("rec_preview", f)
            },
        )

        Spacer(Modifier.width(6.dp))
        Text(duration(displayTime), style = V.t.mono, color = V.c.text)
        Spacer(Modifier.width(8.dp))

        // Resume Recording Button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(V.c.s3)
                .pressable(onClick = onResume),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = "Resume recording", tint = V.c.error, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(8.dp))

        // Send Button
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(V.accent, lerp(V.accent, Color.Black, 0.22f))))
                .pressable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send voice message", tint = V.c.onAccent, modifier = Modifier.size(20.dp))
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


internal val LocalLiveLevel = androidx.compose.runtime.compositionLocalOf { 0f }

/** Scrolling level meter: samples the mic level every 60 ms and slides bars smoothly right-to-left. */
@Composable
fun LiveBars(level: Float, color: Color, modifier: Modifier = Modifier) {
    val latest by androidx.compose.runtime.rememberUpdatedState(level)
    val bars = remember { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(32) { add(0.08f) } } }
    val shift = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            shift.snapTo(0f)
            shift.animateTo(1f, tween(60, easing = androidx.compose.animation.core.LinearEasing))
            bars.removeAt(0); bars.add(latest.coerceIn(0.06f, 1f))
        }
    }
    Canvas(modifier.clipToBounds()) {
        // Fixed bar pitch; only as many of the newest bars as fit, right-aligned (never spills over neighbours).
        val bw = 2.5.dp.toPx()
        val step = bw + 2.5.dp.toPx()
        val fit = (size.width / step).toInt().coerceIn(1, bars.size)
        val start = bars.size - fit
        val x0 = size.width - fit * step
        bars.forEachIndexed { idx, v ->
            if (idx < start) return@forEachIndexed
            val i = idx - start
            val h = (size.height * (0.12f + 0.88f * v)).coerceAtLeast(3f)
            val x = x0 + i * step - shift.value * step + step
            if (x < -bw) return@forEachIndexed
            val edge = (x / (size.width * 0.18f)).coerceIn(0f, 1f) // fade bars entering from the left edge
            drawRoundRect(color.copy(alpha = color.alpha * (0.35f + 0.65f * edge)), androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2), androidx.compose.ui.geometry.Size(bw, h), androidx.compose.ui.geometry.CornerRadius(bw / 2))
        }
    }
}
