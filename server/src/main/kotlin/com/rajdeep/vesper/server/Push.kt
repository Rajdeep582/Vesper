package com.rajdeep.vesper.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bson.Document
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Content-free wake-up pushes via FCM HTTP v1. The payload is exactly {"t":"wake"}: the app fetches
 * and decrypts on-device. Disabled (no-op) when FCM_SERVICE_ACCOUNT_JSON_B64 is not configured.
 */
class Push(private val db: Db, cfg: Config) {
    private val log = LoggerFactory.getLogger("Push")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient.newHttpClient()
    private val sa = cfg.fcmServiceAccountB64?.let { Json.parseToJsonElement(String(Base64.getDecoder().decode(it))).jsonObject }
    private val projectId = sa?.get("project_id")?.jsonPrimitive?.content
    private val clientEmail = sa?.get("client_email")?.jsonPrimitive?.content
    private val tokenUri = sa?.get("token_uri")?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token"
    private val key: RSAPrivateKey? = sa?.get("private_key")?.jsonPrimitive?.content?.let { pem ->
        val der = Base64.getDecoder().decode(pem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), ""))
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
    }
    @Volatile private var accessToken: Pair<String, Long>? = null
    val enabled get() = key != null && projectId != null

    private fun oauthToken(): String? {
        val now = System.currentTimeMillis()
        accessToken?.let { (t, exp) -> if (exp - 60_000 > now) return t }
        val assertion = JWT.create().withIssuer(clientEmail).withAudience(tokenUri)
            .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .withIssuedAt(Date(now)).withExpiresAt(Date(now + 3_600_000))
            .sign(Algorithm.RSA256(null, key))
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=" + assertion
        val res = http.send(
            HttpRequest.newBuilder(URI(tokenUri)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() != 200) { log.warn("FCM oauth failed: {}", res.statusCode()); return null }
        val obj = Json.parseToJsonElement(res.body()).jsonObject
        val t = obj["access_token"]!!.jsonPrimitive.content
        val ttl = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        accessToken = t to now + ttl * 1000
        return t
    }

    private val hallWakeCooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val fcmCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Document>>>()

    fun invalidateUser(uid: String) { fcmCache.remove(uid) }

    /**
     * Hall wakes are rate-limited per member (one per [HALL_COOLDOWN_MS]) — except for members called by name,
     * @all, or replied to: those are woken at once so their mention notification isn't delayed.
     */
    fun wakeHall(uids: List<String>, urgent: Set<String> = emptySet()) {
        if (!enabled || uids.isEmpty()) return
        hallTargets(uids, urgent, System.currentTimeMillis()).forEach { wake(it) }
    }

    /** Which members get a Hall wake now (cooldown bookkeeping included). Pure apart from the cooldown map. */
    internal fun hallTargets(uids: List<String>, urgent: Set<String>, now: Long): List<String> = uids.filter { uid ->
        val last = hallWakeCooldown[uid] ?: 0L
        if (uid in urgent || now - last > HALL_COOLDOWN_MS) {
            hallWakeCooldown[uid] = now
            true
        } else false
    }

    /** Wakes every device of [uid] except [skipDevices] (devices already connected get the message live). */
    fun wake(uid: String, skipDevices: Set<Int> = emptySet()) {
        if (!enabled) return
        scope.launch {
            val now = System.currentTimeMillis()
            val cached = fcmCache[uid]
            val devices = if (cached != null && now - cached.first < 2 * 60_000L) {
                cached.second
            } else {
                val list = db.devices.find(Document("uid", uid)).toList().filter { it.str("fcm") != null }
                fcmCache[uid] = now to list
                list
            }
            val targets = devices.filter { (it.int("d") ?: -1) !in skipDevices }
            if (targets.isEmpty()) return@launch
            val bearer = runCatching { oauthToken() }.getOrNull() ?: return@launch
            targets.forEach { d ->
                // collapse_key: a burst of wakes for an offline phone is delivered as one.
                val body = """{"message":{"token":"${d.str("fcm")}","data":{"t":"wake"},"android":{"priority":"high","ttl":"86400s","collapse_key":"wake"}}}"""
                val res = runCatching {
                    http.send(
                        HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
                            .header("Authorization", "Bearer $bearer").header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }.getOrNull()
                if (res != null) {
                    if (res.statusCode() == 200) {
                        log.debug("FCM push sent to user {}: status {}", uid, res.statusCode())
                    } else {
                        log.warn("FCM push failed for user {}: status {} body {}", uid, res.statusCode(), res.body())
                        if (res.statusCode() == 404 || res.body().contains("UNREGISTERED")) {
                            fcmCache.remove(uid)
                            db.devices.updateOne(Document("_id", d.str("_id")), Updates.unset("fcm"))
                        }
                    }
                } else {
                    log.warn("FCM push network error for user {}", uid)
                }
            }
        }
    }

    companion object {
        const val HALL_COOLDOWN_MS = 60_000L
    }
}
