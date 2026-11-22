package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.*
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import java.util.UUID
import kotlin.test.*

/** Pinned messages (Hall + private), @mention parsing, push wake targeting and HTTP read receipts. */
class PinsMentionsReadTest {
    private lateinit var mongo: MongoServer
    private val b64 = Base64.getEncoder()
    private val day = Pins.DAY_MS

    @BeforeTest fun start() { mongo = MongoServer(MemoryBackend()); mongo.bind() }
    @AfterTest fun stop() { mongo.shutdown() }

    private fun cfg() = Config.fromEnv(
        mapOf(
            "MONGO_URI" to "mongodb://${mongo.localAddress.hostString}:${mongo.localAddress.port}",
            "MONGO_DB" to "pins_test",
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
        val sig = id.privateKey.calculateSignature(spk.publicKey.serialize())
        val k = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kr = KyberPreKeyRecord(1, System.currentTimeMillis(), k, id.privateKey.calculateSignature(k.publicKey.serialize()))
        return DeviceRegistration(
            regId, b64.encodeToString(id.publicKey.serialize()),
            SignedPreKeyDto(1, b64.encodeToString(spk.publicKey.serialize()), b64.encodeToString(sig)),
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

    private suspend fun ApplicationTestBuilder.pin(token: String, req: PinRequest): HttpStatusCode =
        client.post("/v1/pins") {
            contentType(ContentType.Application.Json); header("Authorization", "Bearer $token")
            setBody(VesperJson.encodeToString(PinRequest.serializer(), req))
        }.status

    private suspend fun ApplicationTestBuilder.pins(token: String): PinList =
        VesperJson.decodeFromString(PinList.serializer(), client.get("/v1/pins") { header("Authorization", "Bearer $token") }.bodyAsText())

    /** Sends Hall texts over the socket; skips presence frames; returns the server ids in order. */
    private suspend fun ApplicationTestBuilder.hallSend(token: String, texts: List<String>): List<String> {
        val ids = mutableListOf<String>()
        createClient { install(WebSockets) }.webSocket("/v1/ws", request = { header("Authorization", "Bearer $token") }) {
            for (t in texts) {
                val cmid = UUID.randomUUID().toString()
                send(WsFrame.Text(VesperJson.encodeToString(Frame.serializer(), HallSend(cmid, MessageKind.TEXT, MessageBody(t)))))
                while (true) {
                    val f = VesperJson.decodeFromString(Frame.serializer(), (incoming.receive() as WsFrame.Text).readText())
                    if (f is Ack && f.cmid == cmid) { ids += f.id; break }
                    if (f is Nack && f.cmid == cmid) fail("nack ${f.message}")
                }
            }
        }
        return ids
    }

    @Test
    fun hallPinExtendsLifetimeAndUnpinRestores() = testApplication {
        val c = cfg(); val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }
        val a = signup("pin_a", 11, "10.1.0.1"); val b = signup("pin_b", 12, "10.1.0.2")
        val (mid) = hallSend(a.accessToken, listOf("pin me"))
        val before = db.hall.one(Document("_id", mid))!!
        val exp0 = before.millis("exp")!!

        assertEquals(HttpStatusCode.OK, pin(b.accessToken, PinRequest(mid, null, 7)))
        val pinned = db.hall.one(Document("_id", mid))!!
        assertNotNull(pinned.millis("pin"))
        assertTrue(pinned.millis("exp")!! >= System.currentTimeMillis() + 7 * day - 60_000, "pin must keep the message for 7 days")
        assertEquals(exp0, pinned.millis("exp0"))
        assertEquals(pinned.millis("exp"), pinned.millis("ttl"), "TTL backstop must move with the pin")

        val list = pins(a.accessToken)
        assertEquals(listOf(mid), list.hall.map { it.mid })
        assertEquals("pin_b", list.hall.single().byName)
        assertEquals("pin me", list.hall.single().msg?.body?.text, "pin list carries the message for phones that lost it")

        assertEquals(HttpStatusCode.OK, pin(a.accessToken, PinRequest(mid, null, 0)))
        val un = db.hall.one(Document("_id", mid))!!
        assertNull(un["pin"]); assertEquals(exp0, un.millis("exp"), "unpin restores the original expiry")
        assertTrue(pins(a.accessToken).hall.isEmpty())
    }

    @Test
    fun hallPinValidationAndLimit() = testApplication {
        val c = cfg(); val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }
        val a = signup("lim_a", 21, "10.2.0.1")
        val ids = hallSend(a.accessToken, listOf("one", "two", "three", "four"))
        assertEquals(HttpStatusCode.BadRequest, pin(a.accessToken, PinRequest(ids[0], null, 2)), "only 1/3/7 days")
        assertEquals(HttpStatusCode.NotFound, pin(a.accessToken, PinRequest("nope", null, 1)))
        ids.forEach { assertEquals(HttpStatusCode.OK, pin(a.accessToken, PinRequest(it, null, 1))); Thread.sleep(5) }
        val active = pins(a.accessToken).hall.map { it.mid }.toSet()
        assertEquals(Pins.MAX_PER_CHAT, active.size, "at most 3 pins per chat")
        assertFalse(ids[0] in active, "the oldest pin is replaced")

        // Expired message can't be pinned.
        db.hall.updateOne(Document("_id", ids[1]), Document("\$set", Document("exp", java.util.Date(System.currentTimeMillis() - 1000))))
        assertEquals(HttpStatusCode.NotFound, pin(a.accessToken, PinRequest(ids[1], null, 3)))
    }

    @Test
    fun directPinOnlyForParticipantsAndExtendsEverything() = testApplication {
        val c = cfg(); val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }
        val a = signup("dp_a", 31, "10.3.0.1"); val b = signup("dp_b", 32, "10.3.0.2"); val x = signup("dp_x", 33, "10.3.0.3")
        val now = System.currentTimeMillis()
        val mid = "m-direct-1"
        val exp0 = now + Limits.MEDIA_TTL_MS
        db.privateMeta.insertOne(doc("_id" to mid, "fromUid" to a.user.id, "toUid" to b.user.id, "pair" to listOf(a.user.id, b.user.id).sorted().joinToString(":"),
            "k" to "IMAGE", "cmid" to "c1", "mediaId" to "blob1", "at" to now.asDate(), "exp" to exp0.asDate()))
        db.envelopes.insertOne(doc("_id" to "e1", "mid" to mid, "fromUid" to a.user.id, "toUid" to b.user.id, "toD" to 1, "exp" to exp0.asDate()))
        db.media.insertOne(doc("_id" to "blob1", "msg" to mid, "st" to "attached", "exp" to exp0.asDate(), "bytes" to 10L))

        assertEquals(HttpStatusCode.Forbidden, pin(x.accessToken, PinRequest(mid, a.user.id, 3)), "outsider can't pin")
        assertEquals(HttpStatusCode.Forbidden, pin(a.accessToken, PinRequest(mid, x.user.id, 3)), "wrong peer")
        assertEquals(HttpStatusCode.OK, pin(b.accessToken, PinRequest(mid, a.user.id, 3)))
        val want = System.currentTimeMillis() + 3 * day - 60_000
        assertTrue(db.privateMeta.one(Document("_id", mid))!!.millis("exp")!! >= want)
        assertTrue(db.envelopes.one(Document("_id", "e1"))!!.millis("exp")!! >= want, "undelivered copy kept too")
        assertTrue(db.media.one(Document("_id", "blob1"))!!.millis("exp")!! >= want, "photo kept while pinned")
        assertEquals(a.user.id, pins(b.accessToken).direct.single().peerId)
        assertEquals(b.user.id, pins(a.accessToken).direct.single().peerId)
        assertTrue(pins(x.accessToken).direct.isEmpty())

        assertEquals(HttpStatusCode.OK, pin(a.accessToken, PinRequest(mid, b.user.id, 0)))
        assertEquals(exp0, db.media.one(Document("_id", "blob1"))!!.millis("exp"), "unpin hands the blob back to its own expiry")
    }

    @Test
    fun readReceiptsOverHttpOnlyForRecipient() = testApplication {
        val c = cfg(); val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }
        val a = signup("rr_a", 41, "10.4.0.1"); val b = signup("rr_b", 42, "10.4.0.2")
        val now = System.currentTimeMillis()
        db.privateMeta.insertOne(doc("_id" to "r1", "fromUid" to a.user.id, "toUid" to b.user.id, "pair" to "p", "k" to "TEXT", "cmid" to "c", "at" to now.asDate(), "exp" to (now + day).asDate()))
        suspend fun read(tok: String, peer: String) = client.post("/v1/direct/read") {
            contentType(ContentType.Application.Json); header("Authorization", "Bearer $tok")
            setBody(VesperJson.encodeToString(DirectRead.serializer(), DirectRead(peer, listOf("r1"))))
        }.status
        assertEquals(HttpStatusCode.OK, read(a.accessToken, b.user.id))
        assertNull(db.privateMeta.one(Document("_id", "r1"))!!["seen"], "the sender can't mark its own message seen")
        assertEquals(HttpStatusCode.OK, read(b.accessToken, a.user.id))
        assertNotNull(db.privateMeta.one(Document("_id", "r1"))!!["seen"])
    }

    @Test
    fun mentionParsing() {
        assertEquals(setOf("raj", "all"), HallMentions.names("hi @Raj and @all! mail me at a@b.com"))
        assertEquals(setOf("deep2003"), HallMentions.names("ping @deep2003."))
        assertEquals(setOf("v.test_1"), HallMentions.names("(@v.test_1)"))
        assertTrue(HallMentions.names("no mentions here @").isEmpty())
        assertTrue(HallMentions.names(null).isEmpty())
    }

    @Test
    fun hallWakeCooldownWithUrgentBypass() {
        val push = Push(Db.connect(cfg()), cfg())
        val t0 = 1_000_000L
        assertEquals(listOf("u1", "u2"), push.hallTargets(listOf("u1", "u2"), emptySet(), t0))
        assertEquals(emptyList(), push.hallTargets(listOf("u1", "u2"), emptySet(), t0 + 1_000), "cooldown suppresses repeats")
        assertEquals(listOf("u2"), push.hallTargets(listOf("u1", "u2"), setOf("u2"), t0 + 2_000), "a mention wakes at once")
        assertEquals(listOf("u1", "u2"), push.hallTargets(listOf("u1", "u2"), emptySet(), t0 + Push.HALL_COOLDOWN_MS + 3_000))
    }

    @Test
    fun hubReportsConnectedDevices() {
        val hub = Hub()
        val ws = java.lang.reflect.Proxy.newProxyInstance(
            io.ktor.websocket.WebSocketSession::class.java.classLoader,
            arrayOf(io.ktor.websocket.WebSocketSession::class.java),
        ) { _, _, _ -> null } as io.ktor.websocket.WebSocketSession
        assertEquals(emptySet(), hub.onlineDevices("u"))
        val c1 = Conn("u", 1, false, ws); val c2 = Conn("u", 2, false, ws)
        hub.add(c1); hub.add(c2)
        assertEquals(setOf(1, 2), hub.onlineDevices("u"))
        hub.remove(c1)
        assertEquals(setOf(2), hub.onlineDevices("u"))
    }

    @Test
    fun residentBytesSumsOnTheDatabase() = testApplication {
        val c = cfg(); val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }
        client.get("/health")
        db.media.insertMany(listOf(doc("_id" to "x1", "bytes" to 100L), doc("_id" to "x2", "bytes" to 250L), doc("_id" to "x3")))
        val svc = MediaService(db, c, LocalMediaStore(c))
        assertEquals(350L, svc.residentBytes())
    }
}
