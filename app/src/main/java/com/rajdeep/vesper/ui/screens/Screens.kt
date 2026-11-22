package com.rajdeep.vesper.ui.screens

import kotlinx.coroutines.flow.map
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Insights
import com.rajdeep.vesper.ui.components.springOverscroll
import com.rajdeep.vesper.ui.components.Reveal
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rajdeep.vesper.BuildConfig
import com.rajdeep.vesper.core.db.ConversationRow
import com.rajdeep.vesper.core.db.MsgState
import com.rajdeep.vesper.core.net.ApiException
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.data.AuthRepository
import com.rajdeep.vesper.data.Messages
import com.rajdeep.vesper.data.Notifier
import com.rajdeep.vesper.data.PeopleRepository
import com.rajdeep.vesper.data.Prefs
import com.rajdeep.vesper.data.Settings
import com.rajdeep.vesper.data.ThemeMode
import com.rajdeep.vesper.shared.AdminDevice
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.UserSummary
import com.rajdeep.vesper.ui.chat.SheetTitle
import com.rajdeep.vesper.ui.chat.clock
import com.rajdeep.vesper.ui.chat.dayLabel
import com.rajdeep.vesper.ui.chat.sameDay
import com.rajdeep.vesper.ui.components.EmptyState
import com.rajdeep.vesper.ui.components.GhostButton
import com.rajdeep.vesper.ui.components.Hairline
import com.rajdeep.vesper.ui.components.IconAction
import com.rajdeep.vesper.ui.components.ListRow
import com.rajdeep.vesper.ui.components.Monogram
import com.rajdeep.vesper.ui.components.PrimaryButton
import com.rajdeep.vesper.ui.components.ScreenHeader
import com.rajdeep.vesper.ui.components.SectionLabel
import com.rajdeep.vesper.ui.components.SkeletonRows
import com.rajdeep.vesper.ui.components.Surface
import com.rajdeep.vesper.ui.components.Toggle
import com.rajdeep.vesper.ui.components.VField
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.components.staggerIn
import com.rajdeep.vesper.ui.components.glassCard
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.lazy.itemsIndexed
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ============================================================== Direct list

@HiltViewModel
class DirectListViewModel @Inject constructor(messages: Messages, private val session: SessionManager) : ViewModel() {
    val myName get() = session.current()?.username ?: ""
    val rows = messages.directList().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val online = messages.onlineUsers
    val typing = messages.directTypers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
}

@Composable
fun DirectListScreen(onOpen: (String) -> Unit, onFindPeople: () -> Unit, vm: DirectListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsState()
    val online by vm.online.collectAsState()
    val typing by vm.typing.collectAsState()
    val accent = V.c.direct
    val list = rows
    val unread = list?.sumOf { it.unread } ?: 0
    val activeNow = list.orEmpty().filter { it.peerId in online && it.peerName != "Notes to self" }.map { it.peerId to (it.peerName ?: "…") }
    Box(Modifier.fillMaxSize().background(V.c.bg)) {
        com.rajdeep.vesper.ui.components.Aurora(accent, lerpColor(accent, V.c.success, 0.5f), intensity = 1.5f)
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().springOverscroll(), overscrollEffect = null,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item(key = "head") { InboxHeader(vm.myName, unread, online.size, accent, onFindPeople) }
            if (activeNow.isNotEmpty()) {
                item(key = "activeLabel") { com.rajdeep.vesper.ui.components.FadeLabel("Active now", Modifier.animateItem().padding(top = 14.dp, bottom = 10.dp)) }
                item(key = "active") { com.rajdeep.vesper.ui.components.ActiveStrip(activeNow, accent, onOpen, Modifier.animateItem()) }
            }
            item(key = "msgLabel") { com.rajdeep.vesper.ui.components.FadeLabel("Messages", Modifier.animateItem().padding(top = 18.dp, bottom = 8.dp)) }
            when {
                list == null -> item(key = "sk") { SkeletonRows() }
                list.isEmpty() -> item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Lock, null, tint = accent, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(18.dp))
                        GhostButton("Find people", color = accent, onClick = onFindPeople)
                    }
                }
                else -> itemsIndexed(list, key = { _, r -> r.peerId }) { i, r ->
                    Box(Modifier.animateItem(placementSpec = Motion.offsetSpring).staggerIn(i, r.peerId).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ConversationItem(r, online = r.peerId in online && r.peerName != "Notes to self", typing = r.peerId in typing) { onOpen(r.peerId) }
                    }
                }
            }
        }
    }
}

private fun lerpColor(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

@Composable
private fun InboxHeader(name: String, unread: Int, onlineCount: Int, accent: androidx.compose.ui.graphics.Color, onCompose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(com.rajdeep.vesper.ui.components.greeting() + if (name.isNotEmpty()) ", $name" else "", style = V.t.label, color = V.c.text2)
                Text("Inbox", style = V.t.display.copy(fontSize = 38.sp, lineHeight = 42.sp), color = V.c.text)
            }
            // Compose: gradient orb that breathes softly.
            val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "orb")
            val glow by t.animateFloat(0.25f, 0.55f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800), androidx.compose.animation.core.RepeatMode.Reverse), label = "g")
            Box(
                Modifier.size(52.dp).drawBehind { drawCircle(accent.copy(alpha = glow * 0.5f), radius = size.minDimension * 0.62f) }
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent, androidx.compose.ui.graphics.lerp(accent, androidx.compose.ui.graphics.Color.Black, 0.35f))))
                    .pressable(scaleTo = 0.9f, onClick = onCompose).semantics { contentDescription = "New message" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Edit, null, tint = V.c.onAccent, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.animation.AnimatedVisibility(onlineCount > 0, enter = androidx.compose.animation.scaleIn(Motion.bouncy()) + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
                com.rajdeep.vesper.ui.components.StatChip("$onlineCount online", V.c.success, pulse = true)
            }
            androidx.compose.animation.AnimatedVisibility(unread > 0, enter = androidx.compose.animation.scaleIn(Motion.bouncy()) + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
                com.rajdeep.vesper.ui.components.StatChip("$unread unread", accent, icon = Icons.Rounded.MarkEmailUnread)
            }
            com.rajdeep.vesper.ui.components.StatChip("Encrypted", V.c.text2, icon = Icons.Rounded.Lock)
        }
    }
}

@Composable
private fun ConversationItem(r: ConversationRow, online: Boolean = false, typing: Boolean = false, onClick: () -> Unit) {
    val accent = V.c.direct
    val hot = r.unread > 0 || typing
    val edge by androidx.compose.animation.animateColorAsState(if (hot) accent.copy(alpha = 0.45f) else V.c.hairline.copy(alpha = 0.7f), Motion.tw(), label = "edge")
    val self = r.peerName == "Notes to self"
    ListRow(
        modifier = Modifier.glassCard(V.c.s1.copy(alpha = 0.72f), edge, if (hot) accent else null),
        onClick = onClick,
        leading = {
            if (self) Box(
                Modifier.size(48.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent.copy(alpha = 0.9f), V.c.success.copy(alpha = 0.7f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Bookmark, null, tint = V.c.onAccent, modifier = Modifier.size(22.dp)) }
            else com.rajdeep.vesper.ui.components.PresenceAvatar(r.peerName ?: "?", 48.dp, online)
        },
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.peerName ?: "…", style = V.t.subhead.copy(fontWeight = if (r.unread > 0) androidx.compose.ui.text.font.FontWeight.SemiBold else V.t.subhead.fontWeight), color = V.c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.lastKind.isNotEmpty()) Text(listDate(r.lastAt), style = V.t.mono.copy(fontSize = V.t.label.fontSize), color = if (r.unread > 0) V.accent else V.c.text3)
            }
            Spacer(Modifier.height(3.dp))
            // Typing beats the last-message preview (WhatsApp inbox).
            if (typing) {
                com.rajdeep.vesper.ui.components.LiveStatusLine("typing", resting = {}, style = V.t.bodySmall, accent = V.accent)
                return@Column
            }
            if (r.lastKind.isEmpty()) {
                Text(if (r.peerName == "Notes to self") "Only on this phone · test anything here" else "Tap to message", style = V.t.bodySmall, color = V.c.text3, maxLines = 1)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.lastOutgoing) {
                    Icon(
                        when (r.lastState) { MsgState.PENDING -> Icons.Rounded.Schedule; MsgState.DELIVERED, MsgState.READ -> Icons.Rounded.DoneAll; MsgState.FAILED -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.Done },
                        null, tint = when (r.lastState) { MsgState.READ -> com.rajdeep.vesper.ui.chat.SeenBlue; MsgState.FAILED -> V.c.error; else -> V.c.text3 }, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                val kind = MessageKind.of(r.lastKind)
                if (kind != MessageKind.TEXT) {
                    Icon(when (kind) { MessageKind.VOICE -> Icons.Rounded.GraphicEq; MessageKind.FILE -> Icons.Rounded.AttachFile; else -> Icons.Rounded.Image }, null, tint = V.c.text2, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    when (kind) { MessageKind.TEXT -> r.lastText ?: ""; MessageKind.VOICE -> "Voice message"; MessageKind.IMAGE -> "Photo"; MessageKind.FILE -> "File" },
                    style = V.t.bodySmall, color = if (r.unread > 0) V.c.text else V.c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Reveal(r.unread > 0, enter = scaleIn(Motion.bouncy()) + fadeIn(), exit = fadeOut()) {
                    Box(Modifier.padding(start = 8.dp).heightIn(min = 20.dp).clip(CircleShape).background(V.accent).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                        Text(r.unread.toString(), style = V.t.caption, color = V.c.onAccent)
                    }
                }
            }
        }
    }
}

// ============================================================== People

@HiltViewModel
class PeopleViewModel @Inject constructor(private val repo: PeopleRepository, private val session: SessionManager, messages: Messages) : ViewModel() {
    val online = messages.onlineUsers
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<UserSummary>?>(null)
    val error = MutableStateFlow<String?>(null)
    val me get() = session.current()?.userId
    private var job: kotlinx.coroutines.Job? = null

    init { if (cached != null) results.value = cached; search("") }

    /** Debounced search; shows the last list instantly and quietly retries (with backoff) instead of erroring. */
    fun search(q: String) {
        query.value = q
        job?.cancel()
        job = viewModelScope.launch {
            delay(if (q.isEmpty()) 0 else 250)
            var wait = 1_000L
            while (true) {
                val r = runCatching { repo.search(q) }
                if (r.isSuccess) {
                    results.value = r.getOrThrow().items; error.value = null
                    if (q.isEmpty()) cached = results.value
                    break
                }
                delay(wait); wait = (wait * 2).coerceAtMost(15_000)
            }
        }
    }

    companion object { @Volatile private var cached: List<UserSummary>? = null }
}

@Composable
fun PeopleScreen(onProfile: (String) -> Unit, vm: PeopleViewModel = hiltViewModel()) {
    val q by vm.query.collectAsState()
    val res by vm.results.collectAsState()
    val online by vm.online.collectAsState()
    LaunchedEffect(Unit) { vm.search(vm.query.value) }
    val accent = V.c.text
    val warm = V.c.hall
    val cool = V.c.hall // People stays in the Hall gold family
    val list = res
    val onlineNow = list.orEmpty().filter { it.id in online && it.id != vm.me }
    Box(Modifier.fillMaxSize().background(V.c.bg)) {
        com.rajdeep.vesper.ui.components.Aurora(warm, androidx.compose.ui.graphics.lerp(warm, androidx.compose.ui.graphics.Color.White, 0.45f), intensity = 1.1f)
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().springOverscroll(), overscrollEffect = null, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                    Text("Find your people", style = V.t.label, color = V.c.text2)
                    Text("People", style = V.t.display.copy(fontSize = 38.sp, lineHeight = 42.sp), color = V.c.text)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        com.rajdeep.vesper.ui.components.StatChip("${list?.size ?: 0} members", warm, icon = Icons.Rounded.Groups)
                        androidx.compose.animation.AnimatedVisibility(online.isNotEmpty(), enter = androidx.compose.animation.scaleIn(Motion.bouncy()) + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
                            com.rajdeep.vesper.ui.components.StatChip("${online.size} online now", V.c.success, pulse = true)
                        }
                    }
                }
            }
            item(key = "search") { SearchField(q, vm::search, warm) }
            if (onlineNow.isNotEmpty() && q.isEmpty()) {
                item(key = "olabel") { com.rajdeep.vesper.ui.components.FadeLabel("Online now", Modifier.animateItem().padding(top = 6.dp, bottom = 10.dp)) }
                item(key = "ostrip") { com.rajdeep.vesper.ui.components.ActiveStrip(onlineNow.map { it.id to it.username }, warm, onProfile, Modifier.animateItem()) }
            }
            item(key = "alabel") { com.rajdeep.vesper.ui.components.FadeLabel(if (q.isEmpty()) "Everyone" else "Results", Modifier.animateItem().padding(top = 18.dp, bottom = 8.dp)) }
            when {
                list == null -> item(key = "sk") { SkeletonRows() }
                list.isEmpty() -> item(key = "empty") { EmptyState("No one by that name", "Usernames use a–z, 0–9, _ and .", Modifier.padding(top = 40.dp)) }
                else -> itemsIndexed(list, key = { _, it -> it.id }) { i, u ->
                    val on = u.id in online
                    val mine = u.id == vm.me
                    Box(Modifier.animateItem(placementSpec = Motion.offsetSpring).staggerIn(i, u.id).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ListRow(
                            modifier = Modifier.glassCard(V.c.s1.copy(alpha = 0.72f), V.c.hairline.copy(alpha = 0.7f), if (on) V.c.success else null),
                            onClick = { onProfile(u.id) },
                            leading = { com.rajdeep.vesper.ui.components.RingAvatar(u.username, 52.dp, if (mine) cool else warm, on, spinning = on) },
                            trailing = {
                                if (on && !mine) com.rajdeep.vesper.ui.components.StatChip("Online", V.c.success, pulse = true)
                                else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = V.c.text3)
                            },
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(u.username, style = V.t.subhead, color = V.c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (mine) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("you", style = V.t.caption, color = cool, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cool.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp))
                                    }
                                }
                                Text("Joined " + joined(u.joinedAt), style = V.t.label, color = V.c.text2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(q: String, onChange: (String) -> Unit, glow: androidx.compose.ui.graphics.Color) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val edge by androidx.compose.animation.animateColorAsState(if (focused) glow.copy(alpha = 0.6f) else V.c.hairline, Motion.tw(), label = "edge")
    val lift by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, Motion.spring(), label = "lift")
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth().heightIn(min = 52.dp)
            .graphicsLayer { scaleX = 1f + 0.01f * lift; scaleY = scaleX }
            .drawBehind { if (lift > 0f) drawRoundRect(glow.copy(alpha = 0.10f * lift), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()), size = size) }
            .clip(RoundedCornerShape(18.dp)).background(V.c.s1.copy(alpha = 0.85f)).border(1.dp, edge, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (focused) glow else V.c.text2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) Text("Search by username", style = V.t.body, color = V.c.text3)
            BasicTextField(q, onChange, singleLine = true, textStyle = V.t.body.copy(color = V.c.text), cursorBrush = SolidColor(glow), interactionSource = src, modifier = Modifier.fillMaxWidth())
        }
        com.rajdeep.vesper.ui.components.Reveal(q.isNotEmpty(), enter = androidx.compose.animation.scaleIn(Motion.bouncy()) + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
            Icon(Icons.Rounded.Close, "Clear", tint = V.c.text2, modifier = Modifier.size(20.dp).clip(CircleShape).pressable { onChange("") })
        }
    }
}

private val joinedFmt = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
fun joined(ms: Long) = if (ms <= 0) "recently" else joinedFmt.format(Date(ms))

// ============================================================== Profile

@HiltViewModel
class ProfileViewModel @Inject constructor(handle: SavedStateHandle, private val repo: PeopleRepository, private val session: SessionManager) : ViewModel() {
    val id: String = handle["id"]!!
    val user = MutableStateFlow<UserSummary?>(null)
    val isMe get() = session.current()?.userId == id
    init { viewModelScope.launch { runCatching { repo.profile(id) }.onSuccess { user.value = it } } }
}

@Composable
fun ProfileScreen(onBack: () -> Unit, onMessage: (String) -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val u by vm.user.collectAsState()
    Column(Modifier.fillMaxSize().background(V.c.bg).statusBarsPadding()) {
        IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = V.c.text, modifier = Modifier.padding(4.dp), onClick = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp))
            Reveal(u != null, enter = scaleIn(Motion.spring(), initialScale = 0.8f) + fadeIn(Motion.tw())) {
                Monogram(u?.username ?: "", 112.dp, ring = V.c.hairline)
            }
            Spacer(Modifier.height(20.dp))
            Text(u?.username ?: " ", style = V.t.name, color = V.c.text)
            Text(if (u != null) "Member since " + joined(u!!.joinedAt) else " ", style = V.t.bodySmall, color = V.c.text2, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(36.dp))
            if (!vm.isMe && u != null) {
                PrimaryButton("Message privately", Modifier.fillMaxWidth(), color = V.c.direct) { onMessage(vm.id) }
                Spacer(Modifier.height(12.dp))
                Text("Private messages are end-to-end encrypted.", style = V.t.label, color = V.c.text3)
            }
        }
    }
}

// ============================================================== Chat info (safety numbers)

@HiltViewModel
class ChatInfoViewModel @Inject constructor(handle: SavedStateHandle, private val messages: Messages, private val people: PeopleRepository) : ViewModel() {
    val peer: String = handle["peer"]!!
    val numbers = MutableStateFlow<List<Pair<Int, String>>?>(null)
    val user = MutableStateFlow<UserSummary?>(null)
    init {
        viewModelScope.launch { numbers.value = runCatching { messages.safetyNumbers(peer) }.getOrDefault(emptyList()) }
        viewModelScope.launch { user.value = runCatching { people.profile(peer) }.getOrNull() }
    }
}

@Composable
fun ChatInfoScreen(onBack: () -> Unit, onProfile: (String) -> Unit, vm: ChatInfoViewModel = hiltViewModel()) {
    val nums by vm.numbers.collectAsState()
    val u by vm.user.collectAsState()
    Column(Modifier.fillMaxSize().background(V.c.bg).statusBarsPadding().verticalScroll(rememberScrollState())) {
        IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = V.c.text, modifier = Modifier.padding(4.dp), onClick = onBack)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Monogram(u?.username ?: "?", 84.dp)
            Spacer(Modifier.height(12.dp))
            Text(u?.username ?: "…", style = V.t.name.copy(fontSize = 20.sp), color = V.c.text)
            Spacer(Modifier.height(10.dp))
            GhostButton("View profile", onClick = { onProfile(vm.peer) })
        }
        SectionLabel("Encryption", Modifier.padding(top = 20.dp))
        Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = V.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("End-to-end encrypted", style = V.t.subhead, color = V.c.text)
                }
                Spacer(Modifier.height(6.dp))
                Text("Messages, voice and photos here are encrypted on your phone with the Signal Protocol. Only you and this person's devices hold the keys.", style = V.t.bodySmall, color = V.c.text2)
            }
        }
        SectionLabel("Safety numbers", Modifier.padding(top = 16.dp))
        Text(
            "Compare these with the other person in person. If they match on both phones, nobody is in the middle. Each signed-in device has its own number.",
            style = V.t.bodySmall, color = V.c.text2, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))
        when {
            nums == null -> SkeletonRows(2)
            nums!!.isEmpty() -> Text("Send a message first — numbers appear once a secure session exists.", style = V.t.bodySmall, color = V.c.text3, modifier = Modifier.padding(20.dp))
            else -> nums!!.forEach { (d, n) ->
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), color = V.c.s2) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Device $d", style = V.t.caption, color = V.c.text3)
                        Spacer(Modifier.height(8.dp))
                        n.chunked(20).forEach { line ->
                            Text(line.chunked(5).joinToString("  "), style = V.t.mono.copy(fontSize = V.t.body.fontSize), color = V.c.text)
                        }
                    }
                }
            }
        }
        SectionLabel("What the administrator sees", Modifier.padding(top = 16.dp))
        Text("That you two talk, when, and the type of each message (text, voice or photo). Never the content.", style = V.t.bodySmall, color = V.c.text2, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(40.dp))
    }
}

// ============================================================== You

@HiltViewModel
class YouViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val settings: Settings,
    private val session: SessionManager,
    private val notifier: Notifier,
    messages: Messages,
    rt: com.rajdeep.vesper.core.net.Realtime,
) : ViewModel() {
    val chats = messages.directList().map { l -> l.count { it.peerName != "Notes to self" } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val online = rt.link.map { it == com.rajdeep.vesper.core.net.Link.ONLINE }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val prefs = settings.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, Prefs())
    val me get() = session.current()
    val devices = MutableStateFlow<List<AdminDevice>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init { loadDevices() }
    fun loadDevices() = viewModelScope.launch { runCatching { auth.myDevices() }.onSuccess { devices.value = it.items } }
    fun signOutDevice(d: Int) = viewModelScope.launch {
        runCatching { auth.signOutDevice(d) }.onSuccess { message.value = "Device signed out"; loadDevices() }
            .onFailure { message.value = (it as? ApiException)?.message ?: "Couldn't sign out device" }
    }
    fun signOutOthers() = viewModelScope.launch {
        runCatching { auth.signOutOthers() }.onSuccess { message.value = "Other devices signed out"; loadDevices() }
            .onFailure { message.value = (it as? ApiException)?.message ?: "Couldn't sign out devices" }
    }

    fun theme(t: ThemeMode) = viewModelScope.launch { settings.setTheme(t) }
    fun sounds(v: Boolean) = viewModelScope.launch { settings.setSounds(v) }
    fun haptics(v: Boolean) = viewModelScope.launch { settings.setHaptics(v) }
    fun motion(v: Boolean) = viewModelScope.launch { settings.setReduceMotion(v) }
    fun turnOffHallNotifications(v: Boolean) = viewModelScope.launch {
        settings.setTurnOffHallNotifications(v)
        if (v) notifier.cancelHall()
    }
    fun pauseAll(v: Boolean) = viewModelScope.launch {
        settings.setPauseAll(v)
        if (v) { notifier.cancelHall(); notifier.cancelDirectAll() }
    }
    fun turnOffInboxNotifications(v: Boolean) = viewModelScope.launch {
        settings.setTurnOffInboxNotifications(v)
        if (v) notifier.cancelDirectAll()
    }
    fun signOut() = viewModelScope.launch { busy.value = true; auth.signOut() }
    fun changePassword(u: String, cur: String, next: String, done: () -> Unit) = viewModelScope.launch {
        busy.value = true
        runCatching { auth.changePassword(u, cur, next) }.onSuccess { message.value = "Password changed. Other devices will need to sign in again."; done() }
            .onFailure { message.value = (it as? ApiException)?.message ?: "Couldn't change password" }
        busy.value = false
    }
    fun delete(pw: String) = viewModelScope.launch {
        busy.value = true
        runCatching { auth.deleteAccount(pw) }.onFailure { message.value = (it as? ApiException)?.message ?: "Couldn't delete account"; busy.value = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(onConsole: (() -> Unit)? = null, vm: YouViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsState()
    val devices by vm.devices.collectAsState()
    val msg by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val me = vm.me
    var sheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { if (msg != null) { delay(3500); vm.message.value = null } }
    Box(Modifier.fillMaxSize().background(V.c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            com.rajdeep.vesper.ui.components.ProfileHero(
                name = me?.username ?: "",
                isAdmin = me?.isAdmin == true,
                joinedLabel = joined(me?.joinedAt ?: 0),
            )
            SectionLabel("Appearance")
            Group {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { t ->
                        val sel = prefs.theme == t
                        Box(
                            Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) V.c.s3 else V.c.s1).pressable(scaleTo = 0.97f) { vm.theme(t) },
                            contentAlignment = Alignment.Center,
                        ) { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }, style = V.t.label, color = if (sel) V.c.text else V.c.text2) }
                    }
                }
            }
            SectionLabel("Feedback")
            Group {
                ToggleRow("Sounds", "Soft cues for sending and receiving", prefs.sounds, vm::sounds)
                Hairline()
                ToggleRow("Haptics", "Light touch feedback", prefs.haptics, vm::haptics)
            }
            if (onConsole != null) {
                SectionLabel("Administration")
                Group {
                    ListRow(onClick = onConsole, leading = {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(V.c.console.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Insights, null, tint = V.c.console, modifier = Modifier.size(20.dp))
                        }
                    }, trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = V.c.text3) }) {
                        Column(Modifier.weight(1f)) {
                            Text("Console", style = V.t.body, color = V.c.text)
                            Text("Members, activity and moderation", style = V.t.label, color = V.c.text2)
                        }
                    }
                }
            }
            SectionLabel("Notifications")
            Group {
                ToggleRow("Turn Off All Notifications", "Silence every Vesper notification", prefs.pauseAll, vm::pauseAll)
                Hairline()
                ToggleRow("Turn Off Hall Notifications", "Mute all notifications from the Hall", prefs.turnOffHallNotifications, vm::turnOffHallNotifications)
                Hairline()
                ToggleRow("Turn Off Inbox Notifications", "Mute all notifications from private chats", prefs.turnOffInboxNotifications, vm::turnOffInboxNotifications)
            }
            SectionLabel("About")
            com.rajdeep.vesper.ui.components.AboutContent()
            Spacer(Modifier.height(8.dp))
            SectionLabel("Account")
            Group {
                ListRow(onClick = { sheet = "password" }) { Text("Change password", style = V.t.body, color = V.c.text, modifier = Modifier.weight(1f)) }
                Hairline()
                ListRow(onClick = { vm.signOut() }) { Text("Sign out", style = V.t.body, color = V.c.text, modifier = Modifier.weight(1f)) }
                if (me?.isAdmin != true) {
                    Hairline()
                    ListRow(onClick = { sheet = "delete" }) { Text("Delete account", style = V.t.body, color = V.c.error, modifier = Modifier.weight(1f)) }
                }
            }
            Text("Vesper ${BuildConfig.VERSION_NAME}", style = V.t.mono, color = V.c.text3, modifier = Modifier.padding(20.dp).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
        }
        Reveal(msg != null, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp), enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
            Text(msg ?: "", style = V.t.label, color = V.c.text, modifier = Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(14.dp)).background(V.c.s3).padding(14.dp))
        }
    }
    when (sheet) {
        "password" -> ModalBottomSheet({ sheet = null }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = V.c.s1) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
                SheetTitle("Change password", "Other devices signed in to this account will be signed out.")
                com.rajdeep.vesper.ui.auth.PasswordForm(busy) { u, cur, next -> vm.changePassword(u, cur, next) { sheet = null } }
                msg?.let { Text(it, style = V.t.bodySmall, color = V.c.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
                Spacer(Modifier.height(32.dp))
            }
        }
        "delete" -> ModalBottomSheet({ sheet = null }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = V.c.s1) {
            var pw by remember { mutableStateOf("") }
            SheetTitle("Delete account", "Your account, keys and everything you posted are removed from the server and this phone right away. This can't be undone.")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VField(pw, { pw = it }, "Password", password = true)
                PrimaryButton("Delete permanently", Modifier.fillMaxWidth(), enabled = pw.isNotEmpty(), loading = busy, color = V.c.error) { vm.delete(pw) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Column { content() } }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(onClick = { onChange(!value) }, trailing = { Toggle(value, onChange) }) {
        Column(Modifier.weight(1f)) { Text(title, style = V.t.body, color = V.c.text); Text(sub, style = V.t.label, color = V.c.text2) }
    }
}

@Composable
private fun InfoRow(title: String, body: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, style = V.t.body, color = V.c.text)
        Spacer(Modifier.height(3.dp))
        Text(body, style = V.t.label, color = V.c.text2)
    }
}



/** Compact list timestamp: 14:05 · Yesterday · Fri · 20 Nov — keeps names from being squeezed. */
private fun listDate(t: Long): String {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    return when {
        sameDay(t, now) -> clock(t)
        sameDay(t, now - day) -> "Yesterday"
        now - t < 6 * day -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(t))
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}
