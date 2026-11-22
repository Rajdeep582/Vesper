package com.rajdeep.vesper.server

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.MediaCommit
import com.rajdeep.vesper.shared.MediaIntentRequest
import com.rajdeep.vesper.shared.MediaIntentResponse
import com.rajdeep.vesper.shared.MediaScope
import com.rajdeep.vesper.shared.MediaUrl
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.UploadTicket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.bson.Document
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Provider-neutral blob storage. All blobs are client-side encrypted ciphertext. */
interface MediaStore {
    val name: String
    fun uploadTicket(key: String, bytes: Long, expiresAt: Long): UploadTicket
    /** Returns true when the provider confirms the blob exists with the expected size. */
    suspend fun verifyCommit(key: String, bytes: Long, providerResponse: Map<String, String>): Boolean
    fun downloadUrl(key: String, expiresAt: Long): String
    /** Deletes blobs; returns the keys confirmed gone (missing blobs count as gone). */
    suspend fun delete(keys: List<String>): List<String>
    /** Lists stored keys created before [olderThan] (for the orphan reconciler). */
    suspend fun listOlderThan(olderThan: Long): List<String>
    suspend fun usageCredits(): Pair<Double, Double>? = null
}

/** Free-plan Admin API allows 500 calls/hour. Keep a margin for the console's usage card. */
class AdminApiBudget(private val perHour: Int = 450) {
    private val limiter = RateLimiter(perHour, 60 * 60 * 1000)
    fun take(): Boolean = limiter.allow("admin")
}

class CloudinaryMediaStore(private val cfg: Config) : MediaStore {
    override val name = "cloudinary"
    private val log = LoggerFactory.getLogger("Cloudinary")
    private val cloud = cfg.cloudinaryCloud!!
    private val apiKey = cfg.cloudinaryKey!!
    private val secret = cfg.cloudinarySecret!!
    private val cl = Cloudinary(ObjectUtils.asMap("cloud_name", cloud, "api_key", apiKey, "api_secret", secret, "secure", true))
    private val budget = AdminApiBudget()
    @Volatile private var usageCache: Pair<Long, Pair<Double, Double>>? = null

    /** Cloudinary request signature: SHA-1 over "k=v&k=v" (sorted, excluding file/api_key/resource_type) + secret. */
    private fun sign(params: Map<String, String>): String {
        val toSign = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" } + secret
        return MessageDigest.getInstance("SHA-1").digest(toSign.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    override fun uploadTicket(key: String, bytes: Long, expiresAt: Long): UploadTicket {
        val ts = (ServerClock.now() / 1000).toString()
        val signed = mapOf("public_id" to key, "timestamp" to ts, "type" to "authenticated", "overwrite" to "false")
        return UploadTicket(
            method = "POST",
            url = "https://api.cloudinary.com/v1_1/$cloud/raw/upload",
            fields = signed + mapOf("api_key" to apiKey, "signature" to sign(signed)),
            fileField = "file",
            expiresAt = expiresAt,
        )
    }

    override suspend fun verifyCommit(key: String, bytes: Long, providerResponse: Map<String, String>): Boolean {
        val publicId = providerResponse["public_id"] ?: return false
        val version = providerResponse["version"] ?: return false
        val signature = providerResponse["signature"] ?: return false
        val size = providerResponse["bytes"]?.toLongOrNull() ?: return false
        // Cloudinary signs upload responses with sha1("public_id=..&version=.." + secret).
        val expected = sign(mapOf("public_id" to publicId, "version" to version))
        return publicId == key && expected.equals(signature, ignoreCase = true) && size == bytes
    }

    override fun downloadUrl(key: String, expiresAt: Long): String =
        cl.privateDownload(key, "", mapOf<String, Any>("resource_type" to "raw", "type" to "authenticated", "expires_at" to expiresAt / 1000))

    override suspend fun delete(keys: List<String>): List<String> = withContext(Dispatchers.IO) {
        val gone = mutableListOf<String>()
        keys.chunked(100).forEach { chunk ->
            if (budget.take()) {
                val res = runCatching {
                    cl.api().deleteResources(chunk, ObjectUtils.asMap("resource_type", "raw", "type", "authenticated", "invalidate", true))
                }.onFailure { log.warn("bulk delete failed: {}", it.message) }.getOrNull()
                @Suppress("UNCHECKED_CAST")
                val deleted = res?.get("deleted") as? Map<String, Any?>
                deleted?.forEach { (k, v) -> if (v == "deleted" || v == "not_found") gone += k }
            } else {
                // Upload API destroy is not rate-limited; slower but keeps deletion moving.
                chunk.forEach { k ->
                    val r = runCatching {
                        cl.uploader().destroy(k, ObjectUtils.asMap("resource_type", "raw", "type", "authenticated", "invalidate", true))
                    }.getOrNull()
                    val result = r?.get("result")
                    if (result == "ok" || result == "not found") gone += k
                }
            }
        }
        gone
    }

    override suspend fun listOlderThan(olderThan: Long): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            if (!budget.take()) break
            val opts = HashMap<String, Any>(mapOf("resource_type" to "raw", "type" to "authenticated", "prefix" to cfg.cloudinaryFolder, "max_results" to 500))
            cursor?.let { opts["next_cursor"] = it }
            val res = runCatching { cl.api().resources(opts) }.onFailure { log.warn("list failed: {}", it.message) }.getOrNull() ?: break
            @Suppress("UNCHECKED_CAST")
            (res["resources"] as? List<Map<String, Any?>>)?.forEach { r ->
                val created = (r["created_at"] as? String)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                if (created != null && created < olderThan) (r["public_id"] as? String)?.let(out::add)
            }
            cursor = res["next_cursor"] as? String
            pages++
        } while (cursor != null && pages < 10)
        out
    }

    override suspend fun usageCredits(): Pair<Double, Double>? {
        val now = System.currentTimeMillis()
        usageCache?.let { (at, v) -> if (now - at < 10 * 60 * 1000) return v }
        if (!budget.take()) return usageCache?.second
        return withContext(Dispatchers.IO) {
            runCatching {
                val res = cl.api().usage(ObjectUtils.emptyMap())
                @Suppress("UNCHECKED_CAST")
                val credits = res["credits"] as? Map<String, Any?>
                val used = (credits?.get("usage") as? Number)?.toDouble() ?: 0.0
                val limit = (credits?.get("limit") as? Number)?.toDouble() ?: 25.0
                (used to limit).also { usageCache = now to it }
            }.getOrNull()
        }
    }
}

/** Dev-only store: blobs on local disk, served by this server with HMAC-signed URLs. */
class LocalMediaStore(private val cfg: Config) : MediaStore {
    override val name = "local"
    private val dir = File(cfg.localMediaDir).apply { mkdirs() }
    private val mac = SecretKeySpec(cfg.jwtSecret.toByteArray(), "HmacSHA256")

    fun sig(key: String, op: String, exp: Long): String =
        b64url(Mac.getInstance("HmacSHA256").apply { init(mac) }.doFinal("$op|$key|$exp".toByteArray()))

    fun fileFor(key: String): File = File(dir, key.replace('/', '_'))

    fun checkSig(key: String, op: String, exp: Long, sig: String): Boolean =
        exp >= ServerClock.now() && MessageDigest.isEqual(sig(key, op, exp).toByteArray(), sig.toByteArray())

    override fun uploadTicket(key: String, bytes: Long, expiresAt: Long): UploadTicket = UploadTicket(
        method = "PUT",
        url = "${cfg.publicBaseUrl}/v1/devmedia/${key.replace('/', '_')}?op=put&exp=$expiresAt&sig=${sig(key.replace('/', '_'), "put", expiresAt)}",
        headers = mapOf("Content-Type" to "application/octet-stream"),
        expiresAt = expiresAt,
    )

    override suspend fun verifyCommit(key: String, bytes: Long, providerResponse: Map<String, String>): Boolean =
        fileFor(key).let { it.exists() && it.length() == bytes }

    override fun downloadUrl(key: String, expiresAt: Long): String {
        val k = key.replace('/', '_')
        return "${cfg.publicBaseUrl}/v1/devmedia/$k?op=get&exp=$expiresAt&sig=${sig(k, "get", expiresAt)}"
    }

    override suspend fun delete(keys: List<String>): List<String> = keys.filter { k -> fileFor(k).let { !it.exists() || it.delete() } }

    override suspend fun listOlderThan(olderThan: Long): List<String> =
        dir.listFiles()?.filter { it.lastModified() < olderThan }?.map { it.name.replace('_', '/') } ?: emptyList()
}

class MediaService(private val db: Db, private val cfg: Config, val store: MediaStore) {
    suspend fun intent(c: Caller, req: MediaIntentRequest): MediaIntentResponse {
        if (req.kind == MessageKind.TEXT) bad("media", "Text has no media")
        // Files go up as ≤8 MB encrypted parts; photos/voice stay single small blobs.
        val cap = if (req.kind == MessageKind.FILE) Limits.FILE_PART_MAX else Limits.MAX_MEDIA_BYTES
        if (req.bytes <= 0 || req.bytes > cap) bad("media", "File is too large")
        if (!Regex("^[0-9a-f]{64}$").matches(req.sha256)) bad("media", "Bad digest")
        if (req.scope == MediaScope.PRIVATE) {
            val peer = req.peerUserId ?: bad("media", "Missing recipient")
            db.users.one(Document("_id", peer)) ?: bad("media", "Unknown recipient")
        }
        val now = ServerClock.now()
        val id = randomId()
        val key = "${cfg.cloudinaryFolder}/$id"
        db.media.insertOne(
            doc(
                "_id" to id, "prov" to store.name, "key" to key, "sc" to req.scope.name, "k" to req.kind.name,
                "own" to c.uid, "peer" to req.peerUserId, "bytes" to req.bytes, "sha" to req.sha256, "st" to "pending",
                "at" to now.asDate(),
            ),
        )
        return MediaIntentResponse(id, store.uploadTicket(key, req.bytes, now + 10 * 60 * 1000))
    }

    suspend fun commit(c: Caller, id: String, body: MediaCommit) {
        val m = db.media.one(Document("_id", id)) ?: notFound()
        if (m.str("own") != c.uid) forbidden()
        if (m.str("st") != "pending") return
        val ok = store.verifyCommit(m.str("key")!!, m.long("bytes")!!, body.providerResponse)
        if (!ok) bad("media", "Upload could not be verified")
        db.media.updateOne(Document("_id", id), Updates.set("st", "committed"))
    }

    /** Validates a committed blob for attaching to a message. Returns the media doc. */
    suspend fun requireAttachable(c: Caller, id: String, kind: MessageKind, scope: MediaScope, peer: String?): Document {
        val m = db.media.one(Document("_id", id)) ?: bad("media", "Unknown media")
        if (m.str("own") != c.uid || m.str("st") != "committed" || m.str("k") != kind.name || m.str("sc") != scope.name) bad("media", "Media not attachable")
        if (scope == MediaScope.PRIVATE && m.str("peer") != peer) bad("media", "Media recipient mismatch")
        return m
    }

    /** All parts of a file must be attachable; members are capped at 10 MB in total, the admin is not. */
    suspend fun requireAttachableAll(c: Caller, ids: List<String>, kind: MessageKind, scope: MediaScope, peer: String?) {
        if (ids.size != ids.toSet().size) bad("media", "Duplicate part")
        var total = 0L
        ids.forEach { total += requireAttachable(c, it, kind, scope, peer).long("bytes") ?: 0L }
        if (kind == MessageKind.FILE && !c.isAdmin && total > Limits.MAX_FILE_TOTAL) bad("media", "Files can be up to 10 MB")
        if (kind != MessageKind.FILE && ids.size > 1) bad("media", "Only files have parts")
    }

    suspend fun attach(id: String, messageId: String, expiresAt: Long) {
        db.media.updateOne(
            Document("_id", id),
            Updates.combine(Updates.set("st", "attached"), Updates.set("msg", messageId), Updates.set("exp", expiresAt.asDate())),
        )
    }

    suspend fun url(c: Caller, id: String): MediaUrl {
        val m = db.media.one(Document("_id", id)) ?: notFound()
        if (m.str("st") != "attached") notFound()
        if ((m.millis("exp") ?: 0) <= ServerClock.now()) notFound()
        if (m.str("sc") == MediaScope.PRIVATE.name && c.uid != m.str("own") && c.uid != m.str("peer")) forbidden()
        val exp = ServerClock.now() + 5 * 60 * 1000
        return MediaUrl(store.downloadUrl(m.str("key")!!, exp), exp)
    }

    /** Marks every blob owned by [uid] as due now; the sweeper removes them on its next pass. */
    suspend fun expireOwnedNow(uid: String) {
        db.media.updateMany(Document("own", uid), Updates.combine(Updates.set("exp", ServerClock.now().asDate()), Updates.set("st", "attached")))
    }

    /** Deletes blobs for the given media ids, then their ledger rows. Returns ids confirmed gone. */
    suspend fun purge(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val docs = db.media.find(Filters.`in`("_id", ids)).toList()
        val keyToId = docs.associate { it.str("key")!! to it.str("_id")!! }
        val gone = store.delete(keyToId.keys.toList()).mapNotNull { keyToId[it] }
        val missingLedger = ids - docs.map { it.str("_id")!! }.toSet()
        if (gone.isNotEmpty()) db.media.deleteMany(Filters.`in`("_id", gone))
        return gone + missingLedger
    }

    /** Sum on the database side instead of loading every media document. */
    suspend fun residentBytes(): Long = runCatching {
        db.media.aggregate(listOf(Document("\$group", Document("_id", null).append("t", Document("\$sum", "\$bytes"))))).toList()
            .firstOrNull()?.let { (it["t"] as? Number)?.toLong() } ?: 0L
    }.getOrElse { db.media.find().projection(Document("bytes", 1)).toList().sumOf { it.long("bytes") ?: 0 } }
}
