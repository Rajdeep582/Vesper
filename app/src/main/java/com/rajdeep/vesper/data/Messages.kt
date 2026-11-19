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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    val stores = RoomSignalStores(db.signal()) { session.localIdentity() }
    val cipher = SignalCipher(stores) { SignalProtocolAddress(session.current()!!.userId, session.current()!!.deviceId) }

    init {
        stores.onIdentityChanged = { addr ->
            scope.launch { systemNotice(addr.name, "Security code changed for one of this person's devices. Verify in chat info if this is unexpected.") }
        }
        scope.launch { rt.connected.collect { onConnected() } }
        scope.launch { rt.frames.collect { runCatching { onFrame(it) } } }
    }

    fun hall(): Flow<List<MessageEntity>> = dao.hall()
    fun direct(peer: String): Flow<List<MessageEntity>> = dao.direct(peer)
    fun conversations() = dao.conversations()
    fun unreadDirect() = dao.unreadDirect()
    suspend fun markRead(peer: String) = dao.markRead(peer)

    private fun me() = session.current() ?: error("signed out")
    private fun newCmid() = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ sending

    suspend fun sendText(scope: String, peer: String?, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        t.chunked(Limits.MAX_TEXT_CHARS).forEach { part ->
            insertOutgoing(scope, peer, MessageKind.TEXT, part, null, null)
        }
        flush()
    }

    suspend fun sendMedia(scope: String, peer: String?, kind: MessageKind, plain: ByteArray, mime: String, width: Int?, height: Int?, durationMs: Long?, waveform: List<Int>?, blurhash: String?) {
        val staged = media.stage(plain)
        val ref = MediaRef("", staged.key.b64(), mime, staged.bytes, staged.sha256, width, height, durationMs, waveform, blurhash)
        insertOutgoing(scope, peer, kind, null, ref, staged.cipherFile.absolutePath)
        flush()
    }

    private suspend fun insertOutgoing(scope: String, peer: String?, kind: MessageKind, text: String?, ref: MediaRef?, staged: String?) {
        val s = me()
        dao.upsert(
            MessageEntity(
                localId = newCmid(), scope = scope, peerId = peer, senderId = s.userId, senderName = s.username,
                kind = kind.name, text = text, mediaJson = ref?.let { VesperJson.encodeToString(MediaRef.serializer(), it) },
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
        ids.forEach { id -> dao.get(id)?.let { media.purgeStaged(it.stagedBlob) } }
        dao.delete(ids)
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
        if (ref != null && ref.mediaId.isEmpty()) {
            val staged = m.stagedBlob ?: run { dao.markFailed(m.localId, "Media missing"); return true }
            val file = java.io.File(staged)
            if (!file.exists()) { dao.markFailed(m.localId, "Media missing"); return true }
            val sc = if (m.scope == Scope.HALL) MediaScope.HALL else MediaScope.PRIVATE
            val id = media.upload(kind, sc, m.peerId, file, ref.sha256)
            ref = ref.copy(mediaId = id)
            dao.setMedia(m.localId, VesperJson.encodeToString(MediaRef.serializer(), ref))
        }
        val body = MessageBody(text = m.text, media = ref)
        return if (m.scope == Scope.HALL) sendHall(m, kind, body) else sendDirect(m, kind, body)
    }

    private suspend fun sendHall(m: MessageEntity, kind: MessageKind, body: MessageBody): Boolean {
        val reply = rt.request(m.localId, HallSend(m.localId, kind, body)) ?: return false
        return handleReply(m, reply)
    }

    private suspend fun handleReply(m: MessageEntity, reply: com.rajdeep.vesper.shared.Frame): Boolean = when (reply) {
        is Ack -> { dao.markSent(m.localId, reply.id, reply.acceptedAt, reply.expiresAt, reply.seq); true }
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
            val reply = rt.request(m.localId, DirectSend(m.localId, peer, kind, body.media?.mediaId, envelopes)) ?: return false
            if (reply is Nack && reply.code == Nacks.DEVICES) {
                // Device list changed: drop sessions for removed devices; missing ones get fresh bundles next round.
                reply.mismatch?.extra?.forEach { (u, ds) -> ds.forEach { cipher.deleteSession(u, it) } }
                return@repeat
            }
            return handleReply(m, reply)
        }
        return false
    }

    /** Encrypts [payload] for every device of [peer] and every other device of mine. */
    private suspend fun encryptForAll(peer: String, payload: ByteArray): List<OutEnvelope> = withContext(Dispatchers.IO) {
        val s = me()
        val peerDevices = api.get("/v1/keys/$peer/devices", DeviceList.serializer()).deviceIds
        val myDevices = api.get("/v1/keys/${s.userId}/devices", DeviceList.serializer()).deviceIds.filter { it != s.deviceId }
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
        runCatching { syncHall() }
        runCatching { syncDirect() }
        flush()
        runCatching { maintainPreKeys() }
    }

    suspend fun syncAll() {
        runCatching { syncHall() }
        runCatching { syncDirect() }
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

    suspend fun syncDirect(): Int {
        val pending = api.get("/v1/direct/pending", PendingEnvelopes.serializer())
        val done = pending.items.mapNotNull { e -> if (receiveEnvelope(e, live = false)) e.id else null }
        if (done.isNotEmpty()) api.post("/v1/direct/ack", AckIds(done), AckIds.serializer(), Ok.serializer())
        return done.size
    }

    private suspend fun onFrame(f: com.rajdeep.vesper.shared.Frame) {
        when (f) {
            is HallMsg -> storeHall(f.msg, live = true)
            is HallDeleted -> {
                val rows = dao.hallByServerIds(f.ids)
                media.purge(rows.mapNotNull { r -> r.mediaJson?.let { VesperJson.decodeFromString(MediaRef.serializer(), it).mediaId } })
                dao.deleteHallByServerIds(f.ids)
            }
            is DirectEnv -> if (receiveEnvelope(f.env, live = true)) rt.send(DirectAck(listOf(f.env.id)))
            is DirectDelivered -> dao.markDelivered(f.mid)
            is HallMuted -> _hallMutedUntil.value = f.until?.takeIf { it > 0 } ?: if (f.until == null) Long.MAX_VALUE else null
            is AccountEnded -> session.end(f.reason)
            else -> {}
        }
    }

    private suspend fun storeHall(m: HallMessageDto, live: Boolean) {
        val s = session.current() ?: return
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
                state = MsgState.SENT, outgoing = m.senderId == s.userId, createdAt = m.acceptedAt, acceptedAt = m.acceptedAt,
                expiresAt = m.expiresAt, seq = m.seq,
            ),
        )
        if (live && inserted != -1L && m.senderId != s.userId) _events.tryEmit(ChatEvent.Incoming(Scope.HALL, null))
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
                state = MsgState.SENT, outgoing = outgoing, createdAt = e.acceptedAt, acceptedAt = e.acceptedAt, expiresAt = e.expiresAt,
                read = outgoing || notifier.isViewing(peer),
            ),
        )
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
