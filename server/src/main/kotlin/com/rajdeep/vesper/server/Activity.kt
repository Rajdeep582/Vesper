package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.ActivityEvent
import com.rajdeep.vesper.shared.AdminActivity
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.MessageKind
import org.bson.Document

/** Admin-visible activity log. Metadata only; never content. Kept 72 h. */
class ActivityLog(private val db: Db, private val hub: Hub) {
    suspend fun record(type: String, uid: String?, username: String?, peer: String? = null, peerName: String? = null, kind: MessageKind? = null) {
        val now = ServerClock.now()
        val id = randomId()
        db.activity.insertOne(
            doc(
                "_id" to id, "t" to type, "uid" to uid, "un" to username, "peer" to peer, "pn" to peerName,
                "k" to kind?.name, "at" to now.asDate(), "exp" to (now + Limits.TEXT_TTL_MS).asDate(),
            ),
        )
        hub.toAdmins(AdminActivity(ActivityEvent(id, type, now, uid, username, peer, peerName, kind)))
    }

    companion object {
        fun toDto(d: Document) = ActivityEvent(
            id = d.str("_id")!!, type = d.str("t")!!, at = d.millis("at")!!,
            userId = d.str("uid"), username = d.str("un"), peerId = d.str("peer"), peerName = d.str("pn"),
            kind = d.str("k")?.let { MessageKind.of(it) },
        )
    }
}
