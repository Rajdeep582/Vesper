package com.rajdeep.vesper.data

import android.content.Context
import android.util.LruCache
import com.rajdeep.vesper.core.crypto.MediaCipher
import com.rajdeep.vesper.core.crypto.b64
import com.rajdeep.vesper.core.crypto.unb64
import com.rajdeep.vesper.core.net.Api
import com.rajdeep.vesper.core.net.ApiException
import com.rajdeep.vesper.shared.MediaCommit
import com.rajdeep.vesper.shared.MediaIntentRequest
import com.rajdeep.vesper.shared.MediaIntentResponse
import com.rajdeep.vesper.shared.MediaRef
import com.rajdeep.vesper.shared.MediaScope
import com.rajdeep.vesper.shared.MediaUrl
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.Ok
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A media blob encrypted on-device and staged for upload. Key never leaves the app except inside a message body. */
class StagedMedia(val key: ByteArray, val cipherFile: File, val sha256: String, val bytes: Long)

/**
 * Media pipeline: encrypt → upload (provider-neutral ticket) → commit; download → verify digest → decrypt in memory.
 * Ciphertext is cached in filesDir/media until the message expires. Decrypted bytes live only in a memory LRU.
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: Api,
) {
    private val dir get() = File(ctx.filesDir, "media").apply { mkdirs() }
    private val staging get() = File(ctx.filesDir, "staging").apply { mkdirs() }
    private val plainCache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun stage(plain: ByteArray): StagedMedia = withContext(Dispatchers.Default) {
        val key = MediaCipher.newKey()
        val blob = MediaCipher.encrypt(key, plain)
        val f = File(staging, java.util.UUID.randomUUID().toString() + ".bin")
        f.writeBytes(blob)
        StagedMedia(key, f, MediaCipher.sha256Hex(blob), blob.size.toLong())
    }

    /** Uploads a staged blob and returns the committed media id. The staged file moves into the local cache. */
    suspend fun upload(kind: MessageKind, scope: MediaScope, peer: String?, staged: File, sha256: String): String {
        val bytes = withContext(Dispatchers.IO) { staged.readBytes() }
        val intent = api.post("/v1/media/intents", MediaIntentRequest(kind, scope, bytes.size.toLong(), sha256, peer), MediaIntentRequest.serializer(), MediaIntentResponse.serializer())
        val t = intent.ticket
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val req = when (t.method) {
            "PUT" -> Request.Builder().url(t.url).put(body).apply { t.headers.forEach { (k, v) -> header(k, v) } }.build()
            else -> {
                val mp = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    t.fields.forEach { (k, v) -> addFormDataPart(k, v) }
                    // No extension: Cloudinary may block .bin raw uploads ("resources with extension bin are not allowed").
                    addFormDataPart(t.fileField ?: "file", "blob", body)
                }.build()
                Request.Builder().url(t.url).post(mp).apply { t.headers.forEach { (k, v) -> header(k, v) } }.build()
            }
        }
        val (code, resp) = api.external(req)
        if (code !in 200..299) {
            val why = runCatching { Json.parseToJsonElement(String(resp)).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content }.getOrNull()
            android.util.Log.w("VesperMedia", "upload $code: ${String(resp).take(300)}")
            throw ApiException(code, "upload", "Upload failed ($code)" + (why?.let { ": $it" } ?: ""))
        }
        val provider = runCatching {
            Json.parseToJsonElement(String(resp)).jsonObject.mapNotNull { (k, v) -> runCatching { k to v.jsonPrimitive.content }.getOrNull() }.toMap()
        }.getOrDefault(emptyMap())
            .filterKeys { it in setOf("public_id", "version", "signature", "bytes", "ok") }
        api.post("/v1/media/${intent.mediaId}/commit", MediaCommit(provider), MediaCommit.serializer(), Ok.serializer())
        withContext(Dispatchers.IO) {
            staged.copyTo(File(dir, intent.mediaId + ".bin"), overwrite = true)
            staged.delete()
        }
        return intent.mediaId
    }

    /** Notes-to-self: keep the encrypted blob on this phone only, under a local id. Nothing is uploaded. */
    suspend fun adoptLocal(staged: File, id: String) = withContext(Dispatchers.IO) {
        staged.copyTo(File(dir, "$id.bin"), overwrite = true); staged.delete(); Unit
    }

    /** Returns decrypted bytes for a media ref, downloading and verifying the ciphertext if needed. */
    suspend fun open(ref: MediaRef): ByteArray {
        plainCache.get(ref.mediaId)?.let { return it }
        val lock = locks.getOrPut(ref.mediaId) { Mutex() }
        return lock.withLock {
            plainCache.get(ref.mediaId)?.let { return@withLock it }
            val f = File(dir, ref.mediaId + ".bin")
            val blob = if (f.exists()) withContext(Dispatchers.IO) { f.readBytes() } else {
                val url = api.get("/v1/media/${ref.mediaId}/url", MediaUrl.serializer())
                val (code, data) = api.external(Request.Builder().url(url.url).build())
                if (code !in 200..299) throw ApiException(code, "download", "Media unavailable")
                if (MediaCipher.sha256Hex(data) != ref.sha256) throw ApiException(0, "integrity", "Media failed its integrity check")
                withContext(Dispatchers.IO) { f.writeBytes(data) }
                data
            }
            val plain = withContext(Dispatchers.Default) { MediaCipher.decrypt(ref.key.unb64(), blob) }
            plainCache.put(ref.mediaId, plain)
            plain
        }
    }

    fun cached(mediaId: String): ByteArray? = plainCache.get(mediaId)

    private val decoded get() = File(ctx.filesDir, "files").apply { mkdirs() }

    /** Where a shared file lives once opened (null until downloaded & unpacked). */
    fun fileIfReady(ref: MediaRef): File? = File(decoded, ref.mediaId + "/" + (ref.name ?: "file")).takeIf { ref.mediaId.isNotEmpty() && it.exists() }

    /**
     * Fetches every encrypted part (cached ciphertext first), checks each digest, decrypts in order and unpacks.
     * Plaintext only ever touches app-private storage; [progress] reports 0..1.
     */
    suspend fun openFile(ref: MediaRef, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        fileIfReady(ref)?.let { return@withContext it }
        val lock = locks.getOrPut(ref.mediaId) { Mutex() }
        lock.withLock {
            fileIfReady(ref)?.let { return@withLock it }
            val parts = ref.chunks.ifEmpty { listOf(com.rajdeep.vesper.shared.MediaChunk(ref.mediaId, ref.sha256, ref.bytes)) }
            val key = ref.key.unb64()
            val packed = File(decoded, ref.mediaId + ".part")
            packed.delete()
            java.io.FileOutputStream(packed).use { out ->
                parts.forEachIndexed { i, p ->
                    val f = File(dir, p.id + ".bin")
                    val blob = if (f.exists()) f.readBytes() else {
                        val url = api.get("/v1/media/${p.id}/url", MediaUrl.serializer())
                        val (code, data) = api.external(Request.Builder().url(url.url).build())
                        if (code !in 200..299) throw ApiException(code, "download", "File unavailable")
                        if (MediaCipher.sha256Hex(data) != p.sha256) throw ApiException(0, "integrity", "File failed its integrity check")
                        f.writeBytes(data)
                        data
                    }
                    out.write(MediaCipher.decrypt(key, blob))
                    progress((i + 1f) / parts.size)
                }
            }
            val out = File(decoded, ref.mediaId + "/" + (ref.name ?: "file"))
            com.rajdeep.vesper.media.FilePrep.unpack(packed, ref.codec, out)
            out
        }
    }

    /** Notes-to-self / sender side: keep each staged part locally under its id. */
    suspend fun adoptPart(staged: File, id: String) = adoptLocal(staged, id)

    fun purge(mediaIds: Collection<String>) {
        mediaIds.forEach { id -> plainCache.remove(id); File(dir, "$id.bin").delete(); File(decoded, id).deleteRecursively() }
    }

    fun purgeStaged(path: String?) { path?.let { File(it).deleteRecursively() } }

    /** Removes cached blobs not referenced by any live message (safety net after crashes). */
    fun purgeOrphans(liveIds: Set<String>) {
        dir.listFiles()?.forEach { if (it.nameWithoutExtension !in liveIds) it.delete() }
        decoded.listFiles()?.forEach { if (it.nameWithoutExtension !in liveIds) it.deleteRecursively() }
        File(ctx.cacheDir, "rec").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 10 * 60_000) it.delete() }
        File(ctx.cacheDir, "capture").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 10 * 60_000) it.delete() }
    }

    fun keyB64(key: ByteArray) = key.b64()
}


/** Every stored blob id behind a media ref (all file parts, or the single blob). */
fun MediaRef.blobIds(): List<String> = (chunks.map { it.id } + mediaId).filter { it.isNotEmpty() }.distinct()
