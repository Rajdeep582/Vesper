package com.rajdeep.vesper.ui.chat

import com.rajdeep.vesper.ui.components.springOverscroll
import com.rajdeep.vesper.ui.components.Reveal
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rajdeep.vesper.core.db.MessageEntity
import com.rajdeep.vesper.core.db.MsgState
import com.rajdeep.vesper.core.db.Scope
import com.rajdeep.vesper.core.net.Api
import com.rajdeep.vesper.core.net.Link
import com.rajdeep.vesper.core.net.Reach
import com.rajdeep.vesper.core.net.Realtime
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.data.AdminRepository
import com.rajdeep.vesper.data.ChatEvent
import com.rajdeep.vesper.data.Messages
import com.rajdeep.vesper.data.Notifier
import com.rajdeep.vesper.data.PeopleRepository
import com.rajdeep.vesper.media.ImageCompressor
import com.rajdeep.vesper.media.VoiceRecorder
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.MediaRef
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.ui.components.Banner
import com.rajdeep.vesper.ui.components.DuskGlyph
import com.rajdeep.vesper.ui.components.EmptyState
import com.rajdeep.vesper.ui.components.IconAction
import com.rajdeep.vesper.ui.components.ListRow
import com.rajdeep.vesper.ui.components.Monogram
import com.rajdeep.vesper.ui.components.PulseDots
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    handle: SavedStateHandle,
    val messages: Messages,
    private val session: SessionManager,
    private val recorder: VoiceRecorder,
    private val admin: AdminRepository,
    private val people: PeopleRepository,
    private val notifier: Notifier,
    rt: Realtime,
    api: Api,
) : ViewModel() {
    val peer: String? = handle.get<String>("peer")
    val scope = if (peer == null) Scope.HALL else Scope.DIRECT
    val items: StateFlow<List<MessageEntity>> =
        (if (peer == null) messages.hall() else messages.direct(peer)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val link = rt.link
    val reach = api.reach
    val level = recorder.level
    val elapsed = recorder.elapsed
    val mutedUntil = messages.hallMutedUntil
    val events = messages.events
    val isAdmin get() = session.current()?.isAdmin == true
    val myId get() = session.current()?.userId
    val myName get() = session.current()?.username ?: "someone"
    val peerUser = peer?.let { people.observe(it).stateIn(viewModelScope, SharingStarted.Eagerly, null) }
    val error = MutableStateFlow<String?>(null)
    fun now() = session.now()

    init {
        recorder.onMaxReached = { finishRecording(true) }
        peer?.let { p -> viewModelScope.launch { runCatching { people.profile(p) } } }
    }

    fun onVisible(visible: Boolean) {
        if (peer == null) return
        notifier.viewingPeer = if (visible) peer else null
        if (visible) { notifier.cancelFor(peer); viewModelScope.launch { messages.markRead(peer) } }
    }

    fun markRead() { peer?.let { viewModelScope.launch { messages.markRead(it) } } }
    fun sendText(t: String) = viewModelScope.launch { messages.sendText(scope, peer, t) }
    fun retry(id: String) = viewModelScope.launch { messages.retry(id) }
    fun discard(id: String) = viewModelScope.launch { messages.discard(id) }

    fun sendImage(ctx: Context, uri: Uri, deleteAfter: File? = null) = viewModelScope.launch {
        runCatching {
            val img = ImageCompressor.prepare(ctx, uri)
            messages.sendMedia(scope, peer, MessageKind.IMAGE, img.bytes, img.mime, img.width, img.height, null, null, img.blurhash)
        }.onFailure { error.value = "Couldn't prepare that photo" }
        deleteAfter?.delete()
    }

    fun startRecording(): Boolean = recorder.start(Limits.MAX_VOICE_MS)

    fun finishRecording(send: Boolean) {
        if (!send) { recorder.cancel(); return }
        val r = recorder.stop() ?: return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { r.file.readBytes().also { r.file.delete() } }
            messages.sendMedia(scope, peer, MessageKind.VOICE, bytes, "audio/ogg", null, null, r.durationMs, r.waveform, null)
        }
    }

    fun deleteLocal(ids: List<String>) = viewModelScope.launch { messages.deleteLocal(ids) }
    fun deleteForEveryone(ids: List<String>) = viewModelScope.launch {
        runCatching { messages.deleteForEveryone(ids) }.onSuccess { error.value = if (ids.size == 1) "Deleted for everyone" else "Deleted ${ids.size} messages for everyone" }
            .onFailure { error.value = (it as? com.rajdeep.vesper.core.net.ApiException)?.message ?: "Couldn't delete — check your connection" }
    }
    fun adminDelete(m: MessageEntity) = viewModelScope.launch { m.serverId?.let { runCatching { admin.deleteHall(it) }.onFailure { e -> error.value = e.message } } }
    fun adminMute(m: MessageEntity, minutes: Long?) = viewModelScope.launch { runCatching { admin.mute(m.senderId, minutes) }.onFailure { e -> error.value = e.message } }

    override fun onCleared() { recorder.cancel(); onVisible(false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: (() -> Unit)?, onOpenInfo: (() -> Unit)?, onOpenProfile: (String) -> Unit, vm: ChatViewModel = hiltViewModel()) {
    val items by vm.items.collectAsState()
    val link by vm.link.collectAsState()
    val reach by vm.reach.collectAsState()
    val level by vm.level.collectAsState()
    val elapsed by vm.elapsed.collectAsState()
    val muted by vm.mutedUntil.collectAsState()
    val peerUser = vm.peerUser?.collectAsState()?.value
    val err by vm.error.collectAsState()
    val ctx = LocalContext.current
    val fb = V.fb
    val listState = rememberLazyListState()
    val now = rememberNow { vm.now() }
    var menuFor by remember { mutableStateOf<MessageEntity?>(null) }
    var infoSheet by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf<List<String>?>(null) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    fun toggle(m: MessageEntity) { if (m.state == MsgState.SYSTEM) return; fb.tick(); if (m.localId in selected) selected.remove(m.localId) else selected.add(m.localId) }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.clear() }
    LaunchedEffect(items) { val ids = items.map { it.localId }.toSet(); selected.retainAll { it in ids } }
    var viewer by remember { mutableStateOf<MediaRef?>(null) }
    var attachOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.DisposableEffect(Unit) { vm.onVisible(true); onDispose { vm.onVisible(false) } }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ChatEvent.Incoming -> if (e.scope == vm.scope && (vm.peer == null || e.peerId == vm.peer)) fb.receive()
                is ChatEvent.SendFailed -> fb.error()
            }
        }
    }
    val lastId = items.lastOrNull()?.localId
    LaunchedEffect(lastId) {
        if (items.isNotEmpty()) {
            val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= items.size - 4 } ?: true
            if (nearBottom || items.last().outgoing) listState.animateScrollToItem(items.size - 1)
            vm.markRead()
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.sendImage(ctx, it) } }
    var captureFile by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = captureFile
        if (ok && f != null) vm.sendImage(ctx, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f), deleteAfter = f) else f?.delete()
        captureFile = null
    }
    var pendingRecord by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pendingRecord = false }

    Box(Modifier.fillMaxSize().background(V.c.bg)) {
        com.rajdeep.vesper.ui.components.ChatBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            androidx.compose.animation.AnimatedContent(
                selecting,
                transitionSpec = {
                    (fadeIn(Motion.tw(Motion.M)) + androidx.compose.animation.slideInVertically(Motion.spring()) { -it / 2 }) togetherWith
                        (fadeOut(Motion.tw(Motion.S)) + androidx.compose.animation.slideOutVertically(Motion.tw(Motion.S)) { -it / 3 })
                },
                label = "header",
            ) { sel ->
                if (!sel) ChatHeader(vm.scope, peerUser?.username, onBack, onOpenInfo, items.size, link)
                else {
                    val chosen = items.filter { it.localId in selected }
                    val clipboard = LocalClipboard.current
                    SelectionBar(
                        count = selected.size,
                        canCopy = chosen.any { it.kind == MessageKind.TEXT.name && it.text != null },
                        onClose = { selected.clear() },
                        onCopy = {
                            val text = chosen.filter { it.kind == MessageKind.TEXT.name && it.text != null }.joinToString("\n") { it.text!! }
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("messages", text))) }
                            vm.error.value = if (chosen.size == 1) "Copied" else "Copied ${chosen.size} messages"
                            selected.clear()
                        },
                        onDelete = { fb.press(); deleteAsk = selected.toList() },
                        onDetails = if (chosen.size == 1) ({ menuFor = chosen.first(); selected.clear() }) else null,
                    )
                }
            }
            Banner(link != Link.ONLINE || reach == Reach.WAKING, when {
                reach == Reach.WAKING -> "Waking the server…"
                link == Link.CONNECTING -> "Connecting…"
                else -> "Offline — messages will send when you're back"
            })
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            // Tap any free space in the conversation to put the keyboard away.
            Box(Modifier.weight(1f).pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus(); keyboard?.hide() }) }) {
                if (items.isEmpty()) {
                    EmptyState(
                        title = if (vm.scope == Scope.HALL) "The Hall is quiet" else "A private space",
                        body = if (vm.scope == Scope.HALL) "Everyone on Vesper sees what's said here. Text stays 72 hours; voice and photos 24." else "End-to-end encrypted. Only the two of you can read, hear or see what's shared here.",
                        modifier = Modifier.align(Alignment.Center),
                        glyph = { if (vm.scope == Scope.HALL) DuskGlyph() else Icon(Icons.Rounded.Lock, null, tint = V.accent, modifier = Modifier.size(40.dp)) },
                    )
                }
                LazyColumn(state = listState, overscrollEffect = null, modifier = Modifier.fillMaxSize().then(Modifier.springOverscroll()), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp, top = 4.dp)) {
                    itemsIndexed(items, key = { _, m -> m.localId }) { i, m ->
                        val prev = items.getOrNull(i - 1)
                        val next = items.getOrNull(i + 1)
                        fun grouped(a: MessageEntity?, b: MessageEntity?) = a != null && b != null && a.senderId == b.senderId && a.state != MsgState.SYSTEM && b.state != MsgState.SYSTEM && kotlin.math.abs(a.createdAt - b.createdAt) < 5 * 60_000
                        if (prev == null || !sameDay(prev.createdAt, m.createdAt)) DayDivider(m.createdAt)
                        Box(Modifier.animateItem(fadeInSpec = Motion.tw(Motion.M), placementSpec = Motion.offsetSpring, fadeOutSpec = Motion.tw(Motion.L))) {
                            MessageBubble(
                                m, now,
                                showAvatar = vm.scope == Scope.HALL,
                                showName = vm.scope == Scope.HALL,
                                groupedWithPrev = grouped(prev, m) && sameDay(prev!!.createdAt, m.createdAt),
                                groupedWithNext = grouped(m, next),
                                onLongPress = { fb.longPress(); if (m.state != MsgState.SYSTEM) { if (m.localId !in selected) selected.add(m.localId) } },
                                onImage = { if (selecting) toggle(m) else viewer = it },
                                onRetry = { vm.retry(m.localId) },
                                selecting = selecting,
                                selected = m.localId in selected,
                                onToggle = { toggle(m) },
                            )
                        }
                    }
                }
                val showJump by remember { derivedStateOf { listState.canScrollForward && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) < items.size - 6 } }
                Reveal(showJump, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = scaleIn(Motion.bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(V.c.s3).pressable { scope.launch { listState.animateScrollToItem(items.size - 1) } }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Jump to latest", tint = V.c.text)
                    }
                }
            }
            val mutedNow = vm.scope == Scope.HALL && (muted ?: 0) > now
            Box(Modifier.navigationBarsPadding().imePadding().graphicsLayer { alpha = if (selecting) 0.35f else 1f }) {
                Composer(
                    enabled = !mutedNow,
                    disabledReason = if (mutedNow) "You're muted in the Hall" + (muted?.takeIf { it < Long.MAX_VALUE / 2 }?.let { " until " + clock(it) } ?: "") else null,
                    placeholder = if (vm.scope == Scope.HALL) "Message the Hall" else "Private message",
                    level = level, elapsedMs = elapsed,
                    onSend = { raw ->
                        val t = raw.trim()
                        val cmd = t.substringBefore(' ').lowercase()
                        val arg = t.substringAfter(' ', "").trim()
                        when (cmd) {
                            "/docs" -> infoSheet = "docs"
                            "/about" -> infoSheet = "about"
                            "/clear" -> confirmClear = true
                            "/keep" -> if (arg.isNotEmpty()) vm.sendText("*$arg*forever")
                            "/me" -> if (arg.isNotEmpty()) vm.sendText("_${vm.myName} ${arg}_")
                            "/shrug" -> vm.sendText((if (arg.isNotEmpty()) "$arg " else "") + "¯\\_(ツ)_/¯")
                            else -> vm.sendText(raw)
                        }
                    },
                    onAttach = { attachOpen = true },
                    onRecordStart = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            if (!pendingRecord) { pendingRecord = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                            false
                        } else vm.startRecording()
                    },
                    onRecordFinish = { vm.finishRecording(it) },
                )
            }
        }

        // Full-screen viewer
        Reveal(viewer != null, enter = fadeIn(Motion.tw(Motion.M)) + scaleIn(Motion.spring(), initialScale = 0.92f), exit = fadeOut(Motion.tw(Motion.S)) + scaleOut(targetScale = 0.94f)) {
            viewer?.let { ImageViewer(it) { viewer = null } }
        }
        err?.let { msg -> LaunchedEffect(msg) { kotlinx.coroutines.delay(2800); vm.error.value = null } }
        Reveal(err != null, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp), enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
            Text(err ?: "", style = V.t.label, color = V.c.text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(V.c.s3).padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }

    if (attachOpen) {
        ModalBottomSheet(onDismissRequest = { attachOpen = false }, containerColor = V.c.s1, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SheetTitle("Share a photo", "Photos are compressed, stripped of location data and encrypted before upload. They disappear after 24 hours.")
            SheetAction("Choose from library") {
                attachOpen = false
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            SheetAction("Take a photo") {
                attachOpen = false
                val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
                val f = File(dir, UUID.randomUUID().toString() + ".jpg")
                captureFile = f
                takePhoto.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    infoSheet?.let { which ->
        ModalBottomSheet(onDismissRequest = { infoSheet = null }, containerColor = V.c.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 32.dp)) {
                if (which == "docs") com.rajdeep.vesper.ui.components.DocsContent() else com.rajdeep.vesper.ui.components.AboutContent()
            }
        }
    }
    deleteAsk?.let { ids ->
        val rows = items.filter { it.localId in ids }
        // Everyone: all chosen must be mine and on the server — or, in the Hall, the admin may remove anything.
        val canEveryone = rows.isNotEmpty() && rows.all { it.serverId != null && (it.outgoing || (vm.isAdmin && vm.scope == Scope.HALL)) }
        ModalBottomSheet(onDismissRequest = { deleteAsk = null }, containerColor = V.c.s1) {
            SheetTitle(
                if (ids.size == 1) "Delete message?" else "Delete ${ids.size} messages?",
                if (canEveryone) "Delete for everyone removes it from the server and every phone." else "Only your own sent messages can be deleted for everyone.",
            )
            DeleteChoice(Icons.Rounded.Delete, "Delete for me", "Removes from this phone only", V.c.text) {
                vm.deleteLocal(ids); vm.error.value = "Deleted for you"; deleteAsk = null; selected.clear()
            }
            if (canEveryone) DeleteChoice(Icons.Rounded.DeleteForever, "Delete for everyone", "Gone from the server and all phones", V.c.error) {
                vm.deleteForEveryone(ids); deleteAsk = null; selected.clear()
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    if (confirmClear) {
        ModalBottomSheet(onDismissRequest = { confirmClear = false }, containerColor = V.c.s1) {
            SheetTitle("Clear this conversation?", "Removes every message here from this phone. Others keep theirs until they expire.")
            SheetAction("Clear from this phone", danger = true) {
                vm.deleteLocal(items.map { it.localId }); confirmClear = false; vm.error.value = "Conversation cleared"
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    menuFor?.let { m ->
        val clipboard = LocalClipboard.current
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = V.c.s1) {
            val kind = MessageKind.valueOf(m.kind)
            SheetTitle(
                if (m.outgoing) "Your message" else m.senderName,
                buildString {
                    append(if (m.acceptedAt != null) "Sent " + clockFull(m.acceptedAt) else "Waiting to send — the countdown starts once the server accepts it")
                    m.expiresAt?.let { append("\nDisappears " + clockFull(it)) }
                },
            )
            if (kind == MessageKind.TEXT && m.text != null) SheetAction("Copy text") {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.text))) }; menuFor = null
            }
            if (!m.outgoing && vm.scope == Scope.HALL && m.senderId != vm.myId) SheetAction("View profile") { menuFor = null; onOpenProfile(m.senderId) }
            if (m.state == MsgState.FAILED) {
                SheetAction("Retry") { vm.retry(m.localId); menuFor = null }
                SheetAction("Discard", danger = true) { vm.discard(m.localId); menuFor = null }
            }
            if (vm.isAdmin && vm.scope == Scope.HALL && m.serverId != null) {
                SheetLabel("Moderation")
                SheetAction("Delete for everyone", danger = true) { vm.adminDelete(m); menuFor = null }
                if (m.senderId != vm.myId) {
                    SheetAction("Mute author for 1 hour") { vm.adminMute(m, 60); menuFor = null }
                    SheetAction("Mute author for 24 hours") { vm.adminMute(m, 24 * 60); menuFor = null }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChatHeader(scope: String, peerName: String?, onBack: (() -> Unit)?, onInfo: (() -> Unit)?, count: Int, link: Link) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = V.c.text, onClick = onBack) else Spacer(Modifier.width(16.dp))
        if (scope == Scope.DIRECT) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).pressable(scaleTo = 0.98f) { onInfo?.invoke() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Monogram(peerName ?: "?", 38.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(peerName ?: "…", style = V.t.subhead, color = V.c.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = V.accent, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("End-to-end encrypted", style = V.t.caption, color = V.c.text2)
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.rajdeep.vesper.ui.components.HallMascot(30.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("The Hall", style = V.t.title, color = V.c.text)
                }
                Text(
                    if (link == Link.ONLINE) "Live · everyone on Vesper" else "Public space",
                    style = V.t.label, color = V.c.text2, modifier = Modifier.padding(start = 17.dp),
                )
            }
        }
    }
}

@Composable
private fun DayDivider(at: Long) {
    Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(dayLabel(at), style = V.t.caption, color = V.c.text3)
    }
}

@Composable
fun SheetTitle(title: String, body: String? = null) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = V.t.heading, color = V.c.text)
        body?.let { Spacer(Modifier.height(6.dp)); Text(it, style = V.t.bodySmall, color = V.c.text2) }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun SheetLabel(text: String) = Text(text.uppercase(), style = V.t.caption, color = V.c.text3, modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp))

@Composable
fun SheetAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    ListRow(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(text, style = V.t.body, color = if (danger) V.c.error else V.c.text)
    }
}

@Composable
private fun ImageViewer(ref: MediaRef, onClose: () -> Unit) {
    val host = LocalMediaHost.current
    val image by produceState<ImageBitmap?>(null, ref.mediaId) {
        value = runCatching { val b = host.open(ref); withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }.getOrNull()
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val tState = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 5f); offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero }
    val alpha = (1f - kotlin.math.abs(drag.value) / 900f).coerceIn(0.3f, 1f)
    Box(
        Modifier.fillMaxSize().background(V.c.scrim.copy(alpha = 0.96f * alpha))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (kotlin.math.abs(drag.value) > 220) onClose() else drag.animateTo(0f, Motion.spring()) } },
                    onVerticalDrag = { _, d -> if (scale <= 1f) scope.launch { drag.snapTo(drag.value + d) } },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; if (scale == 1f) offset = androidx.compose.ui.geometry.Offset.Zero }) },
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                it, "Photo",
                Modifier.fillMaxWidth().transformable(tState).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y + drag.value
                },
                contentScale = ContentScale.Fit,
            )
        } ?: PulseDots(V.c.text)
        IconAction(Icons.Rounded.Close, "Close", tint = V.c.text, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp), onClick = onClose)
    }
}

private val dayFmt = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault())
private val fullFmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
fun clockFull(ms: Long): String = fullFmt.format(java.util.Date(ms))
fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}
fun dayLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ms, now) -> "Today"
        sameDay(ms, now - 86_400_000) -> "Yesterday"
        else -> dayFmt.format(java.util.Date(ms))
    }
}


@Composable
private fun DeleteChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(V.c.s2)
            .pressable(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = V.t.subhead, color = tint)
            Text(sub, style = V.t.label, color = V.c.text2)
        }
    }
}

@Composable
private fun SelectionBar(count: Int, canCopy: Boolean, onClose: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onDetails: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(56.dp).clip(RoundedCornerShape(28.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(V.accent.copy(alpha = 0.16f), V.c.s2)))
            .border(1.dp, V.accent.copy(alpha = 0.30f), RoundedCornerShape(28.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(Icons.Rounded.Close, "Cancel selection", V.c.text, onClose)
        androidx.compose.animation.AnimatedContent(
            count,
            transitionSpec = {
                val up = targetState > initialState
                (androidx.compose.animation.slideInVertically(Motion.spring()) { if (up) it else -it } + fadeIn()) togetherWith
                    (androidx.compose.animation.slideOutVertically(Motion.spring()) { if (up) -it else it } + fadeOut())
            },
            label = "count", modifier = Modifier.padding(start = 4.dp),
        ) { n -> Text(n.toString(), style = V.t.subhead.copy(fontFamily = com.rajdeep.vesper.ui.theme.Mono), color = V.accent) }
        Text(" selected", style = V.t.subhead, color = V.c.text, modifier = Modifier.weight(1f))
        if (onDetails != null) BarIcon(Icons.Rounded.Info, "Details", V.c.text2, onDetails)
        if (canCopy) BarIcon(Icons.Rounded.ContentCopy, "Copy", V.c.text, onCopy)
        BarIcon(Icons.Rounded.Delete, "Delete", V.c.error, onDelete)
    }
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).pressable(scaleTo = 0.88f, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
