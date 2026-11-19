package com.rajdeep.vesper.ui.console

import com.rajdeep.vesper.ui.components.Reveal
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rajdeep.vesper.core.net.ApiException
import com.rajdeep.vesper.data.AdminRepository
import com.rajdeep.vesper.shared.ActivityEvent
import com.rajdeep.vesper.shared.AdminMember
import com.rajdeep.vesper.shared.AdminMemberDetail
import com.rajdeep.vesper.shared.AdminOverview
import com.rajdeep.vesper.shared.ConversationMeta
import com.rajdeep.vesper.shared.HallMessageDto
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.ui.chat.SheetAction
import com.rajdeep.vesper.ui.chat.SheetTitle
import com.rajdeep.vesper.ui.chat.clock
import com.rajdeep.vesper.ui.chat.clockFull
import com.rajdeep.vesper.ui.chat.dayLabel
import com.rajdeep.vesper.ui.chat.sameDay
import com.rajdeep.vesper.ui.components.EmptyState
import com.rajdeep.vesper.ui.components.Hairline
import com.rajdeep.vesper.ui.components.IconAction
import com.rajdeep.vesper.ui.components.ListRow
import com.rajdeep.vesper.ui.components.Monogram
import com.rajdeep.vesper.ui.components.SectionLabel
import com.rajdeep.vesper.ui.components.SkeletonRows
import com.rajdeep.vesper.ui.components.StatusDot
import com.rajdeep.vesper.ui.components.Surface
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.screens.joined
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConsoleTab(val label: String) { OVERVIEW("Overview"), ACTIVITY("Activity"), MEMBERS("Members"), HALL("Hall"), CONVERSATIONS("Conversations") }

@HiltViewModel
class ConsoleViewModel @Inject constructor(private val repo: AdminRepository) : ViewModel() {
    val tab = MutableStateFlow(ConsoleTab.OVERVIEW)
    val overview = MutableStateFlow<AdminOverview?>(null)
    val activity = MutableStateFlow<List<ActivityEvent>?>(null)
    val members = MutableStateFlow<List<AdminMember>?>(null)
    val hall = MutableStateFlow<List<HallMessageDto>?>(null)
    val conversations = MutableStateFlow<List<ConversationMeta>?>(null)
    val query = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val toast = MutableStateFlow<String?>(null)

    init {
        refresh()
        viewModelScope.launch {
            repo.activity.collect { e -> activity.value = (listOf(e) + (activity.value ?: emptyList())).take(300); if (tab.value == ConsoleTab.OVERVIEW) loadOverview() }
        }
        viewModelScope.launch {
            repo.presence.collect { p -> members.value = members.value?.map { if (it.id == p.userId) it.copy(online = p.online, lastActiveAt = p.lastActiveAt) else it } }
        }
        viewModelScope.launch { while (true) { delay(30_000); if (tab.value == ConsoleTab.OVERVIEW) loadOverview() } }
    }

    private fun <T> guard(block: suspend () -> T, set: (T) -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess(set).onFailure { error.value = (it as? ApiException)?.message ?: "Couldn't load" }
    }

    private fun loadOverview() = guard({ repo.overview() }) { overview.value = it }
    fun refresh() {
        loadOverview()
        guard({ repo.activityPage() }) { activity.value = it.items }
        guard({ repo.members(query.value) }) { members.value = it.items }
        guard({ repo.hall() }) { hall.value = it.items.reversed() }
        guard({ repo.conversations() }) { conversations.value = it.items }
    }
    fun select(t: ConsoleTab) { tab.value = t; if (t == ConsoleTab.HALL) guard({ repo.hall() }) { hall.value = it.items.reversed() } }
    fun search(q: String) { query.value = q; guard({ repo.members(q) }) { members.value = it.items } }
    fun deleteHall(id: String) = viewModelScope.launch {
        runCatching { repo.deleteHall(id) }.onSuccess { hall.value = hall.value?.filterNot { it.id == id }; toast.value = "Message deleted for everyone" }.onFailure { error.value = it.message }
    }
    fun mute(uid: String, minutes: Long?) = viewModelScope.launch { runCatching { repo.mute(uid, minutes) }.onSuccess { toast.value = "Muted in the Hall" } }
}

@Composable
fun ConsoleScreen(onMember: (String) -> Unit, vm: ConsoleViewModel = hiltViewModel()) {
    val tab by vm.tab.collectAsState()
    val toast by vm.toast.collectAsState()
    LaunchedEffect(toast) { if (toast != null) { delay(2500); vm.toast.value = null } }
    Box(Modifier.fillMaxSize().background(V.c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(V.accent)); Spacer(Modifier.width(10.dp))
                        Text("Console", style = V.t.title, color = V.c.text)
                    }
                    Text("Administration · private content is never visible here", style = V.t.label, color = V.c.text2, modifier = Modifier.padding(start = 17.dp, top = 2.dp))
                }
            }
            TabStrip(tab, vm::select)
            AnimatedContent(tab, transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(Motion.tw()) { it / 8 * dir } + fadeIn(Motion.tw())) togetherWith (slideOutHorizontally(Motion.tw()) { -it / 8 * dir } + fadeOut(Motion.tw(Motion.S)))
            }, modifier = Modifier.weight(1f), label = "console") { t ->
                when (t) {
                    ConsoleTab.OVERVIEW -> Overview(vm)
                    ConsoleTab.ACTIVITY -> Activity(vm)
                    ConsoleTab.MEMBERS -> Members(vm, onMember)
                    ConsoleTab.HALL -> HallModeration(vm)
                    ConsoleTab.CONVERSATIONS -> Conversations(vm)
                }
            }
        }
        Reveal(toast != null, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp), enter = fadeIn() + expandVertically(), exit = fadeOut()) {
            Text(toast ?: "", style = V.t.label, color = V.c.text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(V.c.s3).padding(14.dp))
        }
    }
}

@Composable
private fun TabStrip(selected: ConsoleTab, onSelect: (ConsoleTab) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConsoleTab.entries.forEach { t ->
            val sel = t == selected
            val bg by androidx.compose.animation.animateColorAsState(if (sel) V.accent.copy(alpha = 0.16f) else V.c.s1, label = "tab")
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).pressable(scaleTo = 0.96f) { onSelect(t) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(t.label, style = V.t.label, color = if (sel) V.accent else V.c.text2)
            }
        }
    }
}

@Composable
private fun Overview(vm: ConsoleViewModel) {
    val o by vm.overview.collectAsState()
    val ov = o ?: run { SkeletonRows(); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
            Metric("Members", ov.registered.toString(), Modifier.weight(1f))
            Metric("Online now", ov.onlineNow.toString(), Modifier.weight(1f), live = true)
            Metric("Active · 24h", ov.active24h.toString(), Modifier.weight(1f))
            Metric("Hall · 24h", ov.hall24h.toString(), Modifier.weight(1f))
        }
        Surface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Activity · last 72 hours", style = V.t.caption, color = V.c.text3)
                Spacer(Modifier.height(12.dp))
                Sparkline(ov.activity72h, Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(6.dp))
                Row { Text("72h ago", style = V.t.mono, color = V.c.text3, modifier = Modifier.weight(1f)); Text("now", style = V.t.mono, color = V.c.text3) }
            }
        }
        Surface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Private messages in the last 72 hours", style = V.t.caption, color = V.c.text3)
                Text("Counts only. Content is end-to-end encrypted.", style = V.t.label, color = V.c.text2, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                Row {
                    MiniStat("Text", ov.directText, Modifier.weight(1f)); MiniStat("Voice", ov.directVoice, Modifier.weight(1f)); MiniStat("Photos", ov.directImage, Modifier.weight(1f))
                }
            }
        }
        Surface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Storage", style = V.t.caption, color = V.c.text3)
                Meter("Database", ov.dbSizeBytes.toDouble(), ov.dbCapBytes.toDouble(), bytes(ov.dbSizeBytes) + " of " + bytes(ov.dbCapBytes))
                ov.mediaCreditsUsed?.let { used ->
                    val lim = ov.mediaCreditsLimit ?: 25.0
                    Meter("Cloudinary credits (30 days)", used, lim, "%.2f of %.0f".format(used, lim))
                }
                Text("Media on server now: " + bytes(ov.mediaResidentBytes) + " · " + ov.mediaProvider, style = V.t.label, color = V.c.text2)
            }
        }
        Surface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server", style = V.t.caption, color = V.c.text3)
                Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(true); Spacer(Modifier.width(8.dp)); Text("Up since " + clockFull(ov.serverStartedAt), style = V.t.bodySmall, color = V.c.text) }
                Text(
                    ov.lastKeepAlivePing?.let { "Keep-awake ping " + ago(ov.serverTime - it) + " ago" } ?: "No keep-awake ping yet — set up cron-job.org to call /health every 10 min",
                    style = V.t.label, color = if (ov.lastKeepAlivePing != null && ov.serverTime - ov.lastKeepAlivePing!! < 15 * 60_000) V.c.text2 else V.c.hall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, live: Boolean = false) {
    Surface(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) { StatusDot(true); Spacer(Modifier.width(6.dp)) }
                Text(label, style = V.t.caption, color = V.c.text3)
            }
            Spacer(Modifier.height(8.dp))
            AnimatedContent(value, transitionSpec = { (fadeIn() + androidx.compose.animation.slideInVertically { it / 2 }) togetherWith (fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }) }, label = "metric") {
                Text(it, style = V.t.metric, color = V.c.text)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, v: Long, modifier: Modifier) {
    Column(modifier) { Text(v.toString(), style = V.t.metric.copy(fontSize = V.t.heading.fontSize), color = V.c.text); Text(label, style = V.t.label, color = V.c.text2) }
}

@Composable
private fun Meter(label: String, used: Double, cap: Double, caption: String) {
    val frac = if (cap <= 0) 0f else (used / cap).toFloat().coerceIn(0f, 1f)
    val anim by animateFloatAsState(frac, Motion.tw(Motion.L), label = "meter")
    val color = when { frac > 0.9f -> V.c.error; frac > 0.7f -> V.c.hall; else -> V.accent }
    Column {
        Row { Text(label, style = V.t.bodySmall, color = V.c.text, modifier = Modifier.weight(1f)); Text(caption, style = V.t.mono, color = V.c.text2) }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(V.c.s3)) {
            Box(Modifier.fillMaxWidth(anim).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier) {
    val accent = V.accent
    val track = V.c.s3
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.tw(Motion.L * 2)) }
    Canvas(modifier) {
        val n = values.size.coerceAtLeast(1)
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val h = (size.height * (v.toFloat() / max) * grow.value).coerceAtLeast(2f)
            drawRoundRect(if (v == 0) track else accent.copy(alpha = 0.35f + 0.65f * v / max), Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(bw / 2))
        }
    }
}

@Composable
private fun Activity(vm: ConsoleViewModel) {
    val items by vm.activity.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "All", "hall" to "Hall", "direct" to "Private (metadata)", "signup" to "Sign-ups", "signin" to "Sign-ins").forEach { (k, l) ->
                val sel = filter == k
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) V.c.s3 else V.c.s1).pressable(scaleTo = 0.96f) { filter = k }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(l, style = V.t.label, color = if (sel) V.c.text else V.c.text2)
                }
            }
        }
        val list = items?.filter { filter == null || it.type == filter }
        when {
            list == null -> SkeletonRows()
            list.isEmpty() -> EmptyState("Nothing yet", "Activity from the last 72 hours appears here live.")
            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(list, key = { it.id }) { e -> Box(Modifier.animateItem(fadeInSpec = Motion.tw(), placementSpec = Motion.offsetSpring)) { ActivityRow(e) } }
            }
        }
    }
}

@Composable
private fun ActivityRow(e: ActivityEvent) {
    val (verb, color) = when (e.type) {
        "hall" -> "posted ${kindWord(e.kind)} in the Hall" to V.c.hall
        "direct" -> "sent ${kindWord(e.kind)} privately to" to V.c.direct
        "signup" -> "joined Vesper" to V.c.success
        "signin" -> "signed in" to V.c.text2
        "suspended" -> "was suspended" to V.c.error
        "unsuspended" -> "was reinstated" to V.c.success
        "muted" -> "was muted in the Hall" to V.c.hall
        "unmuted" -> "was unmuted" to V.c.text2
        "account_deleted" -> "account deleted" to V.c.error
        "password_changed" -> "changed password" to V.c.text2
        else -> e.type to V.c.text2
    }
    ListRow(leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) }) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString { append(e.username ?: "someone"); append(" "); append(verb); e.peerName?.let { append(" "); append(it) } },
                style = V.t.bodySmall, color = V.c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(if (sameDay(e.at, System.currentTimeMillis())) clock(e.at) else dayLabel(e.at), style = V.t.mono, color = V.c.text3)
    }
}

private fun kindWord(k: MessageKind?) = when (k) { MessageKind.VOICE -> "a voice message"; MessageKind.IMAGE -> "a photo"; else -> "a message" }

@Composable
private fun Members(vm: ConsoleViewModel, onMember: (String) -> Unit) {
    val list by vm.members.collectAsState()
    val q by vm.query.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(V.c.s1).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = V.c.text2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search members", style = V.t.bodySmall, color = V.c.text3)
                BasicTextField(q, vm::search, singleLine = true, textStyle = V.t.bodySmall.copy(color = V.c.text), cursorBrush = SolidColor(V.c.text), modifier = Modifier.fillMaxWidth())
            }
        }
        when {
            list == null -> SkeletonRows()
            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                items(list!!, key = { it.id }) { m ->
                    Box(Modifier.animateItem(placementSpec = Motion.offsetSpring)) {
                        ListRow(onClick = { onMember(m.id) }, leading = {
                            Box { Monogram(m.username, 42.dp); StatusDot(m.online, Modifier.align(Alignment.BottomEnd)) }
                        }) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(m.username, style = V.t.subhead, color = V.c.text)
                                    if (m.isAdmin) Tag("admin", V.c.console)
                                    if (m.suspended) Tag("suspended", V.c.error)
                                    if (m.hallMuteUntil != null) Tag("muted", V.c.hall)
                                }
                                Text(
                                    if (m.online) "Online now" else "Last active " + (m.lastActiveAt?.let { ago(System.currentTimeMillis() - it) + " ago" } ?: "—"),
                                    style = V.t.label, color = if (m.online) V.c.success else V.c.text2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = V.t.caption, color = color, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HallModeration(vm: ConsoleViewModel) {
    val list by vm.hall.collectAsState()
    var sel by remember { mutableStateOf<HallMessageDto?>(null) }
    when {
        list == null -> SkeletonRows()
        list!!.isEmpty() -> EmptyState("The Hall is empty", "Messages from the last 72 hours appear here for moderation.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list!!, key = { it.id }) { m ->
                Box(Modifier.animateItem(fadeOutSpec = Motion.tw(Motion.L), placementSpec = Motion.offsetSpring)) {
                    ListRow(onClick = { sel = m }, leading = { Monogram(m.senderName, 36.dp) }) {
                        Column(Modifier.weight(1f)) {
                            Row { Text(m.senderName, style = V.t.label, color = V.c.hall, modifier = Modifier.weight(1f)); Text(clock(m.acceptedAt), style = V.t.mono, color = V.c.text3) }
                            Text(
                                when (m.kind) { MessageKind.TEXT -> m.body.text ?: ""; MessageKind.VOICE -> "Voice message · " + com.rajdeep.vesper.ui.chat.duration(m.body.media?.durationMs ?: 0); MessageKind.IMAGE -> "Photo" },
                                style = V.t.bodySmall, color = V.c.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    sel?.let { m ->
        ModalBottomSheet({ sel = null }, containerColor = V.c.s1) {
            SheetTitle(m.senderName, "Posted " + clockFull(m.acceptedAt) + "\nDisappears " + clockFull(m.expiresAt))
            SheetAction("Delete for everyone", danger = true) { vm.deleteHall(m.id); sel = null }
            SheetAction("Mute author for 1 hour") { vm.mute(m.senderId, 60); sel = null }
            SheetAction("Mute author for 24 hours") { vm.mute(m.senderId, 24 * 60); sel = null }
            SheetAction("Mute author until lifted") { vm.mute(m.senderId, null); sel = null }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Conversations(vm: ConsoleViewModel) {
    val list by vm.conversations.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text(
            "Who is talking with whom. Message content is end-to-end encrypted and never reaches the Console.",
            style = V.t.label, color = V.c.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        when {
            list == null -> SkeletonRows()
            list!!.isEmpty() -> EmptyState("No private conversations", "Pairs appear here once members message each other.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list!!, key = { it.a + it.b }) { c ->
                    ListRow(leading = { PairAvatars(c.aName, c.bName) }) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.aName}  ↔  ${c.bName}", style = V.t.subhead, color = V.c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${c.text} text · ${c.voice} voice · ${c.image} photos · 72h", style = V.t.label, color = V.c.text2)
                        }
                        Text(if (sameDay(c.lastActivityAt, System.currentTimeMillis())) clock(c.lastActivityAt) else dayLabel(c.lastActivityAt), style = V.t.mono, color = V.c.text3)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairAvatars(a: String, b: String) {
    Box(Modifier.width(54.dp).height(40.dp)) {
        Monogram(a, 32.dp, Modifier.align(Alignment.TopStart))
        Monogram(b, 32.dp, Modifier.align(Alignment.BottomEnd), ring = V.c.bg)
    }
}

// ============================================================== member detail

@HiltViewModel
class MemberViewModel @Inject constructor(handle: SavedStateHandle, private val repo: AdminRepository) : ViewModel() {
    val id: String = handle["id"]!!
    val detail = MutableStateFlow<AdminMemberDetail?>(null)
    val message = MutableStateFlow<String?>(null)
    val gone = MutableStateFlow(false)
    init { load() }
    fun load() = viewModelScope.launch { runCatching { repo.member(id) }.onSuccess { detail.value = it }.onFailure { message.value = it.message } }
    private fun act(done: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { message.value = done; load() }.onFailure { message.value = (it as? ApiException)?.message ?: "Action failed" }
    }
    fun suspend() = act("Suspended and signed out everywhere") { repo.suspend(id) }
    fun unsuspend() = act("Reinstated") { repo.unsuspend(id) }
    fun mute(min: Long?) = act("Muted in the Hall") { repo.mute(id, min) }
    fun unmute() = act("Unmuted") { repo.unmute(id) }
    fun signOut() = act("Signed out of all devices") { repo.signOutAll(id) }
    fun delete() = viewModelScope.launch { runCatching { repo.deleteMember(id) }.onSuccess { gone.value = true }.onFailure { message.value = it.message } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberScreen(onBack: () -> Unit, vm: MemberViewModel = hiltViewModel()) {
    val d by vm.detail.collectAsState()
    val msg by vm.message.collectAsState()
    val gone by vm.gone.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(gone) { if (gone) onBack() }
    LaunchedEffect(msg) { if (msg != null) { delay(2600); vm.message.value = null } }
    Box(Modifier.fillMaxSize().background(V.c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = V.c.text, modifier = Modifier.padding(4.dp), onClick = onBack)
            val det = d ?: run { SkeletonRows(4); return@Column }
            val m = det.member
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box { Monogram(m.username, 88.dp); StatusDot(m.online, Modifier.align(Alignment.BottomEnd).size(16.dp)) }
                Spacer(Modifier.height(12.dp))
                Text(m.username, style = V.t.name, color = V.c.text)
                Text(if (m.online) "Online now" else "Last active " + (m.lastActiveAt?.let { clockFull(it) } ?: "—"), style = V.t.label, color = if (m.online) V.c.success else V.c.text2)
            }
            SectionLabel("Details", Modifier.padding(top = 16.dp))
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column {
                    KV("Joined", clockFull(m.joinedAt)); Hairline()
                    KV("Signed-in devices", m.devices.toString()); Hairline()
                    KV("Hall messages · 72h", det.hallMessages72h.toString()); Hairline()
                    KV("Status", listOfNotNull(if (m.suspended) "Suspended" else "Active", m.hallMuteUntil?.let { "muted until " + if (it > 4_000_000_000_000L) "lifted" else clockFull(it) }).joinToString(" · "))
                }
            }
            SectionLabel("Private conversations · metadata only", Modifier.padding(top = 16.dp))
            if (det.partners.isEmpty()) Text("None", style = V.t.bodySmall, color = V.c.text3, modifier = Modifier.padding(horizontal = 20.dp))
            det.partners.forEach { p ->
                ListRow(leading = { Monogram(p.username, 36.dp) }) {
                    Column(Modifier.weight(1f)) {
                        Text(p.username, style = V.t.subhead, color = V.c.text)
                        Text("${p.text} text · ${p.voice} voice · ${p.image} photos · last ${dayLabel(p.lastActivityAt)} ${clock(p.lastActivityAt)}", style = V.t.label, color = V.c.text2)
                    }
                }
            }
            if (!m.isAdmin) {
                SectionLabel("Actions", Modifier.padding(top = 16.dp))
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column {
                        if (m.hallMuteUntil != null) Action("Unmute in the Hall") { vm.unmute() } else {
                            Action("Mute in the Hall · 1 hour") { vm.mute(60) }; Hairline()
                            Action("Mute in the Hall · 24 hours") { vm.mute(24 * 60) }; Hairline()
                            Action("Mute in the Hall · until lifted") { vm.mute(null) }
                        }
                        Hairline(); Action("Sign out of all devices") { vm.signOut() }
                        Hairline()
                        if (m.suspended) Action("Reinstate account") { vm.unsuspend() } else Action("Suspend account", danger = true) { confirm = "suspend" }
                        Hairline(); Action("Delete account", danger = true) { typed = ""; confirm = "delete" }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        Reveal(msg != null, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp), enter = fadeIn() + expandVertically(), exit = fadeOut()) {
            Text(msg ?: "", style = V.t.label, color = V.c.text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(V.c.s3).padding(14.dp))
        }
    }
    when (confirm) {
        "suspend" -> ModalBottomSheet({ confirm = null }, containerColor = V.c.s1) {
            SheetTitle("Suspend ${d?.member?.username}?", "They are signed out everywhere immediately and can't sign in until reinstated.")
            SheetAction("Suspend", danger = true) { vm.suspend(); confirm = null }
            Spacer(Modifier.height(24.dp))
        }
        "delete" -> ModalBottomSheet({ confirm = null }, containerColor = V.c.s1) {
            val name = d?.member?.username ?: ""
            SheetTitle("Delete $name?", "Removes the account, its devices and keys, and everything it posted in the Hall. Type the username to confirm.")
            com.rajdeep.vesper.ui.components.VField(typed, { typed = it }, "Username", modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            com.rajdeep.vesper.ui.components.PrimaryButton("Delete permanently", Modifier.padding(horizontal = 20.dp).fillMaxWidth(), enabled = typed == name, color = V.c.error) { vm.delete(); confirm = null }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun KV(k: String, v: String) = ListRow {
    Text(k, style = V.t.bodySmall, color = V.c.text2, modifier = Modifier.padding(end = 16.dp))
    Text(v, style = V.t.bodySmall, color = V.c.text, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
}
@Composable private fun Action(t: String, danger: Boolean = false, onClick: () -> Unit) = ListRow(onClick = onClick) { Text(t, style = V.t.body, color = if (danger) V.c.error else V.c.text) }

fun bytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1048576.0)
}

fun ago(ms: Long): String {
    val s = ms / 1000
    return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; else -> "${s / 86400}d" }
}
