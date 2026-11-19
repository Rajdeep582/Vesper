package com.rajdeep.vesper.server

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.rajdeep.vesper.shared.BackupItem
import com.rajdeep.vesper.shared.BackupItems
import com.rajdeep.vesper.shared.BackupKey
import kotlinx.coroutines.flow.toList
import org.bson.Document

/**
 * Encrypted chat backup. Items are AES-GCM blobs made on the phone with a random key K; K is stored here only
 * wrapped by a key derived from the user's password. Mongo TTL removes each item when its message expires.
 */
class Backups(private val db: Db) {
    suspend fun key(uid: String): BackupKey? = db.users.one(Document("_id", uid))?.get("bk", Document::class.java)?.let {
        BackupKey(it.getString("s"), it.getInteger("i"), it.getString("w"))
    }

    suspend fun setKey(uid: String, k: BackupKey) {
        if (k.salt.length > 64 || k.wrapped.length > 256 || k.iterations !in 50_000..2_000_000) bad("backup", "Bad backup key")
        db.users.updateOne(Document("_id", uid), Updates.set("bk", Document("s", k.salt).append("i", k.iterations).append("w", k.wrapped)))
    }

    suspend fun put(uid: String, items: List<BackupItem>) {
        val now = ServerClock.now()
        items.take(500).forEach { i ->
            if (i.id.length > 80 || i.blob.length > 64_000 || i.expiresAt <= now) return@forEach
            db.backups.replaceOne(
                Document("_id", "$uid:${i.id}"),
                Document("_id", "$uid:${i.id}").append("uid", uid).append("b", i.blob).append("at", now.asDate()).append("exp", i.expiresAt.asDate()),
                ReplaceOptions().upsert(true),
            )
        }
    }

    suspend fun list(uid: String): BackupItems {
        val now = ServerClock.now()
        val docs = db.backups.find(Filters.and(Filters.eq("uid", uid), Filters.gt("exp", now.asDate()))).sort(Sorts.ascending("at")).limit(20_000).toList()
        return BackupItems(docs.map { BackupItem(it.str("_id")!!.substringAfter(':'), it.str("b")!!, it.millis("exp")!!) })
    }

    suspend fun delete(uid: String, ids: List<String>) {
        if (ids.isNotEmpty()) db.backups.deleteMany(Filters.`in`("_id", ids.take(1000).map { "$uid:$it" }))
    }

    suspend fun deleteAll(uid: String) { db.backups.deleteMany(Document("uid", uid)) }
}
