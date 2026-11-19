package com.rajdeep.vesper.server

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.rajdeep.vesper.shared.AccountEnded
import com.rajdeep.vesper.shared.ActivityPage
import com.rajdeep.vesper.shared.AdminDevice
import com.rajdeep.vesper.shared.AdminDeviceList
import com.rajdeep.vesper.shared.AdminMember
import com.rajdeep.vesper.shared.AdminMemberDetail
import com.rajdeep.vesper.shared.AdminOverview
import com.rajdeep.vesper.shared.ConversationMeta
import com.rajdeep.vesper.shared.ConversationPage
import com.rajdeep.vesper.shared.HallMuted
import com.rajdeep.vesper.shared.MemberPage
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.PartnerMeta
import kotlinx.coroutines.flow.toList
import org.bson.Document

/** Admin console data. Private conversations are exposed as metadata only — no route returns content. */
class Admin(
    private val db: Db,
    private val hub: Hub,
    private val accounts: Accounts,
    private val hall: Hall,
    private val media: MediaService,
    private val activity: ActivityLog,
    private val names: Names,
    private val keepAlive: KeepAlive,
    private val startedAt: Long,
) {
    private val forever = 253402300799000L // 9999-12-31

    suspend fun overview(): AdminOverview {
        val now = ServerClock.now()
        val d24 = (now - 24 * 3600_000L).asDate()
        val nowD = now.asDate()
        val metas = db.privateMeta.find(Filters.gt("exp", nowD)).toList()
        val events = db.activity.find(Filters.and(Filters.gt("at", (now - 72 * 3600_000L).asDate()), Filters.`in`("t", listOf("hall", "direct")))).toList()
        val buckets = IntArray(72)
        events.forEach { e -> val h = ((now - (e.millis("at") ?: now)) / 3600_000L).toInt(); if (h in 0..71) buckets[71 - h]++ }
        val credits = runCatching { media.store.usageCredits() }.getOrNull()
        return AdminOverview(
            registered = db.users.countDocuments(),
            onlineNow = hub.onlineCount(),
            active24h = db.users.countDocuments(Filters.gte("la", d24)),
            hall24h = db.hall.countDocuments(Filters.gte("at", d24)),
            hall72h = db.hall.countDocuments(Filters.gt("exp", nowD)),
            directText = metas.count { it.str("k") == MessageKind.TEXT.name }.toLong(),
            directVoice = metas.count { it.str("k") == MessageKind.VOICE.name }.toLong(),
            directImage = metas.count { it.str("k") == MessageKind.IMAGE.name }.toLong(),
            mediaResidentBytes = media.residentBytes(),
            dbSizeBytes = db.dbSizeBytes(),
            dbCapBytes = 512L * 1024 * 1024,
            mediaProvider = media.store.name,
            mediaCreditsUsed = credits?.first,
            mediaCreditsLimit = credits?.second,
            lastKeepAlivePing = keepAlive.lastPing.takeIf { it > 0 },
            activity72h = buckets.toList(),
            serverStartedAt = startedAt,
            serverTime = now,
        )
    }

    private suspend fun member(u: Document): AdminMember {
        val id = u.str("_id")!!
        val mu = u.millis("mu")?.takeIf { it > ServerClock.now() }
        return AdminMember(
            id = id, username = u.str("u")!!, joinedAt = u.millis("at") ?: 0, lastActiveAt = u.millis("la"),
            online = hub.isOnline(id), suspended = u.str("st") == "suspended", hallMuteUntil = mu,
            devices = db.devices.countDocuments(Document("uid", id)).toInt(), isAdmin = u.str("role") == "admin",
        )
    }

    suspend fun members(query: String?): MemberPage {
        val f = query?.trim()?.lowercase()?.filter { it.isLetterOrDigit() || it == '_' || it == '.' }?.takeIf { it.isNotEmpty() }
            ?.let { Filters.regex("u", it.replace(".", "\\.")) } ?: Document()
        val users = db.users.find(f).sort(Sorts.descending("la")).limit(500).toList()
        return MemberPage(users.map { member(it) })
    }

    suspend fun detail(id: String): AdminMemberDetail {
        val u = db.users.one(Document("_id", id)) ?: notFound()
        val nowD = ServerClock.now().asDate()
        val convs = db.conversations.find(Filters.or(Filters.eq("a", id), Filters.eq("b", id))).sort(Sorts.descending("last")).limit(200).toList()
        val peers = convs.map { if (it.str("a") == id) it.str("b")!! else it.str("a")!! }
        val nameMap = names.resolve(peers.toSet())
        val partners = convs.map { c ->
            val peer = if (c.str("a") == id) c.str("b")!! else c.str("a")!!
            val metas = db.privateMeta.find(Filters.and(Filters.eq("pair", c.str("_id")), Filters.gt("exp", nowD))).toList()
            PartnerMeta(
                peer, nameMap[peer] ?: "former member", c.millis("first") ?: 0, c.millis("last") ?: 0,
                metas.count { it.str("k") == "TEXT" }, metas.count { it.str("k") == "VOICE" }, metas.count { it.str("k") == "IMAGE" },
            )
        }
        return AdminMemberDetail(member(u), db.hall.countDocuments(Filters.and(Filters.eq("uid", id), Filters.gt("exp", nowD))), partners)
    }

    private suspend fun requireMember(id: String): Document {
        val u = db.users.one(Document("_id", id)) ?: notFound()
        if (u.str("role") == "admin") forbidden("admin", "Not applicable to the admin identity")
        return u
    }

    suspend fun suspend(id: String) {
        val u = requireMember(id)
        db.users.updateOne(Document("_id", id), Updates.set("st", "suspended"))
        accounts.revokeAll(id, "suspended")
        activity.record("suspended", id, u.str("u"))
    }

    suspend fun unsuspend(id: String) {
        val u = requireMember(id)
        db.users.updateOne(Document("_id", id), Updates.set("st", "active"))
        activity.record("unsuspended", id, u.str("u"))
    }

    suspend fun mute(id: String, minutes: Long?) {
        val u = requireMember(id)
        val until = if (minutes == null) forever else ServerClock.now() + minutes.coerceIn(1, 60L * 24 * 365) * 60_000
        db.users.updateOne(Document("_id", id), Updates.set("mu", until.asDate()))
        hub.toUser(id, HallMuted(if (until == forever) null else until))
        activity.record("muted", id, u.str("u"))
    }

    suspend fun unmute(id: String) {
        val u = requireMember(id)
        db.users.updateOne(Document("_id", id), Updates.unset("mu"))
        hub.toUser(id, HallMuted(0))
        activity.record("unmuted", id, u.str("u"))
    }

    suspend fun signOutAll(id: String) {
        requireMember(id)
        accounts.revokeAll(id, "signed_out_by_admin")
    }

    suspend fun delete(id: String) {
        requireMember(id)
        val hallIds = accounts.deleteAccount(id, "deleted_by_admin")
        names.invalidate(id)
        if (hallIds.isNotEmpty()) hall.delete(hallIds)
    }

    suspend fun conversations(): ConversationPage {
        val nowD = ServerClock.now().asDate()
        val convs = db.conversations.find().sort(Sorts.descending("last")).limit(300).toList()
        val nameMap = names.resolve(convs.flatMap { listOf(it.str("a")!!, it.str("b")!!) }.toSet())
        val metas = db.privateMeta.find(Filters.gt("exp", nowD)).toList().groupBy { it.str("pair") }
        return ConversationPage(
            convs.map { c ->
                val m = metas[c.str("_id")].orEmpty()
                ConversationMeta(
                    c.str("a")!!, nameMap[c.str("a")] ?: "former member", c.str("b")!!, nameMap[c.str("b")] ?: "former member",
                    c.millis("first") ?: 0, c.millis("last") ?: 0,
                    m.count { it.str("k") == "TEXT" }, m.count { it.str("k") == "VOICE" }, m.count { it.str("k") == "IMAGE" },
                )
            },
        )
    }

    suspend fun activityPage(before: Long?, type: String?, user: String?): ActivityPage {
        val f = mutableListOf<org.bson.conversions.Bson>()
        before?.let { f += Filters.lt("at", it.asDate()) }
        type?.let { f += Filters.eq("t", it) }
        user?.let { f += Filters.or(Filters.eq("uid", it), Filters.eq("peer", it)) }
        val docs = db.activity.find(if (f.isEmpty()) Document() else Filters.and(f)).sort(Sorts.descending("at")).limit(100).toList()
        return ActivityPage(docs.map(ActivityLog::toDto))
    }

    suspend fun devices(c: Caller): AdminDeviceList = AdminDeviceList(
        db.devices.find(Document("uid", c.uid)).toList().map {
            AdminDevice(it.int("d")!!, it.str("label") ?: "Android", it.millis("at") ?: 0, it.millis("seen") ?: 0, it.int("d") == c.did)
        }.sortedBy { it.deviceId },
    )
}

/** Tracks the external keep-awake pinger (cron-job.org) so the console can show it is working. */
class KeepAlive { @Volatile var lastPing: Long = 0 }
