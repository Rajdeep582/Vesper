package com.rajdeep.vesper.server

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rajdeep.vesper.shared.Ack
import com.rajdeep.vesper.shared.DeviceMismatch
import com.rajdeep.vesper.shared.DirectAck
import com.rajdeep.vesper.shared.DirectDelivered
import com.rajdeep.vesper.shared.DirectRead
import com.rajdeep.vesper.shared.DirectSeen
import com.rajdeep.vesper.shared.DirectEnv
import com.rajdeep.vesper.shared.DirectEnvelopeDto
import com.rajdeep.vesper.shared.DirectSend
import com.rajdeep.vesper.shared.HallDeleted
import com.rajdeep.vesper.shared.HallMessageDto
import com.rajdeep.vesper.shared.HallMsg
import com.rajdeep.vesper.shared.HallPage
import com.rajdeep.vesper.shared.HallSend
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.MediaScope
import com.rajdeep.vesper.shared.MessageBody
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.Nack
import com.rajdeep.vesper.shared.Nacks
import com.rajdeep.vesper.shared.PendingEnvelopes
import com.rajdeep.vesper.shared.VesperJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.bson.Document

private val CMID = Regex("^[A-Za-z0-9-]{8,64}$")

/** Bookkeeping that must not delay the sender's ack (activity log, conversation index, push). */
private val after = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
private fun later(block: suspend () -> Unit) {
    after.launch { runCatching { block() }.onFailure { org.slf4j.LoggerFactory.getLogger("after").warn("background step failed: {}", it.message) } }
}

class Hall(
    private val db: Db,
    private val hub: Hub,
    private val atRest: AtRest,
    private val media: MediaService,
    private val activity: ActivityLog,
    private val names: Names,
    private val push: Push,
) {
    private val limiter = RateLimiter(30, 10_000)

    private fun validate(kind: MessageKind, body: MessageBody): String? {
        if (VesperJson.encodeToString(MessageBody.serializer(), body).length > Limits.MAX_BODY_BYTES) return "Message too large"
        val text = body.text
        val m = body.media
        return when (kind) {
            MessageKind.TEXT -> when {
                m != null -> "Text can't carry media"
                text.isNullOrBlank() -> "Empty message"
                text.length > Limits.MAX_TEXT_CHARS -> "Message too long"
                else -> null
            }
            else -> when {
                m == null -> "Missing media"
                text != null -> "Media messages have no text"
                kind == MessageKind.VOICE && (m.durationMs ?: 0) > Limits.MAX_VOICE_MS + 1000 -> "Voice message too long"
                else -> null
            }
        }
    }

    suspend fun send(c: Caller, f: HallSend): com.rajdeep.vesper.shared.Frame {
        if (!CMID.matches(f.cmid)) return Nack(f.cmid, Nacks.INVALID, "Bad id")
        val user = db.users.one(Document("_id", c.uid)) ?: return Nack(f.cmid, Nacks.INVALID)
        if (user.str("st") == "suspended") return Nack(f.cmid, Nacks.INVALID, "Account suspended")
        val now = ServerClock.now()
        val muteUntil = user.millis("mu")
        if (muteUntil != null && muteUntil > now) {
            return Nack(f.cmid, Nacks.MUTED, "You're muted in the Hall")
        }
        db.hall.one(Document("uid", c.uid).append("cmid", f.cmid))?.let { d ->
            return Ack(f.cmid, d.str("_id")!!, d.millis("at")!!, d.millis("exp")!!, d.long("seq"))
        }
        validate(f.kind, f.body)?.let { return Nack(f.cmid, Nacks.INVALID, it) }
        if (!limiter.allow(c.uid, now)) return Nack(f.cmid, Nacks.RATE, "Slow down a little")
        val mediaId = f.body.media?.mediaId
        if (mediaId != null) {
            runCatching { media.requireAttachable(c, mediaId, f.kind, MediaScope.HALL, null) }.onFailure { return Nack(f.cmid, Nacks.MEDIA, it.message) }
        }
        val id = randomId()
        val keep = f.keep && f.kind == MessageKind.TEXT
        val exp = now + if (keep) com.rajdeep.vesper.shared.Keep.FOREVER_MS else Limits.ttlFor(f.kind)
        val seq = db.nextSeq("hallSeq")
        val enc = atRest.seal(VesperJson.encodeToString(MessageBody.serializer(), f.body), id)
        db.hall.insertOne(
            doc(
                "_id" to id, "seq" to seq, "uid" to c.uid, "k" to f.kind.name, "enc" to enc, "mid" to mediaId, "cmid" to f.cmid,
                "at" to now.asDate(), "exp" to exp.asDate(), "ttl" to if (mediaId == null && !keep) exp.asDate() else null,
            ),
        )
        if (mediaId != null) media.attach(mediaId, id, exp)
        val username = user.str("u")!!
        hub.toAll(HallMsg(HallMessageDto(id, seq, c.uid, username, f.kind, f.body, now, exp, f.cmid)))
        later {
            activity.record("hall", c.uid, username, kind = f.kind)
            val offlineUsers = db.users.find(Filters.and(Filters.ne("_id", c.uid), Filters.eq("st", "active")))
                .toList()
                .mapNotNull { it.str("_id") }
                .filter { !hub.isOnline(it) }
            if (offlineUsers.isNotEmpty()) push.wakeHall(offlineUsers)
        }
        return Ack(f.cmid, id, now, exp, seq)
    }

    suspend fun page(afterSeq: Long, limit: Int, senderFilter: String? = null): HallPage {
        val now = ServerClock.now()
        val filters = mutableListOf(Filters.gt("seq", afterSeq), Filters.gt("exp", now.asDate()))
        senderFilter?.let { filters += Filters.eq("uid", it) }
        val docs = db.hall.find(Filters.and(filters)).sort(Sorts.ascending("seq")).limit(limit.coerceIn(1, 500)).toList()
        val nameMap = names.resolve(docs.mapNotNull { it.str("uid") }.toSet())
        return HallPage(docs.map { toDto(it, nameMap) }, now)
    }

    fun toDto(d: Document, nameMap: Map<String, String>): HallMessageDto {
        val id = d.str("_id")!!
        val body = VesperJson.decodeFromString(MessageBody.serializer(), atRest.open(d.str("enc")!!, id))
        return HallMessageDto(
            id, d.long("seq")!!, d.str("uid")!!, nameMap[d.str("uid")] ?: "former member",
            MessageKind.valueOf(d.str("k")!!), body, d.millis("at")!!, d.millis("exp")!!, d.str("cmid"),
        )
    }

    suspend fun ownedBy(uid: String, ids: List<String>): List<String> =
        db.hall.find(Filters.and(Filters.`in`("_id", ids), Filters.eq("uid", uid))).projection(Document("_id", 1)).toList().map { it.str("_id")!! }

    /** Hard delete (admin or the author): blob first, then the document. */
    suspend fun delete(ids: List<String>) {
        val docs = db.hall.find(Filters.`in`("_id", ids)).toList()
        val mediaIds = docs.mapNotNull { it.str("mid") }
        val gone = media.purge(mediaIds).toSet()
        val deletable = docs.filter { it.str("mid") == null || it.str("mid") in gone }.map { it.str("_id")!! }
        // Media whose blob delete failed: expire now so the sweeper retries; hide from clients immediately.
        val retry = docs.filter { it.str("mid") != null && it.str("mid") !in gone }
        retry.forEach { db.hall.updateOne(Document("_id", it.str("_id")), Updates.set("exp", ServerClock.now().asDate())) }
        retry.mapNotNull { it.str("mid") }.forEach { db.media.updateOne(Document("_id", it), Updates.set("exp", ServerClock.now().asDate())) }
        if (deletable.isNotEmpty()) db.hall.deleteMany(Filters.`in`("_id", deletable))
        hub.toAll(HallDeleted(docs.map { it.str("_id")!! }))
    }
}

class Direct(
    private val db: Db,
    private val hub: Hub,
    private val media: MediaService,
    private val activity: ActivityLog,
    private val accounts: Accounts,
    private val names: Names,
    private val push: Push,
) {
    private val limiter = RateLimiter(40, 10_000)

    suspend fun send(c: Caller, f: DirectSend): com.rajdeep.vesper.shared.Frame {
        if (!CMID.matches(f.cmid)) return Nack(f.cmid, Nacks.INVALID, "Bad id")
        val now = ServerClock.now()
        db.privateMeta.one(Document("fromUid", c.uid).append("cmid", f.cmid))?.let { d ->
            return Ack(f.cmid, d.str("_id")!!, d.millis("at")!!, d.millis("exp")!!)
        }
        if (f.toUserId == c.uid) return Nack(f.cmid, Nacks.INVALID, "Can't message yourself")
        // One round trip for both accounts.
        val both = db.users.find(Filters.`in`("_id", listOf(c.uid, f.toUserId))).projection(Document("st", 1).append("u", 1)).toList().associateBy { it.str("_id") }
        val me = both[c.uid]
        if (me == null || me.str("st") == "suspended") return Nack(f.cmid, Nacks.INVALID, "Account suspended")
        val peer = both[f.toUserId] ?: return Nack(f.cmid, Nacks.UNKNOWN_USER, "This person no longer exists")
        if (peer.str("st") == "suspended") return Nack(f.cmid, Nacks.UNKNOWN_USER, "This person is unavailable")
        if (!limiter.allow(c.uid, now)) return Nack(f.cmid, Nacks.RATE, "Slow down a little")
        if (f.envelopes.any { it.body.length > Limits.MAX_BODY_BYTES * 2 }) return Nack(f.cmid, Nacks.INVALID, "Message too large")
        if ((f.kind == MessageKind.TEXT) != (f.mediaId == null)) return Nack(f.cmid, Nacks.INVALID, "Kind/media mismatch")

        // Device-set check: every device of the recipient and every *other* device of the sender.
        val devs = db.devices.find(Filters.`in`("uid", listOf(c.uid, f.toUserId))).projection(Document("uid", 1).append("d", 1)).toList()
            .groupBy({ it.str("uid")!! }, { it.int("d")!! })
        val expected = mapOf(
            f.toUserId to (devs[f.toUserId] ?: emptyList()).toSet(),
            c.uid to ((devs[c.uid] ?: emptyList()).toSet() - c.did),
        )
        val provided = f.envelopes.groupBy { it.toUserId }.mapValues { (_, v) -> v.map { it.deviceId }.toSet() }
        if (provided.keys.any { it !in expected.keys }) return Nack(f.cmid, Nacks.INVALID, "Unexpected recipient")
        val missing = expected.mapValues { (u, s) -> (s - (provided[u] ?: emptySet())).toList() }.filterValues { it.isNotEmpty() }
        val extra = provided.mapValues { (u, s) -> (s - (expected[u] ?: emptySet())).toList() }.filterValues { it.isNotEmpty() }
        if (missing.isNotEmpty() || extra.isNotEmpty()) return Nack(f.cmid, Nacks.DEVICES, "Device list changed", DeviceMismatch(missing, extra))
        if (f.envelopes.none { it.toUserId == f.toUserId }) return Nack(f.cmid, Nacks.UNKNOWN_USER, "This person has no active devices")

        val mediaDoc = f.mediaId?.let {
            runCatching { media.requireAttachable(c, it, f.kind, MediaScope.PRIVATE, f.toUserId) }.getOrElse { e -> return Nack(f.cmid, Nacks.MEDIA, e.message) }
        }
        val mid = randomId()
        val exp = now + if (f.keep && f.kind == MessageKind.TEXT) com.rajdeep.vesper.shared.Keep.FOREVER_MS else Limits.ttlFor(f.kind)
        val pair = listOf(c.uid, f.toUserId).sorted().joinToString(":")
        try {
            db.privateMeta.insertOne(
                doc(
                    "_id" to mid, "fromUid" to c.uid, "toUid" to f.toUserId, "pair" to pair, "k" to f.kind.name, "cmid" to f.cmid,
                    "mediaId" to f.mediaId, "bytes" to mediaDoc?.long("bytes"), "at" to now.asDate(), "exp" to exp.asDate(),
                ),
            )
        } catch (e: com.mongodb.MongoWriteException) {
            if (e.error.code == 11000) {
                val d = db.privateMeta.one(Document("fromUid", c.uid).append("cmid", f.cmid))!!
                return Ack(f.cmid, d.str("_id")!!, d.millis("at")!!, d.millis("exp")!!)
            }
            throw e
        }
        val envDocs = f.envelopes.map { e ->
            doc(
                "_id" to randomId(), "mid" to mid, "fromUid" to c.uid, "fromD" to c.did, "toUid" to e.toUserId, "toD" to e.deviceId,
                "type" to e.type, "body" to e.body, "k" to f.kind.name, "at" to now.asDate(), "exp" to exp.asDate(),
            )
        }
        db.envelopes.insertMany(envDocs)
        if (f.mediaId != null) media.attach(f.mediaId!!, mid, exp)
        // Stored durably → deliver live and ack right away; the rest happens off the hot path.
        envDocs.forEach { d -> hub.toDevice(d.str("toUid")!!, d.int("toD")!!, DirectEnv(envToDto(d))) }
        val toName = peer.str("u"); val fromName = me.str("u")
        later {
            push.wake(f.toUserId)
            val (a, b) = pair.split(":")
            db.conversations.updateOne(
                Document("_id", pair),
                Updates.combine(Updates.setOnInsert("a", a), Updates.setOnInsert("b", b), Updates.setOnInsert("first", now.asDate()), Updates.set("last", now.asDate())),
                UpdateOptions().upsert(true),
            )
            activity.record("direct", c.uid, fromName, f.toUserId, toName, f.kind)
        }
        return Ack(f.cmid, mid, now, exp)
    }

    private fun envToDto(d: Document) = DirectEnvelopeDto(
        id = d.str("_id")!!, mid = d.str("mid")!!, fromUserId = d.str("fromUid")!!, fromDeviceId = d.int("fromD")!!,
        toUserId = d.str("toUid")!!, type = d.int("type")!!, body = d.str("body")!!, kind = MessageKind.valueOf(d.str("k")!!),
        acceptedAt = d.millis("at")!!, expiresAt = d.millis("exp")!!,
    )

    suspend fun pending(c: Caller): PendingEnvelopes {
        val now = ServerClock.now()
        val docs = db.envelopes.find(Filters.and(Filters.eq("toUid", c.uid), Filters.eq("toD", c.did), Filters.gt("exp", now.asDate())))
            .sort(Sorts.ascending("at")).limit(500).toList()
        return PendingEnvelopes(docs.map(::envToDto), now)
    }

    /** Everyone this user has ever messaged with (from the conversation index; no content). Newest first. */
    suspend fun contacts(c: Caller): com.rajdeep.vesper.shared.UserPage {
        val convs = db.conversations.find(Filters.or(Filters.eq("a", c.uid), Filters.eq("b", c.uid))).sort(Sorts.descending("last")).limit(500).toList()
        val peers = convs.map { if (it.str("a") == c.uid) it.str("b")!! else it.str("a")!! }
        if (peers.isEmpty()) return com.rajdeep.vesper.shared.UserPage(emptyList())
        val users = db.users.find(Filters.and(Filters.`in`("_id", peers), Filters.ne("st", "suspended"))).toList().associateBy { it.str("_id") }
        return com.rajdeep.vesper.shared.UserPage(peers.mapNotNull { id -> users[id]?.let { com.rajdeep.vesper.shared.UserSummary(id, it.str("u")!!, it.millis("at") ?: 0) } })
    }

    /** Delete for everyone: only the sender's own messages. Removes metadata, undelivered envelopes, media and backups. */
    suspend fun unsend(c: Caller, mids: List<String>) {
        // Either participant may delete a private message for everyone (sender or recipient).
        val docs = db.privateMeta.find(Filters.and(Filters.`in`("_id", mids.take(500)), Filters.or(Filters.eq("fromUid", c.uid), Filters.eq("toUid", c.uid)))).toList()
        if (docs.isEmpty()) return
        val ids = docs.map { it.str("_id")!! }
        media.purge(docs.mapNotNull { it.str("mediaId") })
        db.envelopes.deleteMany(Filters.`in`("mid", ids))
        db.privateMeta.deleteMany(Filters.`in`("_id", ids))
        val peers = docs.flatMap { listOfNotNull(it.str("toUid"), it.str("fromUid")) }.toSet() - c.uid
        db.backups.deleteMany(Filters.`in`("_id", (peers + c.uid).flatMap { u -> ids.map { "$u:$it" } }))
        val frame = com.rajdeep.vesper.shared.DirectDeleted(ids)
        (peers + c.uid).forEach { hub.toUser(it, frame) }
    }

    /** Read receipt: only for messages this user actually received from [r.peerId]; marks them seen and tells the sender. */
    suspend fun read(c: Caller, r: DirectRead) {
        if (r.mids.isEmpty()) return
        val docs = db.privateMeta.find(Filters.and(Filters.`in`("_id", r.mids.take(500)), Filters.eq("fromUid", r.peerId), Filters.eq("toUid", c.uid))).toList()
        if (docs.isEmpty()) return
        val ids = docs.map { it.str("_id")!! }
        db.privateMeta.updateMany(Filters.and(Filters.`in`("_id", ids), Filters.exists("seen", false)), Updates.set("seen", ServerClock.now().asDate()))
        hub.toUser(r.peerId, DirectSeen(ids, c.uid))
    }

    /** Receipt catch-up: only the caller's own messages; returns which are delivered and which are seen. */
    suspend fun receipts(c: Caller, q: com.rajdeep.vesper.shared.ReceiptQuery): com.rajdeep.vesper.shared.ReceiptState {
        if (q.ids.isEmpty()) return com.rajdeep.vesper.shared.ReceiptState(emptyList(), emptyList())
        val docs = db.privateMeta.find(Filters.and(Filters.`in`("_id", q.ids.take(500)), Filters.eq("fromUid", c.uid)))
            .projection(Document("del", 1).append("seen", 1)).toList()
        return com.rajdeep.vesper.shared.ReceiptState(
            delivered = docs.filter { it["del"] != null }.map { it.str("_id")!! },
            seen = docs.filter { it["seen"] != null }.map { it.str("_id")!! },
        )
    }

    suspend fun ack(c: Caller, a: DirectAck) {
        if (a.ids.isEmpty()) return
        val ids = a.ids.take(500)
        val docs = db.envelopes.find(Filters.and(Filters.`in`("_id", ids), Filters.eq("toUid", c.uid), Filters.eq("toD", c.did))).toList()
        if (docs.isEmpty()) return
        db.envelopes.deleteMany(Filters.`in`("_id", docs.map { it.str("_id") }))
        // Delivered = reached any device of the recipient (not the sender's own other devices).
        val fromPeers = docs.filter { it.str("fromUid") != c.uid }.map { it.str("mid")!! to it.str("fromUid")!! }.distinct()
        if (fromPeers.isEmpty()) return
        // Notify first (receipts feel instant), persist in one write.
        fromPeers.forEach { (mid, from) -> hub.toUser(from, DirectDelivered(mid, c.uid)) }
        db.privateMeta.updateMany(Filters.and(Filters.`in`("_id", fromPeers.map { it.first }), Filters.exists("del", false)), Updates.set("del", ServerClock.now().asDate()))
    }
}

/** Username lookup with a small cache. */
class Names(private val db: Db) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    suspend fun resolve(ids: Set<String>): Map<String, String> {
        val missing = ids.filter { !cache.containsKey(it) }
        if (missing.isNotEmpty()) db.users.find(Filters.`in`("_id", missing)).toList().forEach { cache[it.str("_id")!!] = it.str("u")!! }
        return ids.mapNotNull { id -> cache[id]?.let { id to it } }.toMap()
    }
    fun invalidate(id: String) { cache.remove(id) }
}
