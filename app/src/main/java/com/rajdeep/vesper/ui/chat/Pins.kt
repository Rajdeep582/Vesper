package com.rajdeep.vesper.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajdeep.vesper.core.db.MessageEntity
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.Pins
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V

/** One-line preview of a pinned message (text, or a label for media). */
fun pinPreview(m: MessageEntity): String = when (MessageKind.of(m.kind)) {
    MessageKind.TEXT -> m.text?.replace('\n', ' ')?.trim().orEmpty()
    MessageKind.IMAGE -> "Photo"
    MessageKind.VOICE -> "Voice message"
    MessageKind.FILE -> m.ref?.name ?: "File"
}

fun pinLeft(until: Long, now: Long): String {
    val ms = (until - now).coerceAtLeast(0)
    val m = ms / 60_000
    return when {
        m >= 24 * 60 -> "${(m + 12 * 60) / (24 * 60)}d left"
        m >= 60 -> "${m / 60}h left"
        else -> "${m.coerceAtLeast(1)}m left"
    }
}

/**
 * WhatsApp-style pinned strip under the chat header. Segment rail on the left shows which pin is in view (newest
 * first); tapping jumps to that message and advances to the next pin. The pin glyph on the right opens actions.
 */
@Composable
fun PinnedBar(pins: List<MessageEntity>, now: Long, onJump: (MessageEntity) -> Unit, onActions: (MessageEntity) -> Unit) {
    if (pins.isEmpty()) return
    var idx by remember { mutableIntStateOf(0) }
    if (idx >= pins.size) idx = 0
    val cur = pins[idx]
    val fb = V.fb
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(54.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(V.accent.copy(alpha = 0.13f), V.c.s2.copy(alpha = 0.92f))))
            .border(1.dp, V.accent.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .pressable(scaleTo = 0.985f) { fb.tick(); onJump(cur); idx = (idx + 1) % pins.size }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Segment rail: one bar per pin, the one in view lit.
        Column(Modifier.fillMaxHeight().padding(vertical = 9.dp).width(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            pins.indices.forEach { i ->
                val lit = i == idx
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(2.dp)).background(if (lit) V.accent else V.c.text3.copy(alpha = 0.35f)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                cur.localId,
                transitionSpec = { (slideInVertically(Motion.spring()) { it / 2 } + fadeIn()) togetherWith (slideOutVertically(Motion.spring()) { -it / 2 } + fadeOut()) },
                label = "pin",
            ) { id ->
                val m = pins.firstOrNull { it.localId == id } ?: cur
                Column {
                    Text(
                        (if (pins.size > 1) "Pinned message ${pins.indexOf(m) + 1} of ${pins.size}" else "Pinned message") +
                            (m.pinnedUntil?.let { " · " + pinLeft(it, now) } ?: ""),
                        style = V.t.caption, color = V.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (m.senderName.isNotBlank() && m.scope == "hall") m.senderName + ": " else "") + pinPreview(m),
                        style = V.t.label, color = V.c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.size(42.dp).clip(CircleShape).pressable(scaleTo = 0.88f) { fb.tick(); onActions(cur) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PushPin, "Pinned message actions", tint = V.accent, modifier = Modifier.size(20.dp).rotate(35f))
        }
    }
}

/** Duration picker shown before pinning: 24 hours, 3 days, 7 days (default 7, like WhatsApp). */
@Composable
fun PinDurationPicker(onPin: (Int) -> Unit, onCancel: () -> Unit) {
    var pick by remember { mutableIntStateOf(Pins.DAYS.last()) }
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(V.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PushPin, null, tint = V.accent, modifier = Modifier.size(20.dp).rotate(35f))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Choose how long your pin lasts", style = V.t.subhead.copy(fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = V.c.text)
                Text("Stays in the chat until then — you can unpin any time", style = V.t.label, color = V.c.text2)
            }
        }
        Spacer(Modifier.height(12.dp))
        Pins.DAYS.forEach { d ->
            val on = pick == d
            val inset by animateDpAsState(if (on) 0.dp else 2.dp, Motion.spring(), label = "inset")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).padding(horizontal = inset.coerceAtLeast(0.dp)).clip(RoundedCornerShape(16.dp))
                    .background(if (on) V.accent.copy(alpha = 0.12f) else V.c.s2)
                    .border(1.dp, if (on) V.accent.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp))
                    .pressable(scaleTo = 0.98f) { pick = d }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (d == 1) "24 hours" else "$d days", style = V.t.subhead, color = if (on) V.accent else V.c.text, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (on) V.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .border(2.dp, if (on) V.accent else V.c.text3, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.Rounded.Check, null, tint = V.c.bg, modifier = Modifier.size(15.dp)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).background(V.c.s2).pressable(onClick = onCancel), contentAlignment = Alignment.Center) {
                Text("Cancel", style = V.t.subhead, color = V.c.text)
            }
            Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).background(V.accent).pressable { onPin(pick) }, contentAlignment = Alignment.Center) {
                Text("Pin", style = V.t.subhead, color = V.c.bg)
            }
        }
    }
}
