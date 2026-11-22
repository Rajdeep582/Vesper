package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.*
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import java.util.Base64
import kotlin.test.*

/** Live presence (snapshot, online/offline with grace) and typing relay for the Hall and private chats. */
class PresenceTypingTest {
    private lateinit var mongo: MongoServer
    private val b64 = Base64.getEncoder()

    @BeforeTest fun start() { mongo = MongoServer(MemoryBackend()); mongo.bind() }
    @AfterTest fun stop() { mongo.shutdown() }

    private fun cfg() = Config.fromEnv(
        mapOf(
            "MONGO_URI" to "mongodb://${mongo.localAddress.hostString}:${mongo.localAddress.port}",
            "MONGO_DB" to "presence_test",
            "JWT_SECRET" to "VesperJwtSecretKeyMustBeAtLeast48CharactersLongHereForTestingPurpose!",
            "HALL_DATA_KEYSET" to AtRest.generateKeysetB64(),
            "ADMIN_USERNAME" to "vesperadmin",
            "ADMIN_PASSWORD_ARGON2" to Passwords.hash("Admin-Secret-Pass-2026!"),
            "MEDIA_PROVIDER" to "local",
            "VESPER_DEV" to "true",
        ),
    )

    private fun reg(name: String, regId: Int): DeviceRegistration {
        val id = IdentityKeyPair.generate()
        val spk = ECKeyPair.generate()
        val k = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kr = KyberPreKeyRecord(1, System.currentTimeMillis(), k, id.privateKey.calculateSignature(k.publicKey.serialize()))
        return DeviceRegistration(
            regId, b64.encodeToString(id.publicKey.serialize()),
            SignedPreKeyDto(1, b64.encodeToString(spk.publicKey.serialize()), b64.encodeToString(id.privateKey.calculateSignature(spk.publicKey.serialize()))),
            KyberPreKeyDto(1, b64.encodeToString(kr.keyPair.publicKey.serialize()), b64.encodeToString(kr.signature)),
            emptyList(), emptyList(), "t-$name", "hw-$name-$regId",
        )
    }

    private suspend fun ApplicationTestBuilder.signup(name: String, regId: Int, ip: String): AuthResponse {
        val r = client.post("/v1/auth/signup") {
            contentType(ContentType.Application.Json); header("X-Forwarded-For", ip)
            setBody(VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest(name, "Pass-$name-2026!x", reg(name, regId))))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return VesperJson.decodeFromString(AuthResponse.serializer(), r.bodyAsText())
    }

    /** Pumps a socket's frames into a channel so tests can wait for a specific frame. */
    private class Peer(val session: DefaultWebSocketSession) {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        suspend fun send(f: Frame) = session.send(WsFrame.Text(VesperJson.encodeToString(Frame.serializer(), f)))
        suspend inline fun <reified T : Frame> expect(ms: Long = 3_000, noinline pred: (T) -> Boolean = { true }): T =
            withTimeout(ms) { while (true) { val f = frames.receive(); if (f is T && pred(f)) return@withTimeout f } ; error("unreachable") }
        suspend inline fun <reified T : Frame> none(ms: Long, noinline pred: (T) -> Boolean = { true }): Boolean =
            withTimeoutOrNull(ms) { while (true) { val f = frames.receive(); if (f is T && pred(f)) return@withTimeoutOrNull false } ; true } ?: true
    }

    private suspend fun ApplicationTestBuilder.connect(token: String, block: suspend (Peer) -> Unit) {
        createClient { install(WebSockets) }.webSocket("/v1/ws", request = { header("Authorization", "Bearer $token") }) {
            val p = Peer(this)
            coroutineScope {
                val pump = launch { for (raw in incoming) if (raw is WsFrame.Text) runCatching { VesperJson.decodeFromString(Frame.serializer(), raw.readText()) }.getOrNull()?.let { p.frames.trySend(it) } }
                try { block(p) } finally { pump.cancel() }
            }
        }
    }

    @Test
    fun presenceAndTypingRelay() = testApplication {
        val c = cfg(); val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }
        val a = signup("pt_alice", 51, "10.5.0.1"); val b = signup("pt_bob", 52, "10.5.0.2"); val x = signup("pt_carl", 53, "10.5.0.3")

        val bobReady = CompletableDeferred<Peer>(); val carlReady = CompletableDeferred<Peer>(); val aliceDone = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                connect(b.accessToken) { bob ->
                    bob.expect<PresenceSnapshot>()
                    bobReady.complete(bob)
                    // Alice comes online → Bob sees it.
                    bob.expect<Presence> { it.userId == a.user.id && it.online }
                    // Hall typing: name + no target; then stop.
                    val t = bob.expect<TypingAt> { it.fromUserId == a.user.id && it.toUserId == null && it.on }
                    assertEquals("pt_alice", t.fromName)
                    bob.expect<TypingAt> { it.fromUserId == a.user.id && it.toUserId == null && !it.on }
                    // Private typing to Bob.
                    bob.expect<TypingAt> { it.fromUserId == a.user.id && it.toUserId == b.user.id && it.on }
                    aliceDone.await()
                    // Alice left: offline arrives after the grace period (not instantly).
                    assertTrue(bob.none<Presence>(Hub.OFFLINE_GRACE_MS - 1_500) { it.userId == a.user.id && !it.online }, "offline must wait for the grace period")
                    val off = bob.expect<Presence>(Hub.OFFLINE_GRACE_MS + 3_000) { it.userId == a.user.id && !it.online }
                    assertTrue(off.lastActiveAt > 0, "offline carries last seen")
                }
            }
            launch {
                connect(x.accessToken) { carl ->
                    carl.expect<PresenceSnapshot>()
                    carlReady.complete(carl)
                    carl.expect<TypingAt> { it.fromUserId == a.user.id && it.toUserId == null && it.on } // Hall typing is public
                    assertTrue(carl.none<TypingAt>(2_500) { it.toUserId != null }, "private typing must not leak to others")
                }
            }
            launch {
                bobReady.await(); carlReady.await()
                connect(a.accessToken) { alice ->
                    val snap = alice.expect<PresenceSnapshot>()
                    assertTrue(snap.online.containsAll(listOf(b.user.id, x.user.id)), "snapshot lists who's online")
                    kotlinx.coroutines.delay(300)
                    alice.send(Typing(null, true)); kotlinx.coroutines.delay(150)
                    alice.send(Typing(null, false)); kotlinx.coroutines.delay(150)
                    alice.send(Typing(b.user.id, true))
                    assertTrue(alice.none<TypingAt>(800), "typer never gets its own typing back")
                }
                aliceDone.complete(Unit)
            }
        }
    }

    @Test
    fun quickReconnectDoesNotAnnounceOffline() = testApplication {
        val c = cfg(); val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }
        val a = signup("rc_alice", 61, "10.6.0.1"); val b = signup("rc_bob", 62, "10.6.0.2")
        val bobReady = CompletableDeferred<Unit>(); val reconnected = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                connect(b.accessToken) { bob ->
                    bob.expect<PresenceSnapshot>(); bobReady.complete(Unit)
                    bob.expect<Presence> { it.userId == a.user.id && it.online }
                    reconnected.await()
                    assertTrue(bob.none<Presence>(Hub.OFFLINE_GRACE_MS + 1_500) { it.userId == a.user.id && !it.online }, "a blip must not flash offline")
                }
            }
            launch {
                bobReady.await()
                connect(a.accessToken) { it.expect<PresenceSnapshot>() }
                connect(a.accessToken) { it.expect<PresenceSnapshot>(); reconnected.complete(Unit); kotlinx.coroutines.delay(Hub.OFFLINE_GRACE_MS + 2_000) }
            }
        }
    }
}
