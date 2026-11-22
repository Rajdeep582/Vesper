package com.rajdeep.vesper.data

import com.rajdeep.vesper.core.crypto.RoomSignalStores
import com.rajdeep.vesper.core.crypto.SignalCipher
import com.rajdeep.vesper.core.crypto.SignalKeygen
import com.rajdeep.vesper.core.crypto.b64
import com.rajdeep.vesper.core.crypto.unb64
import com.rajdeep.vesper.core.db.MessageEntity
import com.rajdeep.vesper.core.db.MsgState
import com.rajdeep.vesper.core.db.Scope
import com.rajdeep.vesper.core.db.UserEntity
import com.rajdeep.vesper.core.db.VesperDatabase
import com.rajdeep.vesper.core.net.Api
import com.rajdeep.vesper.core.net.Realtime
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.media.VoicePlayer
import com.rajdeep.vesper.shared.Ack
import com.rajdeep.vesper.shared.AccountEnded
import com.rajdeep.vesper.shared.AckIds
import com.rajdeep.vesper.shared.DeviceList
import com.rajdeep.vesper.shared.DirectAck
import com.rajdeep.vesper.shared.DirectDelivered
import com.rajdeep.vesper.shared.DirectEnv
import com.rajdeep.vesper.shared.DirectEnvelopeDto
import com.rajdeep.vesper.shared.DirectPayload
import com.rajdeep.vesper.shared.DirectSend
import com.rajdeep.vesper.shared.HallDeleted
import com.rajdeep.vesper.shared.HallMessageDto
import com.rajdeep.vesper.shared.HallMsg
import com.rajdeep.vesper.shared.HallMuted
import com.rajdeep.vesper.shared.HallPage
import com.rajdeep.vesper.shared.HallSend
import com.rajdeep.vesper.shared.KeysResponse
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.MediaRef
import com.rajdeep.vesper.shared.MediaScope
import com.rajdeep.vesper.shared.MessageBody
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.Nack
import com.rajdeep.vesper.shared.Nacks
import com.rajdeep.vesper.shared.Ok
import com.rajdeep.vesper.shared.ReplyRef
import com.rajdeep.vesper.shared.OutEnvelope
import com.rajdeep.vesper.shared.PendingEnvelopes
import com.rajdeep.vesper.shared.PreKeyCounts
import com.rajdeep.vesper.shared.PreKeyUpload
import com.rajdeep.vesper.shared.UserSummary
import com.rajdeep.vesper.shared.VesperJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Events the UI reacts to with sound/haptics. */
sealed interface ChatEvent {
    data class Incoming(val scope: String, val peerId: String?) : ChatEvent
    data class SendFailed(val reason: String) : ChatEvent
}

/**
 * The messaging engine: optimistic local writes, an ordered outbox flushed over the socket, Hall sync by sequence,
 * Direct E2EE (libsignal, per device), and receipt of live frames. Countdown fields come only from server acks.
 */
@Singleton
class Messages @Inject constructor(
    private val db: VesperDatabase,
    private val api: Api,
    private val rt: Realtime,
    private val session: SessionManager,
    private val media: MediaRepository,
    private val notifier: Notifier,
    private val backup: ChatBackup,
    private val voicePlayer: VoicePlayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao get() = db.messages()
    private val outboxLock = Mutex()
    private val cryptoLock = Mutex()
    private val syncLock = Mutex()
    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ChatEvent> = _events
    private val _hallMutedUntil = MutableStateFlow<Long?>(null)
    val hallMutedUntil: StateFlow<Long?> = _hallMutedUntil
    private val _onlineCount = MutableStateFlow(1)
    val onlineCount: StateFlow<Int> = _onlineCount

    // ---- Live presence + typing (WhatsApp-style). Ephemeral: never stored. ----
    data class Typer(val id: String, val name: String, val to: String?, val at: Long)
    private val _typers = MutableStateFlow<Map<String, Typer>>(emptyMap())
    /** Who is typing in the Hall right now (newest first). */
    val hallTypers: Flow<List<Typer>> = _typers.map { m -> m.values.filter { it.to == null }.sortedByDescending { it.at } }.distinctUntilChanged()
    /** Peers typing to me in a private chat. */
    val directTypers: Flow<Set<String>> = _typers.map { m -> m.values.filter { it.to != null }.map { it.id }.toSet() }.distinctUntilChanged()
    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val onlineUsers: StateFlow<Set<String>> = _online
    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** When we saw a member go offline (this session only — nothing is stored). */
    val lastSeen: StateFlow<Map<String, Long>> = _lastSeen
    private val typingSentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Called on every keystroke; throttled to one frame per 3 s per chat, plus an explicit stop. */
    fun typing(toUserId: String?, on: Boolean) {
        val me = session.current()?.userId ?: return
        if (toUserId == me) return // Notes to self: nobody to tell
        val key = toUserId ?: "hall"
        val now = System.currentTimeMillis()
        if (on) {
            if (now - (typingSentAt[key] ?: 0L) < 3_000) return
            typingSentAt[key] = now
            rt.send(com.rajdeep.vesper.shared.Typing(toUserId, true))
        } else if (typingSentAt.remove(key) != null) {
            rt.send(com.rajdeep.vesper.shared.Typing(toUserId, false))
        }
    }

    private fun dropTyper(id: String, to: String?) { _typers.value = _typers.value - "$id|${if (to == null) "hall" else "d"}" }

    val stores = RoomSignalStores(db.signal()) { session.localIdentity() }
    val cipher = SignalCipher(stores) { SignalProtocolAddress(session.current()!!.userId, session.current()!!.deviceId) }

    init {
        stores.onIdentityChanged = { addr ->
            scope.launch { systemNotice(addr.name, "Security code changed for one of this person's devices. Verify in chat info if this is unexpected.") }
        }
        scope.launch { rt.connected.collect { onConnected() } }
        scope.launch { rt.frames.collect { runCatching { onFrame(it) } } }
        // Typing expires 6 s after the last refresh (sender refreshes every 3 s).
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val cut = System.currentTimeMillis() - 6_000
                val m = _typers.value
                if (m.values.any { it.at < cut }) _typers.value = m.filterValues { it.at >= cut }
            }
        }
        // Lost the socket: presence and typing are unknown, so show nothing rather than stale state.
        // Debounced: a quick background→foreground blip must not blank every green dot (that read as flicker).
        scope.launch {
            rt.link.collectLatest {
                if (it != com.rajdeep.vesper.core.net.Link.ONLINE) {
                    _typers.value = emptyMap(); typingSentAt.clear()
                    kotlinx.coroutines.delay(8_000)
                    _online.value = emptySet()
                }
            }
        }
    }

    fun hall(): Flow<List<MessageEntity>> = dao.hall()
    fun direct(peer: String): Flow<List<MessageEntity>> = dao.direct(peer)
    fun conversations() = dao.conversations()
    fun unreadDirect() = dao.unreadDirect()

    private suspend fun rememberContact(peer: String, at: Long) {
        val cur = db.kv().get("contact:$peer")?.toLongOrNull() ?: 0L
        if (at > cur) db.kv().put(com.rajdeep.vesper.core.db.KvEntity("contact:$peer", at.toString()))
    }

    /** Direct list: live conversations first, then everyone talked to before (their messages may have expired). */
    fun directList(): kotlinx.coroutines.flow.Flow<List<com.rajdeep.vesper.core.db.ConversationRow>> =
        kotlinx.coroutines.flow.combine(dao.conversations(), db.kv().contacts(), db.users().all()) { convs, contacts, users ->
            val have = convs.map { it.peerId }.toSet()
            val names = users.associate { it.id to it.username }
            val me = session.current()?.userId
            // Notes to self is always pinned first.
            val self = me?.let { id -> convs.firstOrNull { it.peerId == id }?.copy(peerName = "Notes to self", unread = 0)
                ?: com.rajdeep.vesper.core.db.ConversationRow(id, "Notes to self", "", null, Long.MAX_VALUE, false, 0, 0) }
            listOfNotNull(self) + convs.filter { it.peerId != me } + contacts.map { it.key.removePrefix("contact:") to (it.value.toLongOrNull() ?: 0L) }
                .filter { (id, _) -> id !in have && id != me }
                .sortedByDescending { it.second }
                .map { (id, at) -> com.rajdeep.vesper.core.db.ConversationRow(id, names[id], "", null, at, false, 0, 0) }
        }

    /** Pull the server's list of past private contacts (restores the Direct list after reinstall). */
    suspend fun syncContacts() {
        val page = api.get("/v1/direct/contacts", com.rajdeep.vesper.shared.UserPage.serializer())
        db.users().upsert(page.items.map { UserEntity(it.id, it.username, it.joinedAt) })
        page.items.forEachIndexed { i, u -> rememberContact(u.id, 1L + page.items.size - i) }
    }
    suspend fun markRead(peer: String) {
        val sids = dao.unreadServerIds(peer)
        dao.markRead(peer)
        if (sids.isNotEmpty()) runCatching { rt.send(com.rajdeep.vesper.shared.DirectRead(peer, sids)) }
    }

    private fun me() = session.current() ?: error("signed out")
    private val foreverRx = Regex("""^\*(.+)\*forever$""", RegexOption.DOT_MATCHES_ALL)
    private fun isForever(t: String?) = t != null && foreverRx.matches(t.trim())
    private fun newCmid() = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ sending

    suspend fun sendText(scope: String, peer: String?, text: String, reply: ReplyRef? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        val chunks = if (isForever(t)) listOf(t) else t.chunked(Limits.MAX_TEXT_CHARS)
        chunks.forEachIndexed { i, part ->
            insertOutgoing(scope, peer, MessageKind.TEXT, part, null, null, if (i == 0) reply else null)
        }
        flush()
    }

    suspend fun sendMedia(scope: String, peer: String?, kind: MessageKind, plain: ByteArray, mime: String, width: Int?, height: Int?, durationMs: Long?, waveform: List<Int>?, blurhash: String?, reply: ReplyRef? = null) {
        val staged = media.stage(plain)
        val ref = MediaRef("", staged.key.b64(), mime, staged.bytes, staged.sha256, width, height, durationMs, waveform, blurhash)
        insertOutgoing(scope, peer, kind, null, ref, staged.cipherFile.absolutePath, reply)
        flush()
    }

    /** Queues a prepared (compressed + encrypted, staged) file; parts upload in the background outbox. */
    suspend fun sendFile(scope: String, peer: String?, f: com.rajdeep.vesper.media.PreparedFile, reply: ReplyRef? = null) {
        val first = f.chunks.first()
        val ref = MediaRef("", f.key.b64(), f.mime, f.cipherBytes, first.sha256, name = f.name, origBytes = f.origBytes, codec = f.codec, chunks = f.chunks)
        insertOutgoing(scope, peer, MessageKind.FILE, null, ref, f.dir.absolutePath, reply)
        flush()
    }

    private suspend fun insertOutgoing(scope: String, peer: String?, kind: MessageKind, text: String?, ref: MediaRef?, staged: String?, reply: ReplyRef? = null) {
        val s = me()
        if (scope == Scope.DIRECT && peer != null) rememberContact(peer, session.now())
        dao.upsert(
            MessageEntity(
                localId = newCmid(), scope = scope, peerId = peer, senderId = s.userId, senderName = s.username,
                kind = kind.name, text = text, mediaJson = ref?.let { VesperJson.encodeToString(MediaRef.serializer(), it) },
                replyJson = reply?.let { VesperJson.encodeToString(ReplyRef.serializer(), it) },
                state = MsgState.PENDING, outgoing = true, createdAt = session.now(), stagedBlob = staged,
            ),
        )
    }

    suspend fun retry(localId: String) { dao.retry(localId); flush() }
    suspend fun discard(localId: String) {
        dao.get(localId)?.let { media.purgeStaged(it.stagedBlob) }
        dao.delete(listOf(localId))
    }

    /** Remove messages from this phone only (others keep theirs until expiry). */
    suspend fun deleteLocal(ids: List<String>) {
        if (ids.isEmpty()) return
        val rows = ids.mapNotNull { dao.get(it) }
        val mediaIds = rows.flatMap { r -> r.mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).blobIds() }.getOrNull() } ?: emptyList() }
        if (voicePlayer.state.value.id in mediaIds) {
            voicePlayer.stop()
        }
        dao.delete(ids)
        runCatching {
            rows.filter { it.scope == Scope.HALL }.mapNotNull { it.serverId }.forEach { db.kv().put(com.rajdeep.vesper.core.db.KvEntity("hid:$it", "1")) }
            rows.forEach { media.purgeStaged(it.stagedBlob) }
            media.purge(mediaIds)
            backup.forget(rows.filter { it.scope == Scope.DIRECT }.mapNotNull { it.serverId })
        }
    }

    /** Delete for everyone: Hall (own, or any for the admin) and own private messages. Removed here at once; others via frames. */
    suspend fun deleteForEveryone(ids: List<String>) {
        if (ids.isEmpty()) return
        val rows = ids.mapNotNull { dao.get(it) }
        val mediaIds = rows.flatMap { r -> r.mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).blobIds() }.getOrNull() } ?: emptyList() }
        if (voicePlayer.state.value.id in mediaIds) {
            voicePlayer.stop()
        }
        dao.delete(ids)
        runCatching {
            rows.forEach { media.purgeStaged(it.stagedBlob) }
            media.purge(mediaIds)
        }
        val hall = rows.filter { it.scope == Scope.HALL }.mapNotNull { it.serverId }
        // Pending (never reached the server) and Notes-to-self rows only need the local delete above.
        val direct = rows.filter { it.scope == Scope.DIRECT }.mapNotNull { it.serverId }.filterNot { it.startsWith("self:") }
        runCatching { backup.forget(direct) }
        if (hall.isNotEmpty()) {
            runCatching {
                api.post("/v1/hall/delete", com.rajdeep.vesper.shared.BackupIds(hall), com.rajdeep.vesper.shared.BackupIds.serializer(), com.rajdeep.vesper.shared.Ok.serializer())
            }
        }
        if (direct.isNotEmpty()) {
            runCatching {
                api.post("/v1/direct/unsend", com.rajdeep.vesper.shared.BackupIds(direct), com.rajdeep.vesper.shared.BackupIds.serializer(), com.rajdeep.vesper.shared.Ok.serializer())
            }
        }
    }

    suspend fun onlineCount(): Int = api.get("/v1/online", com.rajdeep.vesper.shared.OnlineCount.serializer()).count.also {
        _onlineCount.value = it
    }

    fun flushAsync() { scope.launch { flush() } }

    /** Sends pending messages in order. Stops at the first transport failure; the next (re)connect resumes. */
    suspend fun flush() = outboxLock.withLock {
        if (session.current() == null) return@withLock
        for (m in dao.pending()) {
            val ok = runCatching { sendOne(m) }.getOrElse { e ->
                if (e is com.rajdeep.vesper.core.net.ApiException && e.status in 400..499 && e.status != 401 && e.status != 408 && e.status != 429) {
                    dao.markFailed(m.localId, e.message); _events.tryEmit(ChatEvent.SendFailed(e.message)); true
                } else false
            }
            if (!ok) break
        }
    }

    /** Returns true when the message left the pending state (sent or permanently failed). */
    private suspend fun sendOne(m: MessageEntity): Boolean {
        val kind = MessageKind.valueOf(m.kind)
        var ref = m.mediaJson?.let { VesperJson.decodeFromString(MediaRef.serializer(), it) }
        // Notes to self: stays on this phone. No server round trip, no push, nobody else is touched.
        if (m.scope == Scope.DIRECT && m.peerId == me().userId) {
            if (ref != null && kind == MessageKind.FILE && ref.mediaId.isEmpty()) {
                val dir = m.stagedBlob?.let { java.io.File(it) }
                if (dir == null || !dir.exists()) { dao.markFailed(m.localId, "File missing"); return true }
                val chunks = ref.chunks.mapIndexed { i, c -> val id = "self-${m.localId}-$i"; media.adoptPart(java.io.File(dir, "$i.bin"), id); c.copy(id = id) }
                dir.deleteRecursively()
                dao.setMedia(m.localId, VesperJson.encodeToString(MediaRef.serializer(), ref.copy(mediaId = chunks.first().id, chunks = chunks)))
            } else if (ref != null && ref.mediaId.isEmpty()) {
                val f = m.stagedBlob?.let { java.io.File(it) }
                if (f == null || !f.exists()) { dao.markFailed(m.localId, "Media missing"); return true }
                val id = "self-${m.localId}"
                media.adoptLocal(f, id)
                dao.setMedia(m.localId, VesperJson.encodeToString(MediaRef.serializer(), ref.copy(mediaId = id)))
            }
            val now = session.now()
            val ttl = if (isForever(m.text)) com.rajdeep.vesper.shared.Keep.FOREVER_MS else Limits.ttlFor(kind)
            dao.markSent(m.localId, "self:${m.localId}", now, now + ttl, null, MsgState.READ)
            return true
        }
        if (ref != null && kind == MessageKind.FILE && ref.mediaId.isEmpty()) {
            // Upload part by part; progress is saved after each so a retry resumes instead of starting over.
            val dir = m.stagedBlob?.let { java.io.File(it) } ?: run { dao.markFailed(m.localId, "File missing"); return true }
            val sc = if (m.scope == Scope.HALL) MediaScope.HALL else MediaScope.PRIVATE
            var chunks = ref.chunks
            for (i in chunks.indices) {
                if (chunks[i].id.isNotEmpty()) continue
                val part = java.io.File(dir, "$i.bin")
                if (!part.exists()) { dao.markFailed(m.localId, "File missing"); return true }
                val id = media.upload(kind, sc, m.peerId, part, chunks[i].sha256)
                chunks = chunks.toMutableList().also { it[i] = it[i].copy(id = id) }
                dao.setMedia(m.localId, VesperJson.encodeToString(MediaRef.serializer(), ref.copy(chunks = chunks)))
            }
            ref = ref.copy(mediaId = chunks.first().id, chunks = chunks)
            dao.setMedia(m.localId, VesperJson.encodeToString(MediaRef.serializer(), ref))
            dir.deleteRecursively()
        }
        if (ref != null && ref.mediaId.isEmpty()) {
            val staged = m.stagedBlob ?: run { dao.markFailed(m.localId, "Media missing"); return true }
            val file = java.io.File(staged)
            if (!file.exists()) { dao.markFailed(m.localId, "Media missing"); return true }
            val sc = if (m.scope == Scope.HALL) MediaScope.HALL else MediaScope.PRIVATE
            val id = media.upload(kind, sc, m.peerId, file, ref.sha256)
            ref = ref.copy(mediaId = id)
            dao.setMedia(m.localId, VesperJson.encodeToString(MediaRef.serializer(), ref))
        }
        val reply = m.replyJson?.let { runCatching { VesperJson.decodeFromString(ReplyRef.serializer(), it) }.getOrNull() }
        val body = MessageBody(text = m.text, media = ref, reply = reply)
        return if (m.scope == Scope.HALL) sendHall(m, kind, body) else sendDirect(m, kind, body)
    }

    private suspend fun sendHall(m: MessageEntity, kind: MessageKind, body: MessageBody): Boolean {
        val reply = rt.request(m.localId, HallSend(m.localId, kind, body, keep = isForever(m.text))) ?: return false
        return handleReply(m, reply)
    }

    private suspend fun handleReply(m: MessageEntity, reply: com.rajdeep.vesper.shared.Frame): Boolean = when (reply) {
        is Ack -> {
            dao.markSent(m.localId, reply.id, reply.acceptedAt, reply.expiresAt, reply.seq)
            if (m.scope == Scope.DIRECT) dao.get(m.localId)?.let(backup::save)
            true
        }
        is Nack -> {
            if (reply.code == Nacks.RATE) false else {
                dao.markFailed(m.localId, reply.message ?: "Couldn't send")
                if (reply.code == Nacks.MUTED) _hallMutedUntil.value = Long.MAX_VALUE
                _events.tryEmit(ChatEvent.SendFailed(reply.message ?: "Couldn't send"))
                true
            }
        }
        else -> false
    }

    private suspend fun sendDirect(m: MessageEntity, kind: MessageKind, body: MessageBody): Boolean {
        val s = me()
        val peer = m.peerId ?: run { dao.markFailed(m.localId, "No recipient"); return true }
        val payload = VesperJson.encodeToString(DirectPayload.serializer(), DirectPayload(m.localId, kind, body, s.userId, peer, m.createdAt)).toByteArray()
        repeat(3) {
            val envelopes = cryptoLock.withLock { encryptForAll(peer, payload) }
            if (envelopes.none { it.toUserId == peer }) {
                dao.markFailed(m.localId, "This person has no active devices"); return true
            }
            val reply = rt.request(m.localId, DirectSend(m.localId, peer, kind, body.media?.mediaId, envelopes, keep = isForever(m.text), extraMediaIds = body.media?.chunks?.drop(1)?.map { it.id } ?: emptyList())) ?: return false
            if (reply is Nack && reply.code == Nacks.DEVICES) {
                deviceCache.remove(peer); deviceCache.remove(s.userId)
                // Device list changed: drop sessions for removed devices; missing ones get fresh bundles next round.
                reply.mismatch?.extra?.forEach { (u, ds) -> ds.forEach { cipher.deleteSession(u, it) } }
                return@repeat
            }
            return handleReply(m, reply)
        }
        return false
    }

    /** Device lists per user, cached briefly; a DEVICES nack from the server invalidates them. Saves 2 round trips per message. */
    private val deviceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Int>>>()
    private suspend fun devicesOf(uid: String): List<Int> {
        deviceCache[uid]?.let { (at, ids) -> if (System.currentTimeMillis() - at < 10 * 60_000) return ids }
        val ids = api.get("/v1/keys/$uid/devices", DeviceList.serializer()).deviceIds
        deviceCache[uid] = System.currentTimeMillis() to ids
        return ids
    }

    /** Encrypts [payload] for every device of [peer] and every other device of mine. */
    private suspend fun encryptForAll(peer: String, payload: ByteArray): List<OutEnvelope> = withContext(Dispatchers.IO) {
        val s = me()
        val (peerDevicesAll, myDevicesAll) = kotlinx.coroutines.coroutineScope {
            val a = async { devicesOf(peer) }; val b = async { devicesOf(s.userId) }
            a.await() to b.await()
        }
        val peerDevices = peerDevicesAll
        val myDevices = myDevicesAll.filter { it != s.deviceId }
        val targets = peerDevices.map { peer to it } + myDevices.map { s.userId to it }
        // Forget sessions for devices that no longer exist.
        listOf(peer to peerDevices, s.userId to myDevices).forEach { (u, live) ->
            cipher.knownDevices(u).filter { it !in live && !(u == s.userId && it == s.deviceId) }.forEach { cipher.deleteSession(u, it) }
        }
        val needBundles = targets.filter { (u, d) -> !cipher.hasSession(u, d) }.map { it.first }.toSet()
        needBundles.forEach { u ->
            val keys = api.get("/v1/keys/$u", KeysResponse.serializer())
            keys.devices.filter { !cipher.hasSession(u, it.deviceId) && (u != s.userId || it.deviceId != s.deviceId) }.forEach { b ->
                runCatching { cipher.processBundle(u, b) }.onFailure { android.util.Log.w("Vesper", "bundle $u/${b.deviceId} rejected: $it") }
            }
        }
        targets.filter { (u, d) -> cipher.hasSession(u, d) }.map { (u, d) ->
            val (type, bytes) = cipher.encrypt(u, d, payload)
            OutEnvelope(u, d, type, bytes.b64())
        }
    }

    // ------------------------------------------------------------------ receiving

    private suspend fun onConnected() {
        if (session.current() == null) return
        // Outgoing first (the user is waiting on those), catch-up syncs in parallel.
        kotlinx.coroutines.coroutineScope {
            launch { flush() }
            launch { runCatching { syncHall() } }
            launch { runCatching { syncDirect() }; runCatching { syncReceipts() } }
            launch { runCatching { syncContacts() } }
            launch { runCatching { onlineCount() } }
        }
        runCatching { maintainPreKeys() }
    }

    suspend fun syncAll() {
        runCatching { syncHall() }
        runCatching { syncDirect() }
        runCatching { syncReceipts() }
    }

    suspend fun syncHall() = syncLock.withLock {
        var after = dao.maxHallSeq() ?: 0L
        while (true) {
            val page = api.get("/v1/hall?afterSeq=$after&limit=200", HallPage.serializer())
            session.updateSkew(page.serverTime)
            page.items.forEach { storeHall(it, live = false) }
            if (page.items.size < 200) break
            after = page.items.last().seq
        }
    }

    /** Catch up ✓✓ / blue ✓✓ that happened while this phone was offline. */
    suspend fun syncReceipts() {
        val ids = dao.unseenOutgoing()
        if (ids.isEmpty()) return
        val r = api.post("/v1/direct/receipts", com.rajdeep.vesper.shared.ReceiptQuery(ids), com.rajdeep.vesper.shared.ReceiptQuery.serializer(), com.rajdeep.vesper.shared.ReceiptState.serializer())
        if (r.delivered.isNotEmpty()) dao.markDeliveredMany(r.delivered)
        if (r.seen.isNotEmpty()) dao.markSeen(r.seen)
    }

    suspend fun syncDirect(): Int {
        val pending = api.get("/v1/direct/pending", PendingEnvelopes.serializer())
        val done = pending.items.mapNotNull { e -> if (receiveEnvelope(e, live = false)) e.id else null }
        if (done.isNotEmpty()) api.post("/v1/direct/ack", AckIds(done), AckIds.serializer(), Ok.serializer())
        return done.size
    }

    private suspend fun onFrame(f: com.rajdeep.vesper.shared.Frame) {
        when (f) {
            is HallMsg -> { dropTyper(f.msg.senderId, null); storeHall(f.msg, live = true) }
            is com.rajdeep.vesper.shared.TypingAt -> {
                val key = "${f.fromUserId}|${f.toUserId?.let { "d" } ?: "hall"}"
                _typers.value = if (f.on) _typers.value + (key to Typer(f.fromUserId, f.fromName, f.toUserId, System.currentTimeMillis())) else _typers.value - key
            }
            is com.rajdeep.vesper.shared.Presence -> {
                _online.value = if (f.online) _online.value + f.userId else _online.value - f.userId
                if (!f.online) _lastSeen.value = _lastSeen.value + (f.userId to (f.lastActiveAt.takeIf { it > 0 }?.let { it - (session.current()?.clockSkewMs ?: 0L) } ?: System.currentTimeMillis()))
                if (!f.online) _typers.value = _typers.value.filterValues { it.id != f.userId }
            }
            is com.rajdeep.vesper.shared.PresenceSnapshot -> _online.value = f.online.toSet()
            is HallDeleted -> {
                val rows = dao.hallByServerIds(f.ids)
                media.purge(rows.flatMap { r -> r.mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).blobIds() }.getOrNull() } ?: emptyList() })
                dao.deleteHallByServerIds(f.ids)
            }
            is DirectEnv -> if (true.also { dropTyper(f.env.fromUserId, "d") } && receiveEnvelope(f.env, live = true)) rt.send(DirectAck(listOf(f.env.id)))
            is DirectDelivered -> dao.markDelivered(f.mid)
            is com.rajdeep.vesper.shared.DirectDeleted -> {
                val rows = dao.byServerIds(f.mids).filter { it.scope == Scope.DIRECT }
                media.purge(rows.flatMap { r -> r.mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).blobIds() }.getOrNull() } ?: emptyList() })
                dao.deleteDirectByServerIds(f.mids)
            }
            is com.rajdeep.vesper.shared.DirectSeen -> dao.markSeen(f.mids)
            is HallMuted -> _hallMutedUntil.value = f.until?.takeIf { it > 0 } ?: if (f.until == null) Long.MAX_VALUE else null
            is AccountEnded -> session.end(f.reason)
            else -> {}
        }
    }

    private suspend fun storeHall(m: HallMessageDto, live: Boolean) {
        val s = session.current() ?: return
        // "Delete for me" in the Hall: never re-download it on later syncs.
        if (db.kv().get("hid:${m.id}") != null) return
        db.users().upsert(listOf(UserEntity(m.senderId, m.senderName, 0)).filter { db.users().get(it.id) == null })
        val own = m.senderId == s.userId && m.cmid != null && dao.get(m.cmid!!) != null
        if (own) {
            dao.markSent(m.cmid!!, m.id, m.acceptedAt, m.expiresAt, m.seq)
            return
        }
        if (m.expiresAt <= session.now()) return
        val inserted = dao.insertIgnore(
            MessageEntity(
                localId = "h:${m.id}", serverId = m.id, scope = Scope.HALL, senderId = m.senderId, senderName = m.senderName,
                kind = m.kind.name, text = m.body.text, mediaJson = m.body.media?.let { VesperJson.encodeToString(MediaRef.serializer(), it) },
                replyJson = m.body.reply?.let { VesperJson.encodeToString(ReplyRef.serializer(), it) },
                state = MsgState.SENT, outgoing = m.senderId == s.userId, createdAt = m.acceptedAt, acceptedAt = m.acceptedAt,
                expiresAt = m.expiresAt, seq = m.seq,
            ),
        )
        if (inserted != -1L && m.senderId != s.userId) {
            if (live) _events.tryEmit(ChatEvent.Incoming(Scope.HALL, null))
            notifier.hall(m.senderName, m.kind, m.body.text, m.id, m.expiresAt)
        }
    }

    /** Decrypts and stores one envelope. Returns true when it can be acknowledged (stored or duplicate). */
    private suspend fun receiveEnvelope(e: DirectEnvelopeDto, live: Boolean): Boolean {
        val s = session.current() ?: return false
        if (dao.get("d:${e.mid}") != null) return true
        val plain = try {
            cryptoLock.withLock { withContext(Dispatchers.IO) { cipher.decrypt(e.fromUserId, e.fromDeviceId, e.type, e.body.unb64()) } }
        } catch (d: DuplicateMessageException) {
            return true
        } catch (t: Throwable) {
            // Undecryptable (e.g. session reset). Ack so it doesn't loop, and tell the user.
            systemNotice(e.fromUserId.takeIf { it != s.userId } ?: return true, "A private message couldn't be decrypted on this device.")
            return true
        }
        val p = runCatching { VesperJson.decodeFromString(DirectPayload.serializer(), String(plain)) }.getOrNull() ?: return true
        if (p.fromUserId != e.fromUserId) return true // sender binding check
        val outgoing = p.fromUserId == s.userId
        val peer = if (outgoing) p.toUserId else p.fromUserId
        if (!outgoing && p.toUserId != s.userId) return true
        if (e.expiresAt <= session.now()) return true
        ensureUser(peer)
        val name = if (outgoing) s.username else db.users().get(peer)?.username ?: "…"
        val inserted = dao.insertIgnore(
            MessageEntity(
                localId = "d:${e.mid}", serverId = e.mid, scope = Scope.DIRECT, peerId = peer, senderId = p.fromUserId, senderName = name,
                kind = p.kind.name, text = p.body.text, mediaJson = p.body.media?.let { VesperJson.encodeToString(MediaRef.serializer(), it) },
                replyJson = p.body.reply?.let { VesperJson.encodeToString(ReplyRef.serializer(), it) },
                state = MsgState.SENT, outgoing = outgoing, createdAt = e.acceptedAt, acceptedAt = e.acceptedAt, expiresAt = e.expiresAt,
                read = outgoing || notifier.isViewing(peer),
            ),
        )
        if (inserted != -1L) { dao.get("d:${e.mid}")?.let(backup::save); rememberContact(peer, e.acceptedAt) }
        if (inserted != -1L && !outgoing) {
            if (live) _events.tryEmit(ChatEvent.Incoming(Scope.DIRECT, peer))
            notifier.direct(peer, name, p.kind, p.body.text, e.mid, e.expiresAt)
        }
        return true
    }

    private suspend fun ensureUser(id: String) {
        if (db.users().get(id) != null) return
        runCatching { api.get("/v1/users/$id", UserSummary.serializer()) }.getOrNull()?.let {
            db.users().upsert(listOf(UserEntity(it.id, it.username, it.joinedAt)))
        }
    }

    private suspend fun systemNotice(peer: String, text: String) {
        val now = session.now()
        dao.upsert(
            MessageEntity(
                localId = "sys:${UUID.randomUUID()}", scope = Scope.DIRECT, peerId = peer, senderId = "system", senderName = "Vesper",
                kind = MessageKind.TEXT.name, text = text, state = MsgState.SYSTEM, outgoing = false, createdAt = now,
                acceptedAt = now, expiresAt = now + Limits.TEXT_TTL_MS,
            ),
        )
    }

    private suspend fun maintainPreKeys() {
        val counts = api.get("/v1/keys/count", PreKeyCounts.serializer())
        val needEc = counts.ec < Limits.PREKEY_LOW_WATER
        val needKy = counts.kyber < Limits.PREKEY_LOW_WATER
        if (!needEc && !needKy) return
        val up = cryptoLock.withLock {
            withContext(Dispatchers.IO) { SignalKeygen.replenish(stores, db.signal(), session.localIdentity().keyPair, needEc, needKy) }
        }
        api.post("/v1/keys/prekeys", up, PreKeyUpload.serializer(), Ok.serializer())
    }

    suspend fun safetyNumbers(peer: String): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val s = me()
        cipher.knownDevices(peer).sorted().mapNotNull { d -> cipher.safetyNumber(s.userId, peer, d)?.let { d to it } }
    }
}
