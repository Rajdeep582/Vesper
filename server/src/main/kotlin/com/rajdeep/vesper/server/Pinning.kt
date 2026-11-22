package com.rajdeep.vesper.server

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.rajdeep.vesper.shared.Ok
import com.rajdeep.vesper.shared.PinChanged
import com.rajdeep.vesper.shared.PinDto
import com.rajdeep.vesper.shared.PinList
import com.rajdeep.vesper.shared.PinRequest
import com.rajdeep.vesper.shared.Pins
import kotlinx.coroutines.flow.toList
import org.bson.Document

/**
 * WhatsApp-style pinned messages. A pin keeps the message (document, undelivered envelopes, backups and media blobs)
 * alive until the pin ends: `exp = max(exp0, until)` where `exp0` is the message's own expiry. Unpinning restores
 * `exp0`; if that has passed the sweeper removes everything as usual.
 */
class Pinning(private val db: Db, private val hub: Hub, private val hall: Hall, private val names: Names) {

    suspend fun set(c: Caller, r: PinRequest): Ok {
        if (r.days != 0 && r.days !in Pins.DAYS) bad("invalid", "Pick 1, 3 or 7 days")
        if (r.peerId == null) setHall(c, r) else setDirect(c, r)
        return Ok()
    }

    // ---------------------------------------------------------------- Hall
    private suspend fun setHall(c: Caller, r: PinRequest) {
        val now = ServerClock.now()
        val d = db.hall.one(Document("_id", r.mid)) ?: notFound("Message is gone")
        if ((d.millis("exp") ?: 0) <= now) notFound("Message is gone")
        if (r.days == 0) { unpinHall(d, now); return }
        val until = now + r.days * Pins.DAY_MS
        val exp0 = d.millis("exp0") ?: d.millis("exp")!!
        val exp = maxOf(exp0, until)
        db.hall.updateOne(Document("_id", r.mid), Updates.combine(
            Updates.set("pin", until.asDate()), Updates.set("pinBy", c.uid), Updates.set("pinAt", now.asDate()),
            Updates.set("exp0", exp0.asDate()), Updates.set("exp", exp.asDate()),
            if (d["ttl"] != null) Updates.set("ttl", exp.asDate()) else Updates.unset("ttl"),
        ))
        db.media.updateMany(Document("msg", r.mid), Updates.set("exp", exp.asDate()))
        val others = db.hall.find(Filters.and(Filters.gt("pin", now.asDate()), Filters.ne("_id", r.mid))).sort(Sorts.ascending("pinAt")).toList()
        others.take((others.size - (Pins.MAX_PER_CHAT - 1)).coerceAtLeast(0)).forEach { unpinHall(it, now) }
        val name = names.resolve(setOf(c.uid))[c.uid] ?: "someone"
        hub.toAll(PinChanged(r.mid, null, PinDto(r.mid, null, until, c.uid, name, now)))
    }

    private suspend fun unpinHall(d: Document, now: Long) {
        val id = d.str("_id")!!
        val exp0 = d.millis("exp0") ?: d.millis("exp")!!
        val exp = maxOf(exp0, now)
        db.hall.updateOne(Document("_id", id), Updates.combine(
            Updates.unset("pin"), Updates.unset("pinBy"), Updates.unset("pinAt"), Updates.set("exp", exp.asDate()),
            if (d["ttl"] != null) Updates.set("ttl", exp.asDate()) else Updates.unset("ttl"),
        ))
        db.media.updateMany(Document("msg", id), Updates.set("exp", exp.asDate()))
        hub.toAll(PinChanged(id, null, null))
    }

    // ---------------------------------------------------------------- private chats
    private suspend fun setDirect(c: Caller, r: PinRequest) {
        val now = ServerClock.now()
        val peer = r.peerId!!
        val d = db.privateMeta.one(Document("_id", r.mid)) ?: notFound("Message is gone")
        val from = d.str("fromUid"); val to = d.str("toUid")
        if (!((from == c.uid && to == peer) || (from == peer && to == c.uid))) forbidden()
        if ((d.millis("exp") ?: 0) <= now) notFound("Message is gone")
        if (r.days == 0) { unpinDirect(d, now); return }
        val until = now + r.days * Pins.DAY_MS
        val exp0 = d.millis("exp0") ?: d.millis("exp")!!
        val exp = maxOf(exp0, until)
        db.privateMeta.updateOne(Document("_id", r.mid), Updates.combine(
            Updates.set("pin", until.asDate()), Updates.set("pinBy", c.uid), Updates.set("pinAt", now.asDate()),
            Updates.set("exp0", exp0.asDate()), Updates.set("exp", exp.asDate()),
        ))
        extendDirect(r.mid, listOf(c.uid, peer), exp)
        val others = db.privateMeta.find(Filters.and(Filters.eq("pair", d.str("pair")), Filters.gt("pin", now.asDate()), Filters.ne("_id", r.mid)))
            .sort(Sorts.ascending("pinAt")).toList()
        others.take((others.size - (Pins.MAX_PER_CHAT - 1)).coerceAtLeast(0)).forEach { unpinDirect(it, now) }
        val name = names.resolve(setOf(c.uid))[c.uid] ?: "someone"
        hub.toUser(c.uid, PinChanged(r.mid, peer, PinDto(r.mid, peer, until, c.uid, name, now)))
        hub.toUser(peer, PinChanged(r.mid, c.uid, PinDto(r.mid, c.uid, until, c.uid, name, now)))
    }

    private suspend fun unpinDirect(d: Document, now: Long) {
        val id = d.str("_id")!!
        val exp0 = d.millis("exp0") ?: d.millis("exp")!!
        val exp = maxOf(exp0, now)
        db.privateMeta.updateOne(Document("_id", id), Updates.combine(
            Updates.unset("pin"), Updates.unset("pinBy"), Updates.unset("pinAt"), Updates.set("exp", exp.asDate()),
        ))
        val a = d.str("fromUid")!!; val b = d.str("toUid")!!
        extendDirect(id, listOf(a, b), exp)
        hub.toUser(a, PinChanged(id, b, null))
        hub.toUser(b, PinChanged(id, a, null))
    }

    private suspend fun extendDirect(mid: String, users: List<String>, exp: Long) {
        db.envelopes.updateMany(Document("mid", mid), Updates.set("exp", exp.asDate()))
        db.media.updateMany(Document("msg", mid), Updates.set("exp", exp.asDate()))
        db.backups.updateMany(Filters.`in`("_id", users.map { "$it:$mid" }), Updates.set("exp", exp.asDate()))
    }

    // ---------------------------------------------------------------- catch-up
    suspend fun list(c: Caller): PinList {
        val now = ServerClock.now()
        val hallDocs = db.hall.find(Filters.and(Filters.gt("pin", now.asDate()), Filters.gt("exp", now.asDate()))).limit(20).toList()
        val directDocs = db.privateMeta.find(
            Filters.and(Filters.or(Filters.eq("fromUid", c.uid), Filters.eq("toUid", c.uid)), Filters.gt("pin", now.asDate()), Filters.gt("exp", now.asDate())),
        ).limit(500).toList()
        val nameMap = names.resolve((hallDocs + directDocs).flatMap { listOfNotNull(it.str("pinBy"), it.str("uid")) }.toSet())
        val hallPins = hallDocs.map { d ->
            val by = d.str("pinBy") ?: ""
            PinDto(d.str("_id")!!, null, d.millis("pin")!!, by, nameMap[by] ?: "someone", d.millis("pinAt") ?: now, runCatching { hall.toDto(d, nameMap) }.getOrNull())
        }
        val directPins = directDocs.map { d ->
            val by = d.str("pinBy") ?: ""
            val peer = if (d.str("fromUid") == c.uid) d.str("toUid")!! else d.str("fromUid")!!
            PinDto(d.str("_id")!!, peer, d.millis("pin")!!, by, nameMap[by] ?: "someone", d.millis("pinAt") ?: now)
        }
        return PinList(hallPins, directPins, now)
    }
}
