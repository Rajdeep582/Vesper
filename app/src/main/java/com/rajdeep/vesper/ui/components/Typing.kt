package com.rajdeep.vesper.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Three dots rising in a soft wave — the WhatsApp "typing" pulse. */
@Composable
fun TypingDots(color: Color, dot: Dp = 5.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(dot * 0.7f), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val p by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 160)),
                label = "d$i",
            )
            Box(
                Modifier.size(dot).graphicsLayer { translationY = -p * dot.toPx() * 0.8f; alpha = 0.4f + 0.6f * p; scaleX = 0.85f + 0.15f * p; scaleY = scaleX }
                    .clip(CircleShape).background(color),
            )
        }
    }
}

/** Header / list subtitle that cross-fades between a live status ("typing…", "online") and its resting text. */
@Composable
fun LiveStatusLine(
    typing: String?,
    resting: @Composable () -> Unit,
    style: TextStyle,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        typing,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }) togetherWith (fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 2 })
        },
        contentAlignment = Alignment.CenterStart,
        label = "status",
        modifier = modifier,
    ) { who ->
        if (who == null) resting()
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(who, style = style, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(5.dp))
            TypingDots(accent, 3.5.dp, Modifier.graphicsLayer { translationY = 2f })
        }
    }
}

/** "deep2003 is typing", "deep2003 and ash are typing", "3 people are typing". */
fun typingLabel(names: List<String>): String? = when (names.size) {
    0 -> null
    1 -> "${names[0]} is typing"
    2 -> "${names[0]} and ${names[1]} are typing"
    else -> "${names.size} people are typing"
}

/** Monogram with a WhatsApp-style green presence dot that pops in/out. */
@Composable
fun PresenceAvatar(name: String, size: Dp, online: Boolean, modifier: Modifier = Modifier) {
    val s by androidx.compose.animation.core.animateFloatAsState(
        if (online) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "presence",
    )
    Box(modifier) {
        Monogram(name, size)
        if (s > 0.01f) Box(
            Modifier.align(Alignment.BottomEnd).size(size * 0.3f).graphicsLayer { scaleX = s; scaleY = s }
                .clip(CircleShape).background(com.rajdeep.vesper.ui.theme.V.c.bg).padding(size * 0.05f)
                .clip(CircleShape).background(com.rajdeep.vesper.ui.theme.V.c.success),
        )
    }
}

/** False while a kept-alive tab is hidden behind another one. */
val LocalTabActive = androidx.compose.runtime.staticCompositionLocalOf { true }
