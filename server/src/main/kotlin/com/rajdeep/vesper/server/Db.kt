package com.rajdeep.vesper.server

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Compact documents with short field names. Dates are stored as BSON dates so TTL indexes work.
 * See docs/ARCHITECTURE.md for the field dictionary.
 */
class Db(val client: MongoClient, val db: MongoDatabase) {
    val users: MongoCollection<Document> = db.getCollection("users")
    val devices: MongoCollection<Document> = db.getCollection("devices")
    val preKeys: MongoCollection<Document> = db.getCollection("one_time_prekeys")
    val refresh: MongoCollection<Document> = db.getCollection("refresh_tokens")
    val hall: MongoCollection<Document> = db.getCollection("hall_messages")
    val envelopes: MongoCollection<Document> = db.getCollection("private_envelopes")
    val privateMeta: MongoCollection<Document> = db.getCollection("private_meta")
    val conversations: MongoCollection<Document> = db.getCollection("conversations")
    val media: MongoCollection<Document> = db.getCollection("media_objects")
    val activity: MongoCollection<Document> = db.getCollection("activity_events")
    /** Opaque, client-encrypted copies of private messages (the server cannot read them). */
    val backups: MongoCollection<Document> = db.getCollection("chat_backups")
    val counters: MongoCollection<Document> = db.getCollection("counters")

    suspend fun ensureIndexes() {
        val log = org.slf4j.LoggerFactory.getLogger("Db")
        suspend fun safe(name: String, block: suspend () -> Unit) = runCatching { block() }.onFailure { log.warn("index {} failed: {}", name, it.message) }
        safe("3") { users.createIndex(Indexes.ascending("u"), IndexOptions().unique(true)) }
        safe("4") { users.createIndex( Indexes.ascending("role"), IndexOptions().unique(true).partialFilterExpression(Document("role", "admin")).name("one_admin"), ) }
        safe("5") { devices.createIndex(Indexes.ascending("uid", "d"), IndexOptions().unique(true)) }
        safe("6") { preKeys.createIndex(Indexes.ascending("uid", "d", "k", "id"), IndexOptions().unique(true)) }
        safe("7") { refresh.createIndex(Indexes.ascending("exp"), IndexOptions().expireAfter(0, TimeUnit.SECONDS)) }
        safe("8") { refresh.createIndex(Indexes.ascending("uid", "d")) }
        safe("9") { hall.createIndex(Indexes.ascending("seq"), IndexOptions().unique(true)) }
        safe("10") { hall.createIndex(Indexes.ascending("uid", "cmid"), IndexOptions().unique(true)) }
        // TTL backstop. The sweeper deletes first; media-bearing docs are skipped by TTL because their
        // `ttl` field is only set once the blob is gone (see Sweeper).
        safe("13") { hall.createIndex(Indexes.ascending("ttl"), IndexOptions().expireAfter(0, TimeUnit.SECONDS)) }
        safe("14") { hall.createIndex(Indexes.ascending("exp")) }
        safe("15") { envelopes.createIndex(Indexes.ascending("toUid", "toD", "at")) }
        safe("16") { envelopes.createIndex(Indexes.ascending("mid")) }
        safe("17") { envelopes.createIndex(Indexes.ascending("exp"), IndexOptions().expireAfter(0, TimeUnit.SECONDS)) }
        safe("18") { privateMeta.createIndex(Indexes.ascending("fromUid", "cmid"), IndexOptions().unique(true)) }
        safe("19") { privateMeta.createIndex(Indexes.ascending("pair")) }
        safe("20") { privateMeta.createIndex(Indexes.ascending("exp"), IndexOptions().expireAfter(0, TimeUnit.SECONDS)) }
        safe("21") { conversations.createIndex(Indexes.descending("last")) }
        safe("22") { media.createIndex(Indexes.ascending("exp")) }
        safe("23") { media.createIndex(Indexes.ascending("st", "at")) }
        safe("24") { activity.createIndex(Indexes.descending("at")) }
        safe("25") { activity.createIndex(Indexes.ascending("exp"), IndexOptions().expireAfter(0, TimeUnit.SECONDS)) }
        safe("27") { backups.createIndex(Indexes.ascending("uid", "at")) }
        safe("28") { backups.createIndex(Indexes.ascending("exp"), IndexOptions().expireAfter(0, TimeUnit.SECONDS)) }
        safe("26") { devices.createIndex(Indexes.ascending("hw"), IndexOptions().sparse(true)) }
    }

    suspend fun nextSeq(name: String): Long {
        val doc = counters.findOneAndUpdate(
            Document("_id", name),
            Document("\$inc", Document("v", 1L)),
            com.mongodb.client.model.FindOneAndUpdateOptions().upsert(true)
                .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER),
        )
        return (doc?.get("v") as Number).toLong()
    }

    suspend fun dbSizeBytes(): Long = runCatching {
        val stats = db.runCommand(Document("dbStats", 1))
        val data = (stats["dataSize"] as? Number)?.toLong() ?: 0L
        val idx = (stats["indexSize"] as? Number)?.toLong() ?: 0L
        data + idx
    }.getOrDefault(0L)

    companion object {
        fun connect(cfg: Config): Db {
            val client = MongoClient.create(cfg.mongoUri)
            return Db(client, client.getDatabase(cfg.mongoDb))
        }
    }
}

fun Long.asDate(): Date = Date(this)
fun Document.millis(key: String): Long? = (this[key] as? Date)?.time ?: (this[key] as? Number)?.toLong()
fun Document.str(key: String): String? = this[key] as? String
fun Document.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Document.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun doc(vararg pairs: Pair<String, Any?>): Document = Document().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }
suspend fun MongoCollection<Document>.one(filter: Document): Document? = find(filter).limit(1).firstOrNull()
