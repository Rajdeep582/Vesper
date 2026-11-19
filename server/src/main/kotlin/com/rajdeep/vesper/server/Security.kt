package com.rajdeep.vesper.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

private val rng = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun randomId(): String = randomBytes(16).joinToString("") { "%02x".format(it) }

object Passwords {
    // OWASP baseline for Argon2id (19 MiB, t=2, p=1). Tuned for small Render instances.
    private val fn: Argon2Function = Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID)
    private val dummy: String by lazy { hash("vesper-dummy-password-for-timing") }

    fun hash(password: String): String = Password.hash(password).addRandomSalt(16).with(fn).result

    fun verify(password: String, hash: String?): Boolean {
        if (hash == null) {
            // Equalise timing for unknown usernames.
            Password.check(password, dummy).with(fn)
            return false
        }
        return runCatching { Password.check(password, hash).with(Argon2Function.getInstanceFromHash(hash)) }.getOrDefault(false)
    }
}

class Tokens(secret: String) {
    private val alg = Algorithm.HMAC256(secret)
    val verifier = JWT.require(alg).withIssuer(ISSUER).build()

    fun access(uid: String, did: Int, role: String, now: Long): Pair<String, Long> {
        val exp = now + ACCESS_TTL_MS
        val token = JWT.create().withIssuer(ISSUER)
            .withClaim("uid", uid).withClaim("did", did).withClaim("role", role)
            .withIssuedAt(Date(now)).withExpiresAt(Date(exp))
            .sign(alg)
        return token to exp
    }

    companion object {
        const val ISSUER = "vesper"
        const val ACCESS_TTL_MS = 15L * 60 * 1000
        const val REFRESH_TTL_MS = 30L * 24 * 60 * 60 * 1000
        fun newRefresh(): String = b64url(randomBytes(32))
    }
}

/** At-rest AEAD for Hall content. The keyset comes from HALL_DATA_KEYSET (base64 Tink JSON). */
class AtRest(keysetB64: String) {
    private val aead: Aead

    init {
        AeadConfig.register()
        val json = String(unb64(keysetB64))
        val handle = TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get())
        aead = handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun seal(plain: String, ad: String): String = b64(aead.encrypt(plain.toByteArray(), ad.toByteArray()))
    fun open(cipher: String, ad: String): String = String(aead.decrypt(unb64(cipher), ad.toByteArray()))

    companion object {
        fun generateKeysetB64(): String {
            AeadConfig.register()
            val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            return b64(TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get()).toByteArray())
        }
    }
}

/** Simple in-memory sliding-window limiter. One instance per rule. */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && q.first() <= now - windowMs) q.removeFirst()
            if (q.size >= max) return false
            q.addLast(now)
            return true
        }
    }

    fun reset(key: String) { hits.remove(key) }

    fun prune(now: Long = System.currentTimeMillis()) {
        hits.entries.removeIf { (_, q) -> synchronized(q) { q.isEmpty() || q.last() <= now - windowMs } }
    }
}
