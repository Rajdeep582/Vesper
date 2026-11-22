package com.rajdeep.vesper.ui.chat

import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Description
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.focus.focusRequester
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
    suspend fun openFile(ref: MediaRef, progress: (Float) -> Unit = {}): java.io.File
    fun fileIfReady(ref: MediaRef): java.io.File?
    val player: VoicePlayer
}

val LocalMediaHost = staticCompositionLocalOf<MediaHost> { error("no media host") }

val MessageEntity.ref: MediaRef? get() = mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it) }.getOrNull() }
val MessageEntity.replyRef: com.rajdeep.vesper.shared.ReplyRef? get() = replyJson?.let { runCatching { VesperJson.decodeFromString(com.rajdeep.vesper.shared.ReplyRef.serializer(), it) }.getOrNull() }

fun MessageEntity.toReplyRef(): com.rajdeep.vesper.shared.ReplyRef = com.rajdeep.vesper.shared.ReplyRef(
    messageId = serverId ?: localId,
    senderName = senderName,
    text = if (kind == MessageKind.FILE.name) ref?.name else text,
    kind = MessageKind.of(kind),
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
    highlighted: Boolean = false,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {},
    onReply: ((MessageEntity) -> Unit)? = null,
    onJumpToMessage: ((String) -> Unit)? = null,
    me: String? = null,
) {
    if (m.state == MsgState.SYSTEM) { SystemLine(m.text ?: ""); return }
    val mine = m.outgoing
    val kind = MessageKind.of(m.kind)
    val ttl = Limits.ttlFor(kind)
    val remaining = m.expiresAt?.let { it - now }
    val fraction = remaining?.let { it.toFloat() / ttl } ?: 1f
    val fading = remaining != null && remaining < 3_600_000
    val bubbleAlpha by animateFloatAsState(if (fading) 0.82f else 1f, tween(Motion.L), label = "fade")
    val accent = V.accent
    // 1–3 emoji and nothing else: shown large without a bubble (WhatsApp "jumbo" emoji / sticker look).
    val jumbo = remember(m.text, m.kind) { m.kind == MessageKind.TEXT.name && m.replyRef == null && emojiOnlyCount(m.text) in 1..3 }
    val bg = if (jumbo) androidx.compose.ui.graphics.Color.Transparent else if (mine) accent.copy(alpha = if (V.c.dark) 0.16f else 0.14f) else V.c.s2
    val hl by animateFloatAsState(if (highlighted) 1f else 0f, tween(Motion.M), label = "hl")
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
                        .then(if (hl > 0f) Modifier.background(V.accent.copy(alpha = 0.18f * hl)).border(1.5.dp, V.accent.copy(alpha = 0.8f * hl), shape) else Modifier)
                        .then(swipeDraggable)
                        .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongPress, onClick = { if (selecting) onToggle() else if (m.state == MsgState.FAILED) onRetry() }),
                ) {
                    // WhatsApp-style text layout: the time sits in the bottom-right corner — on the last line when it fits, else on its own line.
                    val textMeasurer = rememberTextMeasurer()
                    val q2 = V.c.text2; val s3 = V.c.s3
                    val bodyStyle = if (jumbo) V.t.body.copy(fontSize = (if (emojiOnlyCount(m.text) == 1) 52 else 40).sp, lineHeight = (if (emojiOnlyCount(m.text) == 1) 60 else 48).sp) else V.t.body
                    val metaStyle = V.t.mono.copy(fontSize = V.t.caption.fontSize)
                    val mentionColor = V.c.hall
                    val shown = remember(m.text, accent, q2, me) {
                        if (kind == MessageKind.TEXT) com.rajdeep.vesper.ui.text.formatMessage(
                            m.text ?: "", com.rajdeep.vesper.ui.theme.Mono, accent, q2, s3,
                            mention = if (m.scope == Scope.HALL) mentionColor else null, me = me,
                        ) else null
                    }
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
                            MessageKind.FILE -> Column(Modifier.padding(start = 8.dp, end = 10.dp, top = if (m.replyRef != null) 3.dp else 8.dp, bottom = 6.dp)) {
                                m.ref?.let { FileContent(it, mine, uploading = m.state == MsgState.PENDING, onLongPress = onLongPress) }
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
        if ((m.pinnedUntil ?: 0) > System.currentTimeMillis()) {
            Icon(androidx.compose.material.icons.Icons.Rounded.PushPin, "Pinned", tint = V.c.text3, modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = 35f })
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
                MessageKind.FILE -> "📎 " + (reply.text ?: "File")
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
    val kind = MessageKind.of(reply.kind)
    val textSnippet = when (kind) {
        MessageKind.TEXT -> reply.text ?: ""
        MessageKind.VOICE -> "Voice message" + (reply.ref?.durationMs?.let { " (${duration(it)})" } ?: "")
        MessageKind.IMAGE -> "Photo"
        MessageKind.FILE -> "📎 " + (reply.ref?.name ?: "File")
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
    /** Discarded: the strip plays the "into the bin" animation, then returns to Idle. */
    data object Cancelled : RecordUi
}

private val MENTION_QUERY = Regex("""(?:^|\s)@([A-Za-z0-9_.]{0,20})$""")

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
    draftKey: String? = null,
    /** Hall only: usernames offered after typing "@" (plus @all). Empty = no mention picker. */
    mentionables: List<String> = emptyList(),
) {
    // Unsent text survives leaving the chat (kept in memory only, never written to disk).
    var text by remember(draftKey) { mutableStateOf(draftKey?.let { Drafts.map[it] }.orEmpty()) }
    LaunchedEffect(text, draftKey) { draftKey?.let { if (text.isBlank()) Drafts.map.remove(it) else Drafts.map[it] = text } }
    var emojiOpen by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = emojiOpen) { emojiOpen = false }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val typingNow by androidx.compose.runtime.rememberUpdatedState(onTyping)
    // Each keystroke restarts this: "typing" while active, "stopped" after 4 s idle or when cleared/sent.
    LaunchedEffect(text) {
        if (text.isNotBlank()) { typingNow(true); kotlinx.coroutines.delay(4_000); typingNow(false) } else typingNow(false)
    }
    var rec by remember { mutableStateOf<RecordUi>(RecordUi.Idle) }
    LaunchedEffect(rec) { if (rec == RecordUi.Cancelled) { kotlinx.coroutines.delay(760); rec = RecordUi.Idle } }
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

    // Text + cursor for the field; external changes (draft restore, emoji, mention pick) put the cursor at the end
    // unless they set it themselves.
    var tfv by remember(draftKey) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }
    if (tfv.text != text) tfv = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
    val beforeCursor = tfv.text.substring(0, tfv.selection.end.coerceIn(0, tfv.text.length))
    val mentionMatch = if (draftKey != "hall") null else MENTION_QUERY.find(beforeCursor)
    val mentionQ = mentionMatch?.groupValues?.get(1)
    val mentionHits = mentionQ?.let { q ->
        (listOf("all") + mentionables).filter { it.startsWith(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) || (q.isEmpty() && it == "all") }.distinct().take(6)
    }.orEmpty()
    fun pickMention(name: String) {
        val m = mentionMatch ?: return
        val at = m.range.first + m.value.indexOf('@')
        val end = tfv.selection.end
        val ins = "@$name "
        val nt = tfv.text.substring(0, at) + ins + tfv.text.substring(end)
        tfv = androidx.compose.ui.text.input.TextFieldValue(nt, androidx.compose.ui.text.TextRange(at + ins.length))
        text = nt
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

        com.rajdeep.vesper.ui.components.Reveal(
            mentionHits.isNotEmpty(),
            enter = fadeIn(tween(Motion.S)) + androidx.compose.animation.expandVertically(Motion.spring()),
            exit = fadeOut(tween(Motion.XS)) + androidx.compose.animation.shrinkVertically(tween(Motion.S)),
        ) {
            val gold = V.c.hall
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp).clip(RoundedCornerShape(20.dp))
                    .background(V.c.s2).border(1.dp, gold.copy(alpha = 0.28f), RoundedCornerShape(20.dp)).padding(vertical = 6.dp),
            ) {
                mentionHits.forEach { n ->
                    val all = n == "all"
                    Row(
                        Modifier.fillMaxWidth().pressable(scaleTo = 0.98f) { fb.tick(); pickMention(n) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(if (all) Brush.linearGradient(listOf(gold, gold.copy(alpha = 0.55f))) else Brush.linearGradient(listOf(gold.copy(alpha = 0.22f), gold.copy(alpha = 0.10f)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (all) Icon(Icons.Rounded.Groups, null, tint = V.c.bg, modifier = Modifier.size(18.dp))
                            else Text(n.first().uppercase(), style = V.t.label.copy(fontWeight = FontWeight.SemiBold), color = gold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("@$n", style = V.t.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = V.c.text, maxLines = 1)
                            if (all) Text("Notify everyone in the Hall", style = V.t.caption, color = V.c.text2, maxLines = 1)
                        }
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
                    // Holding → Locked rises into place; Locked ↔ Paused cross-morph; back to idle sinks away.
                    val up = targetState > initialState
                    (fadeIn(tween(Motion.M)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 340f)) { if (up) it / 3 else -it / 4 } +
                        scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 360f), initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(Motion.S)) + scaleOut(tween(Motion.S), targetScale = 0.97f)) using
                        androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f) }
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
                                rec = RecordUi.Cancelled
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
                                rec = RecordUi.Cancelled
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
                                    val focusReq = remember { androidx.compose.ui.focus.FocusRequester() }
                                    val focused by focus.collectIsFocusedAsState()
                                    val ring by animateColorAsState(if (focused) accent.copy(alpha = 0.55f) else V.c.hairline, tween(Motion.M), label = "ring")
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp))
                                            .background(Brush.verticalGradient(listOf(V.c.s2, V.c.s1)))
                                            .border(1.dp, ring, RoundedCornerShape(25.dp))
                                            .animateContentSize(Motion.spring())
                                            .padding(start = 4.dp, end = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconAction(Icons.Rounded.AttachFile, "Attach", tint = V.c.text2, size = 42.dp, modifier = Modifier.graphicsLayer { rotationZ = 45f }, onClick = onAttach)
                                        LaunchedEffect(focused) { if (focused) emojiOpen = false }
                                        Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                                            if (text.isEmpty()) Text(placeholder, style = V.t.body, color = V.c.text3)
                                            BasicTextField(
                                                tfv, { if (it.text.length <= Limits.MAX_TEXT_CHARS * 3) { tfv = it; text = it.text } },
                                                textStyle = V.t.body.copy(color = V.c.text), cursorBrush = SolidColor(accent), maxLines = 6,
                                                interactionSource = focus,
                                                modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
                                            )
                                        }
                                        // Emoji ↔ keyboard toggle, morphing icon.
                                        androidx.compose.animation.Crossfade(emojiOpen, animationSpec = androidx.compose.animation.core.tween(Motion.S), label = "emo") { open ->
                                            Text(
                                                if (open) "⌨️" else "🙂", fontSize = 20.sp,
                                                modifier = Modifier.padding(start = 6.dp).clip(CircleShape).pressable(scaleTo = 0.85f) {
                                                    fb.tick()
                                                    if (emojiOpen) { emojiOpen = false; focusReq.requestFocus(); kb?.show() }
                                                    else { kb?.hide(); emojiOpen = true }
                                                }.padding(4.dp).graphicsLayer { alpha = if (open) 0.9f else 0.75f },
                                            )
                                        }
                                    }
                                } else {
                                    HoldingRecordingStrip(
                                        dragX = (rec as? RecordUi.Holding)?.dragX ?: 0f,
                                        level = level,
                                        elapsedMs = elapsedMs,
                                        cancelPx = cancelPx,
                                        cancelled = rec == RecordUi.Cancelled,
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
                                                        rec = RecordUi.Cancelled
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
        // In-app emoji & sticker drawer (slides up in place of the keyboard).
        com.rajdeep.vesper.ui.components.Reveal(
            emojiOpen && rec == RecordUi.Idle,
            enter = androidx.compose.animation.expandVertically(Motion.spring(), expandFrom = Alignment.Top) + fadeIn(tween(Motion.M)),
            exit = androidx.compose.animation.shrinkVertically(tween(Motion.S)) + fadeOut(tween(Motion.XS)),
        ) {
            EmojiPanel(
                onEmoji = { text += it },
                onSticker = { fb.send(); onSend(it) },
                onBackspace = {
                    if (text.isNotEmpty()) {
                        val bi = java.text.BreakIterator.getCharacterInstance(); bi.setText(text)
                        text = text.substring(0, bi.preceding(text.length).coerceAtLeast(0))
                    }
                },
                modifier = Modifier.padding(top = 6.dp).navigationBarsPadding(),
            )
        }
    }
}

/** Recording dot: soft pulse in size and glow (not a hard blink). */
@Composable
private fun RecDot() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "recdot")
    val p by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "p")
    val red = V.c.error
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { val k = 0.7f + 0.6f * p; scaleX = k; scaleY = k; alpha = 0.28f * (1f - p) }.clip(CircleShape).background(red))
        Box(Modifier.size(9.dp).graphicsLayer { val k = 0.9f + 0.12f * p; scaleX = k; scaleY = k; alpha = 0.65f + 0.35f * p }.clip(CircleShape).background(red))
    }
}

/** Timer whose digits roll up as they change (only the changed digit moves). */
@Composable
private fun RollingTimer(ms: Long, color: Color = V.c.text) {
    val txt = duration(ms)
    Row {
        txt.forEachIndexed { i, ch ->
            AnimatedContent(
                ch,
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 520f)) { it / 2 } + fadeIn(tween(Motion.S))) togetherWith
                        (androidx.compose.animation.slideOutVertically(tween(Motion.S)) { -it / 2 } + fadeOut(tween(Motion.XS)))
                },
                label = "digit$i",
            ) { c -> Text(c.toString(), style = V.t.mono, color = color) }
        }
    }
}

@Composable
private fun HoldingRecordingStrip(
    dragX: Float,
    level: Float,
    elapsedMs: Long,
    cancelPx: Float,
    cancelled: Boolean = false,
) {
    val target = (-dragX / cancelPx).coerceIn(0f, 1f)
    // Eased follow of the finger so text/tint glide instead of tracking every touch sample.
    val pauseProgress by animateFloatAsState(target, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 900f), label = "slide")
    val chevrons = androidx.compose.animation.core.rememberInfiniteTransition(label = "chev")
    val wave by chevrons.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1300, easing = androidx.compose.animation.core.LinearEasing)), label = "wave")
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 320f)) }

    val textAlpha = (1f - pauseProgress * 1.4f).coerceIn(0f, 1f)
    val releaseAlpha = ((pauseProgress - 0.72f) / 0.28f).coerceIn(0f, 1f)
    val nudgeX = (dragX * 0.55f).coerceIn(-cancelPx, 0f)
    val danger = V.c.error

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); translationX = (1f - enter.value) * 24.dp.toPx() }
            .clip(RoundedCornerShape(25.dp))
            .background(lerp(V.c.s2, lerp(V.c.s2, danger, 0.22f), pauseProgress))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            cancelled,
            transitionSpec = { (fadeIn(tween(Motion.S)) + scaleIn(Motion.bouncy(), initialScale = 0.6f)) togetherWith fadeOut(tween(Motion.XS)) },
            label = "bin",
        ) { gone ->
            if (gone) BinDrop() else Row(verticalAlignment = Alignment.CenterVertically) {
                RecDot()
                Spacer(Modifier.width(6.dp))
                RollingTimer(elapsedMs)
            }
        }
        Spacer(Modifier.width(10.dp))
        if (!cancelled) {
            LiveBars(level, lerp(V.accent, danger, pauseProgress * 0.6f).copy(alpha = 1f - pauseProgress * 0.45f), Modifier.weight(1f).height(28.dp))
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.CenterEnd) {
                if (textAlpha > 0f) {
                    Row(
                        Modifier.graphicsLayer { translationX = nudgeX; alpha = textAlpha },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Three chevrons with a travelling shimmer pointing the way.
                        for (k in 0 until 3) {
                            val ph = ((wave + k / 3f) % 1f)
                            Text("‹", style = V.t.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = V.c.text2.copy(alpha = 0.25f + 0.75f * (1f - ph)))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Slide to cancel", style = V.t.caption.copy(fontSize = 11.5.sp), color = V.c.text2, maxLines = 1)
                    }
                }
                if (releaseAlpha > 0f) {
                    Text(
                        "Release to cancel",
                        style = V.t.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                        color = danger, maxLines = 1,
                        modifier = Modifier.graphicsLayer { alpha = releaseAlpha; val k = 0.92f + 0.08f * releaseAlpha; scaleX = k; scaleY = k },
                    )
                }
            }
        } else {
            Text("Discarded", style = V.t.caption.copy(fontSize = 12.sp), color = V.c.text2, modifier = Modifier.weight(1f))
        }
    }
}

/** WhatsApp-style discard: the mic drops into a bin whose lid opens and snaps shut. */
@Composable
private fun BinDrop() {
    val drop = remember { androidx.compose.animation.core.Animatable(0f) }
    val lid = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { lid.animateTo(1f, tween(160)); kotlinx.coroutines.delay(260); lid.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 700f)) }
            launch { drop.animateTo(1f, tween(380, easing = androidx.compose.animation.core.FastOutLinearInEasing)) }
        }
    }
    val red = V.c.error
    Box(Modifier.size(30.dp, 30.dp), contentAlignment = Alignment.BottomCenter) {
        Icon(
            Icons.Rounded.Mic, null, tint = red,
            modifier = Modifier.align(Alignment.TopCenter).size(15.dp).graphicsLayer {
                translationY = (-22f + 26f * drop.value).dp.toPx(); rotationZ = 160f * drop.value
                alpha = (1f - ((drop.value - 0.8f) / 0.2f).coerceIn(0f, 1f))
            },
        )
        Icon(
            Icons.Rounded.Delete, "Discarded", tint = red,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -18f * lid.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.1f, 0.2f) },
        )
    }
}

/** Staggered spring pop-in for bar controls (index = order). */
@Composable
private fun Modifier.popIn(index: Int): Modifier {
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(40L * index); a.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 420f)) }
    return this.graphicsLayer { val k = 0.4f + 0.6f * a.value; scaleX = k; scaleY = k; alpha = a.value.coerceIn(0f, 1f) }
}

@Composable
private fun LockedRecordingBar(
    elapsedMs: Long,
    liveHistory: List<Int>,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onSend: () -> Unit,
) {

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
                .popIn(1)
                .clip(CircleShape)
                .background(V.c.error.copy(alpha = 0.12f))
                .pressable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = "Discard recording", tint = V.c.error, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(8.dp))
        RecDot()
        Spacer(Modifier.width(6.dp))
        RollingTimer(elapsedMs)
        Spacer(Modifier.width(10.dp))

        // Live Waveform
        LiveBars(LocalLiveLevel.current, V.accent, Modifier.weight(1f).height(28.dp))

        Spacer(Modifier.width(10.dp))

        // Pause Recording Button
        Box(
            modifier = Modifier
                .size(40.dp)
                .popIn(2)
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
                .popIn(3)
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
                .popIn(1)
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
                .popIn(2)
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
                .popIn(3)
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
                .popIn(4)
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

/**
 * Scrolling level meter driven by the frame clock: the mic level is low-pass filtered, bars glide right-to-left at a
 * constant speed (no 60 ms steps) and each new bar grows into its height. The newest bars are brightest.
 */
@Composable
fun LiveBars(level: Float, color: Color, modifier: Modifier = Modifier) {
    val latest by androidx.compose.runtime.rememberUpdatedState(level)
    val n = 40
    val target = remember { FloatArray(n) { 0.06f } }
    val shown = remember { FloatArray(n) { 0.06f } }
    var phase by remember { mutableStateOf(0f) }
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var smooth = 0.06f
        var last = 0L
        val stepMs = 75f
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 64f)
                last = now
                smooth += (latest.coerceIn(0f, 1f) - smooth) * (1f - kotlin.math.exp(-dt / 70f))
                var ph = phase + dt / stepMs
                while (ph >= 1f) {
                    ph -= 1f
                    System.arraycopy(target, 1, target, 0, n - 1); System.arraycopy(shown, 1, shown, 0, n - 1)
                    // A touch of organic variation so steady tones don't look like a flat bar.
                    target[n - 1] = (smooth * (0.82f + 0.36f * kotlin.random.Random.nextFloat())).coerceIn(0.06f, 1f)
                    shown[n - 1] = 0.06f
                }
                phase = ph
                val k = 1f - kotlin.math.exp(-dt / 55f)
                for (i in 0 until n) shown[i] += (target[i] - shown[i]) * k
                tick = now
            }
        }
    }
    Canvas(modifier.clipToBounds()) {
        tick // redraw every frame
        val bw = 2.6.dp.toPx()
        val step = bw + 2.4.dp.toPx()
        val fit = (size.width / step).toInt().coerceIn(1, n - 1)
        val start = n - fit
        val x0 = size.width - fit * step
        for (idx in start until n) {
            val i = idx - start
            val x = x0 + i * step - phase * step + step * 0.5f
            if (x < -bw) continue
            val v = shown[idx]
            val h = (size.height * (0.12f + 0.88f * v)).coerceAtLeast(3f)
            val edge = (x / (size.width * 0.22f)).coerceIn(0f, 1f)
            val recency = 0.55f + 0.45f * (i.toFloat() / fit)
            drawRoundRect(
                color.copy(alpha = color.alpha * edge * recency),
                androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2),
                androidx.compose.ui.geometry.Size(bw, h),
                androidx.compose.ui.geometry.CornerRadius(bw / 2),
            )
        }
    }
}


/** Per-chat unsent drafts for this app session (RAM only). */
object Drafts { val map = java.util.concurrent.ConcurrentHashMap<String, String>() }


fun humanBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

private fun fileTint(ext: String): androidx.compose.ui.graphics.Color = when (ext) {
    "pdf" -> androidx.compose.ui.graphics.Color(0xFFE5675C)
    "doc", "docx", "odt", "rtf", "txt", "md" -> androidx.compose.ui.graphics.Color(0xFF6C9BE8)
    "xls", "xlsx", "ods", "csv" -> androidx.compose.ui.graphics.Color(0xFF4FB286)
    "ppt", "pptx", "odp", "key" -> androidx.compose.ui.graphics.Color(0xFFE59A4F)
    "zip", "rar", "7z", "gz", "tar", "xz" -> androidx.compose.ui.graphics.Color(0xFFB28BE0)
    "mp4", "mkv", "mov", "webm", "3gp" -> androidx.compose.ui.graphics.Color(0xFFE06C9F)
    "mp3", "m4a", "wav", "flac", "ogg", "opus", "aac" -> androidx.compose.ui.graphics.Color(0xFF4FC1C9)
    "jpg", "jpeg", "png", "webp", "heic", "gif" -> androidx.compose.ui.graphics.Color(0xFFD9B24C)
    "apk" -> androidx.compose.ui.graphics.Color(0xFF8BC34A)
    else -> androidx.compose.ui.graphics.Color(0xFF8FA3C8)
}

/** Opens a ready file with the system chooser (read-only grant through our FileProvider). */
fun openWithSystem(ctx: android.content.Context, f: java.io.File, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    if (f.name.endsWith(".apk", ignoreCase = true)) {
        // App update / install: straight to the system installer (it asks once to allow installs from Vesper).
        val install = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(install) }.isSuccess) return
    }
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(android.content.Intent.createChooser(view, f.name)) }
}

fun shareWithSystem(ctx: android.content.Context, f: java.io.File, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share " + f.name)) }
}

/** WhatsApp-style document card: type badge, name, size · type, and a download/open state ring. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FileContent(ref: MediaRef, mine: Boolean, uploading: Boolean, onLongPress: () -> Unit) {
    val host = LocalMediaHost.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val fb = V.fb
    val name = ref.name ?: "File"
    val ext = name.substringAfterLast('.', "").lowercase().take(4)
    val tint = fileTint(ext)
    var ready by remember(ref.mediaId) { mutableStateOf(host.fileIfReady(ref) != null || mine) }
    var progress by remember(ref.mediaId) { mutableStateOf<Float?>(null) }
    var failed by remember(ref.mediaId) { mutableStateOf(false) }
    val shown by animateFloatAsState(progress ?: 0f, tween(Motion.M), label = "dl")
    fun go() {
        if (uploading || progress != null || ref.mediaId.isEmpty()) return
        fb.tick(); failed = false
        scope.launch {
            progress = 0f
            runCatching { host.openFile(ref) { p -> progress = p } }
                .onSuccess { f -> ready = true; progress = null; openWithSystem(ctx, f, ref.mime) }
                .onFailure { progress = null; failed = true; fb.error() }
        }
    }
    Row(
        Modifier.widthIn(min = 250.dp, max = 300.dp).clip(RoundedCornerShape(14.dp)).background(V.c.bg.copy(alpha = 0.35f))
            .combinedClickable(onClick = { go() }, onLongClick = onLongPress).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.65f)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Description, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.95f), modifier = Modifier.size(22.dp).offset(y = (-4).dp))
            Text(
                ext.uppercase().ifEmpty { "FILE" }, style = V.t.caption.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
                color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = V.t.bodySmall.copy(fontWeight = FontWeight.Medium), color = V.c.text, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis)
            Spacer(Modifier.height(3.dp))
            val sizeLine = buildString {
                append(humanBytes(ref.origBytes ?: ref.bytes))
                val sent = ref.bytes
                val orig = ref.origBytes ?: sent
                if (orig > 0 && sent < orig * 0.9) append(" → ").append(humanBytes(sent))

            }
            Text(
                when {
                    uploading -> "Sending… · " + sizeLine
                    failed -> "Couldn't open · tap to retry"
                    else -> sizeLine
                },
                style = V.t.label, color = if (failed) V.c.error else V.c.text2, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            when {
                uploading -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = tint, strokeWidth = 2.5.dp, trackColor = tint.copy(alpha = 0.15f))
                progress != null -> androidx.compose.material3.CircularProgressIndicator({ shown }, Modifier.size(28.dp), color = tint, strokeWidth = 2.5.dp, trackColor = tint.copy(alpha = 0.15f))
                ready -> Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open", tint = tint, modifier = Modifier.size(20.dp))
                else -> Box(Modifier.size(32.dp).clip(CircleShape).border(1.5.dp, tint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Download, "Download", tint = tint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
