package com.rajdeep.vesper.data

import com.rajdeep.vesper.core.db.KvEntity
import com.rajdeep.vesper.core.db.MessageEntity
import com.rajdeep.vesper.core.db.MsgState
import com.rajdeep.vesper.core.db.Scope
import com.rajdeep.vesper.core.db.VesperDatabase
import com.rajdeep.vesper.core.net.Api
import com.rajdeep.vesper.shared.BackupIds
import com.rajdeep.vesper.shared.BackupItem
import com.rajdeep.vesper.shared.BackupItems
import com.rajdeep.vesper.shared.BackupKey
import com.rajdeep.vesper.shared.BackupKeyLookup
import com.rajdeep.vesper.shared.Ok
import com.rajdeep.vesper.shared.VesperJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** What a backed-up private message looks like inside its encrypted blob. */
@Serializable
private data class BackupMsg(
    val localId: String, val serverId: String?, val peerId: String?, val senderId: String, val senderName: String,
    val kind: String, val text: String?, val mediaJson: String?, val outgoing: Boolean, val createdAt: Long,
    val acceptedAt: Long?, val expiresAt: Long?, val state: Int,
)

/**
 * Encrypted backup of private chats so they come back after a reinstall, like the Hall does.
 * A random 256-bit key K encrypts each message (AES-GCM) on the phone; the server stores only ciphertext and K wrapped
 * with PBKDF2(password). Note: because the password reaches the server at sign-in, this is weaker than the live E2EE.
 */
@Singleton
class ChatBackup @Inject constructor(private val api: Api, private val db: VesperDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<MessageEntity>(Channel.UNLIMITED)
    private val rnd = SecureRandom()
    @Volatile private var key: ByteArray? = null

    init {
        // Batch uploads: drain whatever is queued, send in one request.
        scope.launch {
            for (first in queue) {
                val batch = mutableListOf(first)
                while (true) { batch += queue.tryReceive().getOrNull() ?: break }
                runCatching { upload(batch) }
            }
        }
    }

    private suspend fun k(): ByteArray? = key ?: db.kv().get(KV)?.let { Base64.getDecoder().decode(it) }?.also { key = it }

    /** After sign-in/up (password in hand): fetch or create the wrapped key, then restore private chats. */
    suspend fun onAuthenticated(password: String) = withContext(Dispatchers.IO) {
        val remote = api.get("/v1/backup/key", BackupKey.serializer())
        val k = remote.takeIf { it.salt.isNotEmpty() }?.let { runCatching { unwrap(it, password) }.getOrNull() } ?: run {
            val fresh = ByteArray(32).also(rnd::nextBytes)
            api.put("/v1/backup/key", wrap(fresh, password), BackupKey.serializer(), Ok.serializer())
            fresh
        }
        key = k
        db.kv().put(KvEntity(KV, Base64.getEncoder().encodeToString(k)))
        runCatching { restore() }
    }

    private suspend fun restore() {
        val k = k() ?: return
        val items = api.get("/v1/backup/items", BackupItems.serializer()).items
        val rows = items.mapNotNull { i ->
            runCatching { VesperJson.decodeFromString(BackupMsg.serializer(), String(open(k, i.blob))) }.getOrNull()?.let { b ->
                MessageEntity(
                    localId = b.localId, serverId = b.serverId, scope = Scope.DIRECT, peerId = b.peerId, senderId = b.senderId,
                    senderName = b.senderName, kind = b.kind, text = b.text, mediaJson = b.mediaJson,
                    state = if (b.outgoing) b.state.coerceIn(MsgState.SENT, MsgState.READ) else MsgState.SENT,
                    outgoing = b.outgoing, createdAt = b.createdAt, acceptedAt = b.acceptedAt, expiresAt = b.expiresAt, read = true,
                )
            }
        }
        rows.forEach { db.messages().insertIgnore(it) }
    }

    /** Queue a private message for backup once it has a server id. */
    fun save(m: MessageEntity) {
        if (m.scope != Scope.DIRECT || m.serverId == null || m.expiresAt == null || m.state == MsgState.SYSTEM) return
        queue.trySend(m)
    }

    fun forget(serverIds: List<String>) {
        if (serverIds.isEmpty()) return
        scope.launch { runCatching { api.post("/v1/backup/delete", BackupIds(serverIds), BackupIds.serializer(), Ok.serializer()) } }
    }

    private suspend fun upload(ms: List<MessageEntity>) {
        val k = k() ?: return
        val items = ms.map { m ->
            val b = BackupMsg(m.localId, m.serverId, m.peerId, m.senderId, m.senderName, m.kind, m.text, m.mediaJson, m.outgoing, m.createdAt, m.acceptedAt, m.expiresAt, m.state)
            BackupItem(m.serverId!!, seal(k, VesperJson.encodeToString(BackupMsg.serializer(), b).toByteArray()), m.expiresAt!!)
        }
        items.chunked(200).forEach { api.put("/v1/backup/items", BackupItems(it), BackupItems.serializer(), Ok.serializer()) }
    }

    /** New wrapped key for a password change (signed in: local K). */
    suspend fun rewrapLocal(newPassword: String): BackupKey? = k()?.let { wrap(it, newPassword) }

    /** New wrapped key for a signed-out password change: fetch with old credentials, unwrap, re-wrap. */
    suspend fun rewrapRemote(username: String, oldPassword: String, newPassword: String): BackupKey? = runCatching {
        val remote = api.post("/v1/auth/backup-key", BackupKeyLookup(username, oldPassword), BackupKeyLookup.serializer(), BackupKey.serializer(), auth = false)
        if (remote.salt.isEmpty()) null else wrap(unwrap(remote, oldPassword), newPassword)
    }.getOrNull()

    fun clear() { key = null }

    // ---- crypto
    private fun kek(password: String, salt: ByteArray, iters: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iters, 256)).encoded

    private fun wrap(k: ByteArray, password: String): BackupKey {
        val salt = ByteArray(16).also(rnd::nextBytes)
        return BackupKey(b64(salt), ITERS, seal(kek(password, salt, ITERS), k))
    }

    private fun unwrap(b: BackupKey, password: String): ByteArray = open(kek(password, unb64(b.salt), b.iterations), b.wrapped)

    private fun seal(k: ByteArray, plain: ByteArray): String {
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv)) }
        return b64(iv + c.doFinal(plain))
    }

    private fun open(k: ByteArray, blob: String): ByteArray {
        val all = unb64(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, all, 0, 12)) }
        return c.doFinal(all, 12, all.size - 12)
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object { private const val KV = "backup.k"; private const val ITERS = 150_000 }
}
