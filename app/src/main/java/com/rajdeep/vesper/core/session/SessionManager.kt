package com.rajdeep.vesper.core.session

import android.content.Context
import com.rajdeep.vesper.core.crypto.LocalIdentity
import com.rajdeep.vesper.core.crypto.b64
import com.rajdeep.vesper.core.crypto.unb64
import com.rajdeep.vesper.core.db.KvEntity
import com.rajdeep.vesper.core.db.VesperDatabase
import com.rajdeep.vesper.shared.AuthResponse
import com.rajdeep.vesper.shared.TokenPair
import com.rajdeep.vesper.shared.VesperJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Session(
    val userId: String,
    val username: String,
    val joinedAt: Long,
    val isAdmin: Boolean,
    val deviceId: Int,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val clockSkewMs: Long = 0,
)

sealed interface SessionState {
    data object Loading : SessionState
    data class SignedOut(val reason: String? = null) : SessionState
    data class SignedIn(val session: Session) : SessionState
}

/** Holds the signed-in session and this device's libsignal identity, both persisted in the SQLCipher DB. */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: VesperDatabase,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state
    @Volatile private var cached: Session? = null
    @Volatile private var identity: LocalIdentity? = null
    private val lock = Mutex()
    var onEnded: suspend (String) -> Unit = {}

    suspend fun load() = withContext(Dispatchers.IO) {
        val s = db.kv().get(KEY_SESSION)?.let { runCatching { VesperJson.decodeFromString(Session.serializer(), it) }.getOrNull() }
        identity = db.kv().get(KEY_IDENTITY)?.let { decodeIdentity(it) }
        cached = s
        _state.value = if (s != null && identity != null) SessionState.SignedIn(s) else SessionState.SignedOut()
    }

    fun current(): Session? = cached
    fun accessToken(): String? = cached?.accessToken
    fun localIdentity(): LocalIdentity = identity ?: error("no identity")
    fun now(): Long = System.currentTimeMillis() + (cached?.clockSkewMs ?: 0)

    suspend fun start(auth: AuthResponse, newIdentity: LocalIdentity) = lock.withLock {
        withContext(Dispatchers.IO) {
            val skew = auth.serverTime - System.currentTimeMillis()
            val s = Session(auth.user.id, auth.user.username, auth.user.joinedAt, auth.user.isAdmin, auth.deviceId, auth.accessToken, auth.refreshToken, auth.accessExpiresAt, skew)
            identity = newIdentity
            db.kv().put(KvEntity(KEY_IDENTITY, encodeIdentity(newIdentity)))
            persist(s)
            _state.value = SessionState.SignedIn(s)
        }
    }

    suspend fun updateTokens(p: TokenPair) = withContext(Dispatchers.IO) {
        val s = cached ?: return@withContext
        persist(s.copy(accessToken = p.accessToken, refreshToken = p.refreshToken, accessExpiresAt = p.accessExpiresAt, clockSkewMs = p.serverTime - System.currentTimeMillis()))
    }

    suspend fun updateSkew(serverTime: Long) {
        val s = cached ?: return
        val skew = serverTime - System.currentTimeMillis()
        if (kotlin.math.abs(skew - s.clockSkewMs) > 2_000) withContext(Dispatchers.IO) { persist(s.copy(clockSkewMs = skew)) }
    }

    private suspend fun persist(s: Session) {
        cached = s
        db.kv().put(KvEntity(KEY_SESSION, VesperJson.encodeToString(Session.serializer(), s)))
    }

    /** Ends the session locally: wipes every message, key and media file. Reason is shown on the welcome screen. */
    suspend fun end(reason: String) = lock.withLock {
        if (cached == null && _state.value is SessionState.SignedOut) return@withLock
        withContext(Dispatchers.IO) {
            runCatching { onEnded(reason) }
            cached = null
            identity = null
            db.clearAllTables()
            File(ctx.filesDir, "media").deleteRecursively()
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        _state.value = SessionState.SignedOut(reason)
    }

    private fun encodeIdentity(i: LocalIdentity) = "${i.registrationId}:${i.keyPair.serialize().b64()}"
    private fun decodeIdentity(s: String): LocalIdentity? = runCatching {
        val (reg, kp) = s.split(":", limit = 2)
        LocalIdentity(IdentityKeyPair(kp.unb64()), reg.toInt())
    }.getOrNull()

    companion object {
        const val KEY_SESSION = "session"
        const val KEY_IDENTITY = "identity"
    }
}
