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
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.DisposableEffect
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
    /** Hall only: members with a live connection, synced with fast polling and instant event triggers. */
    val online = messages.onlineCount
    /** Names typing here right now: Hall members, or the peer in a private chat. */
    val typers: StateFlow<List<String>> = (if (peer == null) messages.hallTypers.map { l -> l.map { it.name } }
        else kotlinx.coroutines.flow.combine(messages.directTypers, people.observe(peer)) { set, u -> if (peer in set) listOf(u?.username ?: "…") else emptyList() })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val peerOnline: StateFlow<Boolean> = messages.onlineUsers.map { peer != null && peer != myId && peer in it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun typing(on: Boolean) = messages.typing(peer, on)
    fun refreshOnline() {
        if (peer == null) viewModelScope.launch { runCatching { messages.onlineCount() } }
    }
    init {
        if (peer == null) viewModelScope.launch {
            runCatching { messages.onlineCount() }
            while (true) {
                kotlinx.coroutines.delay(3_000)
                runCatching { messages.onlineCount() }
            }
        }
    }
    fun now() = session.now()

    init {
        recorder.onMaxReached = { finishRecording(true) }
        peer?.let { p -> viewModelScope.launch { runCatching { people.profile(p) } } }
    }

    fun onVisible(visible: Boolean) {
        if (scope == Scope.HALL) {
            notifier.viewingHall = visible
            if (visible) {
                notifier.cancelHall()
                refreshOnline()
            }
            return
        }
        if (peer == null) return
        notifier.viewingPeer = if (visible) peer else null
        if (visible) { notifier.cancelFor(peer); viewModelScope.launch { messages.markRead(peer) } }
    }

    val replyTo = MutableStateFlow<MessageEntity?>(null)
    fun setReply(m: MessageEntity?) { replyTo.value = m }
    fun clearReply() { replyTo.value = null }

    fun markRead() { peer?.let { viewModelScope.launch { messages.markRead(it) } } }
    fun sendText(t: String, reply: com.rajdeep.vesper.shared.ReplyRef? = null) = viewModelScope.launch {
        val rep = reply ?: replyTo.value?.toReplyRef()
        replyTo.value = null
        messages.sendText(scope, peer, t, rep)
        if (scope == Scope.HALL) refreshOnline()
    }
    fun retry(id: String) = viewModelScope.launch { messages.retry(id) }
    fun discard(id: String) = viewModelScope.launch { messages.discard(id) }

    fun sendImage(ctx: Context, uri: Uri, deleteAfter: File? = null, reply: com.rajdeep.vesper.shared.ReplyRef? = null) = viewModelScope.launch {
        val rep = reply ?: replyTo.value?.toReplyRef()
        replyTo.value = null
        runCatching {
            val img = ImageCompressor.prepare(ctx, uri)
            messages.sendMedia(scope, peer, MessageKind.IMAGE, img.bytes, img.mime, img.width, img.height, null, null, img.blurhash, rep)
        }.onFailure { error.value = "Couldn't prepare that photo" }
        deleteAfter?.delete()
    }

    fun startRecording(): Boolean = recorder.start(Limits.MAX_VOICE_MS)
    fun pauseRecording(): Boolean = recorder.pause()
    fun resumeRecording(): Boolean = recorder.resume()
    fun getPreviewBytes(): ByteArray? = recorder.previewBytes()
    fun getRecordedWaveform(): List<Int> = recorder.currentWaveform()

    fun finishRecording(send: Boolean, reply: com.rajdeep.vesper.shared.ReplyRef? = null) {
        if (!send) {
            android.util.Log.d("ChatViewModel", "finishRecording: send=false, cancelling recorder")
            recorder.cancel()
            return
        }
        val r = recorder.stop()
        if (r == null || r.durationMs < 1000L) {
            android.util.Log.w("ChatViewModel", "finishRecording: recorder.stop() returned null or < 1000ms duration")
            recorder.cancel()
            return
        }
        val rep = reply ?: replyTo.value?.toReplyRef()
        replyTo.value = null
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { r.file.readBytes().also { r.file.delete() } }
            android.util.Log.d("ChatViewModel", "finishRecording: sending ${bytes.size} bytes, duration=${r.durationMs}ms")
            messages.sendMedia(scope, peer, MessageKind.VOICE, bytes, "audio/ogg", null, null, r.durationMs, r.waveform, null, rep)
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
fun ChatScreen(
    onBack: (() -> Unit)?,
    onOpenInfo: (() -> Unit)?,
    onOpenProfile: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    vm: ChatViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val link by vm.link.collectAsState()
    val reach by vm.reach.collectAsState()
    val level by vm.level.collectAsState()
    val elapsed by vm.elapsed.collectAsState()
    val muted by vm.mutedUntil.collectAsState()
    val peerUser = vm.peerUser?.collectAsState()?.value
    val err by vm.error.collectAsState()
    val online by vm.online.collectAsState()
    val typers by vm.typers.collectAsState()
    val peerOnline by vm.peerOnline.collectAsState()
    DisposableEffect(Unit) { onDispose { vm.typing(false) } }
    val ctx = LocalContext.current
    val fb = V.fb
    val now = rememberNow { vm.now() }
    var menuFor by remember { mutableStateOf<MessageEntity?>(null) }
    var infoSheet by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf<List<String>?>(null) }
    val tabActive = com.rajdeep.vesper.ui.components.LocalTabActive.current
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    fun toggle(m: MessageEntity) { if (m.state == MsgState.SYSTEM) return; fb.tick(); if (m.localId in selected) selected.remove(m.localId) else selected.add(m.localId) }
    androidx.activity.compose.BackHandler(enabled = selecting && tabActive) { selected.clear() }
    LaunchedEffect(items) { val ids = items.map { it.localId }.toSet(); selected.retainAll { it in ids } }
    var viewer by remember { mutableStateOf<MediaRef?>(null) }
    var attachOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tabActive) { vm.onVisible(tabActive); if (!tabActive) vm.typing(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.onVisible(false) } }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ChatEvent.Incoming -> {
                    if (e.scope == vm.scope && (vm.peer == null || e.peerId == vm.peer)) fb.receive()
                    if (vm.scope == Scope.HALL) vm.refreshOnline()
                }
                is ChatEvent.SendFailed -> fb.error()
            }
        }
    }
    val lastId = items.lastOrNull()?.localId
    var activeLastId by remember(vm.scope, vm.peer) { mutableStateOf<String?>(null) }
    LaunchedEffect(lastId) {
        if (items.isNotEmpty()) {
            if (activeLastId == null) {
                // Initial load or navigation entry: display latest chat immediately without scrolling animation
                val atStart = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                val atEnd = !listState.canScrollForward
                if ((atStart || atEnd) && items.size > 1) {
                    listState.scrollToItem(items.size - 1)
                }
                activeLastId = lastId
            } else if (lastId != activeLastId) {
                // New message arrived while actively viewing the screen
                val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= items.size - 4 } ?: true
                if (nearBottom || items.last().outgoing) {
                    listState.animateScrollToItem(items.size - 1)
                }
                activeLastId = lastId
            }
            vm.markRead()
        }
    }

    val replyTo by vm.replyTo.collectAsState()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.sendImage(ctx, it) }
    }
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
                if (!sel) ChatHeader(vm.scope, (if (vm.peer != null && vm.peer == vm.myId) "Notes to self" else peerUser?.username), onBack, onOpenInfo, items.size, link, online, typers, peerOnline)
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
                        onReply = if (chosen.size == 1) ({ vm.setReply(chosen.first()); selected.clear() }) else null,
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
            val density = LocalDensity.current
            var composerHeightPx by remember { mutableIntStateOf(0) }
            val composerHeight = if (composerHeightPx > 0) with(density) { composerHeightPx.toDp() } else if (replyTo != null) 124.dp else 68.dp
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            // Chat area bounded below header/banner so overscroll never covers the heading.
            // Messages flow behind the transparent composer down to the bottom navigation bar with a smooth fade-away.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus(); keyboard?.hide() }) }) {
                if (items.isEmpty()) {
                    EmptyState(
                        title = if (vm.scope == Scope.HALL) "The Hall is quiet" else "A private space",
                        body = if (vm.scope == Scope.HALL) "Everyone on Vesper sees what's said here. Text stays 72 hours; voice and photos 24." else "End-to-end encrypted. Only the two of you can read, hear or see what's shared here.",
                        modifier = Modifier.align(Alignment.Center).padding(bottom = composerHeight / 2),
                        glyph = { if (vm.scope == Scope.HALL) DuskGlyph() else Icon(Icons.Rounded.Lock, null, tint = V.accent, modifier = Modifier.size(40.dp)) },
                    )
                }
                LazyColumn(
                    state = listState,
                    overscrollEffect = null,
                    modifier = Modifier.fillMaxSize().imePadding().then(Modifier.springOverscroll()),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = composerHeight + navBottom + 12.dp, top = 4.dp),
                ) {
                    itemsIndexed(items, key = { _, m -> m.localId }) { i, m ->
                        val prev = items.getOrNull(i - 1)
                        val next = items.getOrNull(i + 1)
                        fun grouped(a: MessageEntity?, b: MessageEntity?) = a != null && b != null && a.senderId == b.senderId && a.state != MsgState.SYSTEM && b.state != MsgState.SYSTEM && kotlin.math.abs(a.createdAt - b.createdAt) < 5 * 60_000
                        if (prev == null || !sameDay(prev.createdAt, m.createdAt)) DayDivider(m.createdAt)
                        Box(Modifier.animateItem(fadeInSpec = Motion.tw(Motion.M), placementSpec = androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold), fadeOutSpec = Motion.tw(Motion.S))) {
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
                                onReply = { vm.setReply(it) },
                                onJumpToMessage = { targetId ->
                                    val idx = items.indexOfFirst { it.serverId == targetId || it.localId == targetId }
                                    if (idx >= 0) {
                                        scope.launch { listState.animateScrollToItem(idx) }
                                    }
                                },
                            )
                        }
                    }
                    // Live "typing" bubble under the last message, like WhatsApp.
                    if (typers.isNotEmpty()) item(key = "__typing") {
                        Box(Modifier.animateItem(fadeInSpec = Motion.tw(Motion.M), placementSpec = androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold), fadeOutSpec = Motion.tw(Motion.S))) {
                            TypingBubble(if (vm.scope == Scope.HALL) typers else emptyList())
                        }
                    }
                }
                // Keep the typing bubble in view when the newest message is already visible.
                LaunchedEffect(typers.isNotEmpty()) {
                    if (typers.isNotEmpty()) {
                        val info = listState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        if (lastVisible >= info.totalItemsCount - 3) listState.animateScrollToItem(info.totalItemsCount - 1)
                    }
                }
                // Keyboard opening shrinks the list: if the newest message was in view just before, keep it pinned above the composer.
                LaunchedEffect(listState) {
                    var lastH = 0; var wasBottom = true
                    androidx.compose.runtime.snapshotFlow { listState.layoutInfo.viewportSize.height to !listState.canScrollForward }.collect { (h, bottom) ->
                        val shrunk = lastH != 0 && h < lastH
                        if (shrunk && wasBottom) listState.scrollBy((lastH - h).toFloat()) else wasBottom = bottom
                        lastH = h
                    }
                }

                // Smooth gradual fade-away just before the bottom navigation bar starts
                val fadeH = 84.dp + navBottom
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(fadeH)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Transparent,
                                    0.40f to Color.Transparent,
                                    0.68f to V.c.bg.copy(alpha = 0.18f),
                                    0.86f to V.c.bg.copy(alpha = 0.55f),
                                    1.00f to V.c.bg,
                                )
                            )
                        )
                )

                val showJump by remember { derivedStateOf { listState.canScrollForward && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) < items.size - 6 } }
                Reveal(
                    showJump,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .imePadding()
                        .padding(end = 16.dp, bottom = composerHeight + navBottom + 12.dp),
                    enter = scaleIn(Motion.bouncy()) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(V.c.s3).pressable { scope.launch { listState.animateScrollToItem(items.size - 1) } }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Jump to latest", tint = V.c.text)
                    }
                }

                val mutedNow = vm.scope == Scope.HALL && (muted ?: 0) > now
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .graphicsLayer { alpha = if (selecting) 0.35f else 1f }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { composerHeightPx = it.height }
                    ) {
                        Column {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = replyTo != null,
                                enter = androidx.compose.animation.expandVertically(Motion.spring()) + androidx.compose.animation.fadeIn(tween(Motion.S)),
                                exit = androidx.compose.animation.shrinkVertically(Motion.spring()) + androidx.compose.animation.fadeOut(tween(Motion.XS)),
                            ) {
                                replyTo?.let { target ->
                                    ReplyPreviewBar(
                                        reply = target,
                                        onDismiss = {
                                            fb.tick()
                                            vm.clearReply()
                                        },
                                    )
                                }
                            }
                            Composer(
                                enabled = !mutedNow,
                                disabledReason = if (mutedNow) "You're muted in the Hall" + (muted?.takeIf { it < Long.MAX_VALUE / 2 }?.let { " until " + clock(it) } ?: "") else null,
                                placeholder = if (vm.scope == Scope.HALL) "Message the Hall" else "Private message",
                                level = level, elapsedMs = elapsed,
                                onSend = { raw ->
                                    val trimmed = raw.trimEnd()
                                    if (trimmed.isEmpty()) return@Composer
                                    val t = trimmed.trim()
                                    val cmd = t.substringBefore(' ').lowercase()
                                    val arg = t.substringAfter(' ', "").trim()
                                    when (cmd) {
                                        "/docs" -> { vm.clearReply(); infoSheet = "docs" }
                                        "/about" -> { vm.clearReply(); infoSheet = "about" }
                                        "/clear" -> { vm.clearReply(); confirmClear = true }
                                        "/keep" -> if (arg.isNotEmpty()) vm.sendText("*$arg*forever")
                                        "/me" -> if (arg.isNotEmpty()) vm.sendText("_${vm.myName} ${arg}_")
                                        "/shrug" -> vm.sendText((if (arg.isNotEmpty()) "$arg " else "") + "¯\\_(ツ)_/¯")
                                        else -> vm.sendText(trimmed)
                                    }
                                },
                                onAttach = { attachOpen = true },
                                onTyping = { vm.typing(it) },
                                onRecordStart = {
                                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        if (!pendingRecord) { pendingRecord = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                                        false
                                    } else vm.startRecording()
                                },
                                onRecordPause = { vm.pauseRecording() },
                                onRecordResume = { vm.resumeRecording() },
                                onRecordFinish = { vm.finishRecording(it) },
                                getPreviewBytes = { vm.getPreviewBytes() },
                                getRecordedWaveform = { vm.getRecordedWaveform() },
                            )
                        }
                    }
                }
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
        // Snapshot rows when the sheet opens so a message changing state (upload finishing, receipt) can't flip the options mid-sheet.
        val rows = remember(ids) { items.filter { it.localId in ids } }
        val self = vm.peer != null && vm.peer == vm.myId
        // Everyone works for any mix of text / photo / voice: own messages (sent or still pending), or anything in the Hall for the admin.
        // Private chats: either side can remove a message for everyone. Hall: your own, or anything for the admin.
        fun eligible(r: MessageEntity) = r.state != MsgState.SYSTEM && (vm.scope == Scope.DIRECT || r.outgoing || r.senderId == vm.myId || vm.isAdmin)
        val canEveryone = rows.isNotEmpty() && rows.all(::eligible)
        val n = rows.size.coerceAtLeast(ids.size)
        val what = run {
            val k = rows.map { it.kind }.toSet()
            when {
                k.size > 1 -> if (n == 1) "message" else "messages"
                MessageKind.IMAGE.name in k -> if (n == 1) "photo" else "photos"
                MessageKind.VOICE.name in k -> if (n == 1) "voice message" else "voice messages"
                else -> if (n == 1) "message" else "messages"
            }
        }
        fun done(msg: String) { deleteAsk = null; selected.clear(); vm.error.value = msg }
        ModalBottomSheet(onDismissRequest = { deleteAsk = null }, containerColor = V.c.s1, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SheetTitle(
                if (n == 1) "Delete $what?" else "Delete $n $what?",
                when {
                    self -> "Notes to self never leave this phone — either option removes them for good."
                    canEveryone -> "Delete for everyone removes it permanently from the server and every phone."
                    else -> "Only your own Hall messages can be deleted for everyone."
                },
            )
            if (canEveryone) DeleteChoice(Icons.Rounded.DeleteForever, "Delete for everyone", "Gone from the server and all phones", V.c.error) {
                vm.deleteForEveryone(ids); done(if (n == 1) "Deleted for everyone" else "$n deleted for everyone")
            }
            DeleteChoice(Icons.Rounded.Delete, "Delete for me", "Removes from this phone only", V.c.text) {
                vm.deleteLocal(ids); done("Deleted for you")
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
            if (m.state != MsgState.SYSTEM) SheetAction("Delete…", danger = true) { menuFor = null; deleteAsk = listOf(m.localId) }
            if (vm.isAdmin && vm.scope == Scope.HALL && m.serverId != null && m.senderId != vm.myId) {
                SheetLabel("Moderation")
                run {
                    SheetAction("Mute author for 1 hour") { vm.adminMute(m, 60); menuFor = null }
                    SheetAction("Mute author for 24 hours") { vm.adminMute(m, 24 * 60); menuFor = null }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnlinePill(n: Int) {
    val green = V.c.success
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val pulse by inf.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "p")
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(green.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).graphicsLayer { alpha = if (n > 0) pulse else 0.4f }.clip(CircleShape).background(green))
        Spacer(Modifier.width(4.dp))
        androidx.compose.animation.AnimatedContent(n, transitionSpec = {
            (androidx.compose.animation.slideInVertically { if (targetState > initialState) it else -it } + fadeIn()) togetherWith (androidx.compose.animation.slideOutVertically { if (targetState > initialState) -it else it } + fadeOut())
        }, label = "online") { v ->
            Text("$v online", style = V.t.caption.copy(fontFamily = com.rajdeep.vesper.ui.theme.Mono, fontSize = 9.5.sp, letterSpacing = 0.2.sp), color = green)
        }
    }
}

@Composable
private fun ChatHeader(scope: String, peerName: String?, onBack: (() -> Unit)?, onInfo: (() -> Unit)?, count: Int, link: Link, online: Int = 0, typers: List<String> = emptyList(), peerOnline: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = V.c.text, onClick = onBack) else Spacer(Modifier.width(16.dp))
        if (scope == Scope.DIRECT) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).pressable(scaleTo = 0.98f) { onInfo?.invoke() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                com.rajdeep.vesper.ui.components.PresenceAvatar(peerName ?: "?", 38.dp, peerOnline)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(peerName ?: "…", style = V.t.subhead, color = V.c.text)
                    com.rajdeep.vesper.ui.components.LiveStatusLine(
                        typing = if (typers.isNotEmpty()) "typing" else null,
                        style = V.t.caption, accent = V.accent,
                        resting = {
                            androidx.compose.animation.Crossfade(peerOnline, animationSpec = androidx.compose.animation.core.tween(220), label = "on") { on ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (on) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(V.c.success))
                                        Spacer(Modifier.width(5.dp))
                                        Text("online", style = V.t.caption, color = V.c.success)
                                    } else {
                                        Icon(Icons.Rounded.Lock, null, tint = V.accent, modifier = Modifier.size(11.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (peerName == "Notes to self") "Only on this phone" else "End-to-end encrypted", style = V.t.caption, color = V.c.text2)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        } else {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.rajdeep.vesper.ui.components.HallMascot(34.dp, Modifier.offset(y = (-4).dp))
                    Spacer(Modifier.width(10.dp))
                    Text("The Hall", style = V.t.title, color = V.c.text)
                    Spacer(Modifier.width(10.dp))
                    OnlinePill(online)
                }
                // WhatsApp group style: "name is typing…" slides in under the title.
                com.rajdeep.vesper.ui.components.Reveal(
                    typers.isNotEmpty(),
                    enter = androidx.compose.animation.expandVertically(Motion.spring()) + fadeIn(Motion.tw(Motion.M)),
                    exit = androidx.compose.animation.shrinkVertically(Motion.tw(Motion.M)) + fadeOut(Motion.tw(Motion.S)),
                ) {
                    com.rajdeep.vesper.ui.components.LiveStatusLine(
                        typing = com.rajdeep.vesper.ui.components.typingLabel(typers) ?: "",
                        resting = {}, style = V.t.caption, accent = V.accent,
                        modifier = Modifier.padding(start = 44.dp, top = 0.dp),
                    )
                }
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
private fun SelectionBar(count: Int, canCopy: Boolean, onClose: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onReply: (() -> Unit)?, onDetails: (() -> Unit)?) {
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
        ) { n -> Text(n.toString(), style = V.t.title.copy(fontFamily = com.rajdeep.vesper.ui.theme.Mono), color = V.accent, maxLines = 1, softWrap = false) }
        Spacer(Modifier.weight(1f))
        if (onReply != null) BarIcon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Reply, "Reply", V.c.text, onReply)
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


/** Incoming-style bubble with bouncing dots; in the Hall it shows who's typing (avatar + small name). */
@Composable
private fun TypingBubble(names: List<String>) {
    val first = names.firstOrNull()
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        if (first != null) { Box(Modifier.width(36.dp)) { Monogram(first, 30.dp) } }
        Column {
            if (first != null) Text(
                com.rajdeep.vesper.ui.components.typingLabel(names)!!, style = V.t.caption, color = V.accent.copy(alpha = 0.9f),
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp), maxLines = 1,
            )
            Box(
                Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).background(V.c.s2)
                    .border(1.dp, V.c.hairline, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) { com.rajdeep.vesper.ui.components.TypingDots(V.c.text2, 6.dp) }
        }
    }
}
