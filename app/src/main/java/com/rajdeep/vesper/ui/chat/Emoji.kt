package com.rajdeep.vesper.ui.chat

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import kotlinx.coroutines.launch

/** Counts emoji graphemes if the text is *only* emoji (and spaces); -1 otherwise. Zero assets: system emoji font. */
fun emojiOnlyCount(text: String?): Int {
    val t = text?.trim() ?: return -1
    if (t.isEmpty() || t.length > 40) return -1
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(t)
    var n = 0
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        val g = t.substring(start, end)
        if (g.isNotBlank()) {
            if (!isEmojiGrapheme(g)) return -1
            n++
        }
        start = end; end = it.next()
    }
    return n
}

private fun isEmojiGrapheme(g: String): Boolean {
    val cp = g.codePointAt(0)
    return when {
        cp in 0x1F000..0x1FAFF -> true
        cp in 0x2600..0x27BF -> true
        cp in 0x2300..0x23FF -> true
        cp in 0x2B00..0x2BFF -> true
        cp in 0x1F1E6..0x1F1FF -> true
        cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 -> true
        cp in 0x2190..0x21FF && g.contains('️') -> true
        (cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A) && g.contains('⃣') -> true
        else -> false
    }
}

private class Cat(val icon: String, val name: String, val items: List<String>)

private fun split(s: String): List<String> = s.trim().split(Regex("\\s+"))

private val CATS = listOf(
    Cat("😀", "Smileys", split("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 🫠 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺️ 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🫢 🫣 🤫 🤔 🫡 🤐 🤨 😐 😑 😶 🫥 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 😵‍💫 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 🫤 😟 🙁 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾")),
    Cat("👋", "People", split("👋 🤚 🖐️ ✋ 🖖 🫱 🫲 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🧠 🫀 👀 👁️ 👅 👄 🫦 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 🧑‍💻 🧑‍🎓 🧑‍🍳 🧑‍🚀 🥷 🦸 🧙 🧚 🧛 🧜 🧞 🧟 💃 🕺 👯 🧘 🏃 🚶 👫 👭 👬 💏 💑 👪")),
    Cat("❤️", "Hearts", split("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 🩵 🩶 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💌 💋 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭 💤 ✨ 🌟 ⭐ 🔥 🎉 🎊 🎈 🎁 🏆 🥇 🎯")),
    Cat("🐶", "Nature", split("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪲 🐢 🐍 🦎 🐙 🦑 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦒 🐪 🐫 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐈 🐓 🦚 🦜 🦢 🕊️ 🐇 🦝 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🍄 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌙 🌎 🪐 ⚡ ☄️ 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ ⛄ 🌊")),
    Cat("🍕", "Food", split("🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🍞 🥖 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷 🍸 🍹 🧉")),
    Cat("⚽", "Activity", split("⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏏 🥅 ⛳ 🏹 🎣 🥊 🥋 🛹 ⛸️ 🎿 🏂 🏋️ 🤸 ⛹️ 🤺 🏄 🏊 🚴 🧗 🏅 🎖️ 🎫 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🪕 🎻 🎲 ♟️ 🎮 🧩 🎳")),
    Cat("✈️", "Places", split("🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚚 🚜 🛵 🏍️ 🚲 🛴 🚨 🚃 🚄 🚅 🚂 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛳️ ⚓ 🗺️ 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ 🏠 🏡 🏢 🏥 🏦 🏨 🏫 ⛪ 🕌 🛕 🕍 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁")),
    Cat("💡", "Objects", split("⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📷 📸 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚙️ 🧲 🔫 💣 🔪 🛡️ 🔮 📿 💈 🔭 🔬 💊 💉 🩹 🧬 🌡️ 🧹 🧺 🧻 🚽 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎀 ✉️ 📩 📦 📫 📜 📄 📅 📆 🗒️ 📇 📈 📉 📊 📋 📌 📍 📎 🖇️ 📏 📐 ✂️ 🗃️ 🗑️ 🔒 🔓 🔏 🔐 📝 ✏️ 🖊️ 🖋️ 📚 📖 🔖")),
    Cat("✅", "Symbols", split("✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚫 🔞 📵 🔇 🔕 ♻️ ⚜️ 🔱 📛 🔰 ⭕ 🆗 🆒 🆕 🆓 🆙 🆘 🔝 🔜 🔛 🔙 🔚 ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ 🔀 🔁 🔂 ⬆️ ⬇️ ⬅️ ➡️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ 🔄 🎵 🎶 💲 ©️ ®️ ™️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🏁 🚩 🏳️ 🏴 🏳️‍🌈 🇮🇳")),
)

/** "Stickers": expressive emoji combos sent in one tap and shown jumbo. No images, no downloads, no storage. */
private val STICKERS = listOf(
    "🥳🎉", "😂🤣", "❤️‍🔥", "🫶", "🥹", "😭💔", "🤯", "🫡", "😎👌", "🙏✨", "👀", "💀😂", "🤝", "💯🔥", "😴💤", "☕😌",
    "🎂🎈", "👏👏👏", "🤗", "😤💪", "🌚", "🙈🙉🙊", "🫠", "🤌", "😇", "🤡", "🚀", "🌸✨", "🍕😋", "🤞", "✅", "👋😊",
)

private object Recents {
    private const val KEY = "emoji_recent"
    fun load(ctx: Context): List<String> = ctx.getSharedPreferences("vesper_ui", Context.MODE_PRIVATE).getString(KEY, "")!!.split('\u0001').filter { it.isNotEmpty() }
    fun push(ctx: Context, e: String) {
        val next = (listOf(e) + load(ctx).filter { it != e }).take(32)
        ctx.getSharedPreferences("vesper_ui", Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString("\u0001")).apply()
    }
}

/**
 * In-app emoji & sticker drawer. Emoji insert into the message; stickers send immediately.
 * Recents are kept on this phone only (emoji, never message text).
 */
@Composable
fun EmojiPanel(onEmoji: (String) -> Unit, onSticker: (String) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val recents = remember { mutableStateListOf<String>().apply { addAll(Recents.load(ctx)) } }
    var tab by remember { mutableStateOf(if (recents.isEmpty()) 1 else 0) } // 0 recent, 1..n cats, last = stickers
    val tabs = listOf("🕘") + CATS.map { it.icon } + listOf("✨")
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val fb = V.fb
    Column(modifier.fillMaxWidth().height(300.dp).background(V.c.s1)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(tabs) { i, icon ->
                    val sel = i == tab
                    val s by animateFloatAsState(if (sel) 1f else 0f, Motion.spring(), label = "t")
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(V.accent.copy(alpha = 0.16f * s))
                            .pressable(scaleTo = 0.88f) { fb.tick(); tab = i; scope.launch { grid.scrollToItem(0) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(icon, fontSize = 20.sp, modifier = Modifier.graphicsLayer { val k = 0.9f + 0.15f * s; scaleX = k; scaleY = k; alpha = 0.6f + 0.4f * s }) }
                }
            }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).pressable(scaleTo = 0.88f) { fb.tick(); onBackspace() }, contentAlignment = Alignment.Center) {
                Text("⌫", fontSize = 20.sp, color = V.c.text2)
            }
        }
        val stickers = tab == tabs.lastIndex
        val list = when {
            tab == 0 -> recents.toList()
            stickers -> STICKERS
            else -> CATS[tab - 1].items
        }
        val title = when {
            tab == 0 -> "Recent"
            stickers -> "Stickers · tap to send"
            else -> CATS[tab - 1].name
        }
        LazyVerticalGrid(
            if (stickers) GridCells.Fixed(4) else GridCells.Adaptive(46.dp),
            Modifier.fillMaxWidth().weight(1f), state = grid,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(title.uppercase(), style = V.t.caption, color = V.c.text3, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp))
            }
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Emoji you use will show up here.", style = V.t.label, color = V.c.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            items(list, key = { it }) { e ->
                Box(
                    Modifier.aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(if (stickers) 18.dp else 12.dp))
                        .then(if (stickers) Modifier.background(V.c.s2) else Modifier)
                        .pressable(scaleTo = 0.8f) {
                            fb.tick()
                            Recents.push(ctx, e)
                            if (stickers) onSticker(e) else {
                                onEmoji(e)
                                recents.remove(e); recents.add(0, e); if (recents.size > 32) recents.removeAt(recents.lastIndex)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val n = if (stickers) emojiOnlyCount(e).coerceAtLeast(1) else 1
                    Text(e, fontSize = if (stickers) (when (n) { 1 -> 36; 2 -> 24; else -> 17 }).sp else 28.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
