package com.rajdeep.vesper.ui.screens

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
class DirectListViewModel @Inject constructor(messages: Messages) : ViewModel() {
    val rows = messages.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun DirectListScreen(onOpen: (String) -> Unit, onFindPeople: () -> Unit, vm: DirectListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsState()
    Column(Modifier.fillMaxSize().background(V.c.bg).statusBarsPadding()) {
        ScreenHeader("Direct", "Private · end-to-end encrypted")
        when {
            rows == null -> SkeletonRows()
            rows!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "No private conversations yet",
                    "Open someone's profile from People or the Hall and choose Message privately.",
                    glyph = { Icon(Icons.Rounded.Lock, null, tint = V.accent, modifier = Modifier.size(40.dp)) },
                    action = { GhostButton("Find people", color = V.accent, onClick = onFindPeople) },
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows!!, key = { it.peerId }) { r ->
                    Box(Modifier.animateItem(placementSpec = Motion.offsetSpring).staggerIn(rows!!.indexOf(r), r.peerId)) { ConversationItem(r) { onOpen(r.peerId) } }
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(r: ConversationRow, onClick: () -> Unit) {
    ListRow(onClick = onClick, leading = { Monogram(r.peerName ?: "?", 48.dp) }) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.peerName ?: "…", style = V.t.subhead, color = V.c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (sameDay(r.lastAt, System.currentTimeMillis())) clock(r.lastAt) else dayLabel(r.lastAt), style = V.t.mono, color = if (r.unread > 0) V.accent else V.c.text3)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.lastOutgoing) {
                    Icon(
                        when (r.lastState) { MsgState.PENDING -> Icons.Rounded.Schedule; MsgState.DELIVERED -> Icons.Rounded.DoneAll; MsgState.FAILED -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.Done },
                        null, tint = when (r.lastState) { MsgState.DELIVERED -> V.accent; MsgState.FAILED -> V.c.error; else -> V.c.text3 }, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                val kind = MessageKind.valueOf(r.lastKind)
                if (kind != MessageKind.TEXT) {
                    Icon(if (kind == MessageKind.VOICE) Icons.Rounded.GraphicEq else Icons.Rounded.Image, null, tint = V.c.text2, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    when (kind) { MessageKind.TEXT -> r.lastText ?: ""; MessageKind.VOICE -> "Voice message"; MessageKind.IMAGE -> "Photo" },
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
class PeopleViewModel @Inject constructor(private val repo: PeopleRepository, private val session: SessionManager) : ViewModel() {
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<UserSummary>?>(null)
    val error = MutableStateFlow<String?>(null)
    val me get() = session.current()?.userId
    private var job: kotlinx.coroutines.Job? = null

    init { search("") }

    fun search(q: String) {
        query.value = q
        job?.cancel()
        job = viewModelScope.launch {
            delay(if (q.isEmpty()) 0 else 250)
            runCatching { repo.search(q) }.onSuccess { results.value = it.items; error.value = null }.onFailure { error.value = (it as? ApiException)?.message ?: "Couldn't load people" }
        }
    }
}

@Composable
fun PeopleScreen(onProfile: (String) -> Unit, vm: PeopleViewModel = hiltViewModel()) {
    val q by vm.query.collectAsState()
    val res by vm.results.collectAsState()
    val err by vm.error.collectAsState()
    LaunchedEffect(Unit) { vm.search(vm.query.value) }
    Column(Modifier.fillMaxSize().background(V.c.bg).statusBarsPadding()) {
        ScreenHeader("People", "Everyone on Vesper", accentDot = false)
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(V.c.s1).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = V.c.text2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search by username", style = V.t.body, color = V.c.text3)
                BasicTextField(q, vm::search, singleLine = true, textStyle = V.t.body.copy(color = V.c.text), cursorBrush = SolidColor(V.c.text), modifier = Modifier.fillMaxWidth())
            }
        }
        when {
            err != null && res == null -> EmptyState("Can't reach Vesper", err!!)
            res == null -> SkeletonRows()
            res!!.isEmpty() -> EmptyState("No one by that name", "Usernames use a–z, 0–9, _ and .")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(res!!, key = { _, it -> it.id }) { i, u ->
                    Box(Modifier.animateItem().staggerIn(i, u.id)) {
                        ListRow(onClick = { onProfile(u.id) }, leading = { Monogram(u.username, 42.dp) }, trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = V.c.text3) }) {
                            Column(Modifier.weight(1f)) {
                                Text(u.username + if (u.id == vm.me) "  · you" else "", style = V.t.subhead, color = V.c.text)
                                Text("Joined " + joined(u.joinedAt), style = V.t.label, color = V.c.text2)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val joinedFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
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
            Text(u?.username ?: " ", style = V.t.title, color = V.c.text)
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
            Text(u?.username ?: "…", style = V.t.heading, color = V.c.text)
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
class YouViewModel @Inject constructor(private val auth: AuthRepository, private val settings: Settings, private val session: SessionManager) : ViewModel() {
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
fun YouScreen(vm: YouViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsState()
    val devices by vm.devices.collectAsState()
    val msg by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val me = vm.me
    var sheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { if (msg != null) { delay(3500); vm.message.value = null } }
    Box(Modifier.fillMaxSize().background(V.c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Monogram(me?.username ?: "", 96.dp, ring = if (me?.isAdmin == true) V.c.console else V.c.hairline)
                Spacer(Modifier.height(14.dp))
                Text(me?.username ?: "", style = V.t.title, color = V.c.text)
                Text(
                    (if (me?.isAdmin == true) "Administrator · " else "") + "Member since " + joined(me?.joinedAt ?: 0),
                    style = V.t.label, color = if (me?.isAdmin == true) V.c.console else V.c.text2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                )
            }
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
                Hairline()
                ToggleRow("Reduce motion", "Simpler transitions", prefs.reduceMotion, vm::motion)
            }
            SectionLabel("Feedback")
            Group {
                ToggleRow("Sounds", "Soft cues for sending and receiving", prefs.sounds, vm::sounds)
                Hairline()
                ToggleRow("Haptics", "Light touch feedback", prefs.haptics, vm::haptics)
            }
            SectionLabel("Privacy")
            Group {
                InfoRow("Disappearing by design", "Text disappears 72 hours after the server accepts it; voice and photos after 24 hours — from the server and this phone.")
            }
            SectionLabel(if (me?.isAdmin == true) "Devices signed in as administrator" else "Devices")
            Group {
                if (me?.isAdmin == true) {
                    InfoRow("Shared credentials", "Anyone who signs in with the admin credentials becomes another device of this identity and receives new private messages sent to it from then on.")
                    Hairline()
                }
                devices.forEachIndexed { i, d ->
                    if (i > 0) Hairline()
                    ListRow(trailing = if (d.current) null else ({
                        Text("Sign out", style = V.t.label, color = V.c.error, modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { vm.signOutDevice(d.deviceId) }.padding(10.dp))
                    })) { Column(Modifier.weight(1f)) {
                        Text(d.label + if (d.current) "  · this phone" else "", style = V.t.subhead, color = V.c.text)
                        Text("Last seen " + clock(d.lastSeenAt) + " · " + dayLabel(d.lastSeenAt), style = V.t.label, color = V.c.text2)
                    } }
                }
                if (devices.count { !it.current } > 0) {
                    Hairline()
                    ListRow(onClick = { vm.signOutOthers() }) { Text("Sign out all other devices", style = V.t.body, color = V.c.error, modifier = Modifier.weight(1f)) }
                }
            }
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

