package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.*
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class FullSystemAtoZTest {
    private lateinit var mongo: MongoServer
    private val adminPw = "Admin-Secret-Pass-2026!"
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    @BeforeTest
    fun start() {
        mongo = MongoServer(MemoryBackend())
        mongo.bind()
    }

    @AfterTest
    fun stop() {
        mongo.shutdown()
    }

    private fun cfg() = Config.fromEnv(
        mapOf(
            "MONGO_URI" to "mongodb://${mongo.localAddress.hostString}:${mongo.localAddress.port}",
            "MONGO_DB" to "vesper_test",
            "JWT_SECRET" to "VesperJwtSecretKeyMustBeAtLeast48CharactersLongHereForTestingPurpose!",
            "HALL_DATA_KEYSET" to AtRest.generateKeysetB64(),
            "ADMIN_USERNAME" to "vesperadmin",
            "ADMIN_PASSWORD_ARGON2" to Passwords.hash(adminPw),
            "MEDIA_PROVIDER" to "local",
            "VESPER_DEV" to "true",
        ),
    )

    private data class SimulatedDevice(
        val username: String,
        val identityKeyPair: IdentityKeyPair,
        val registrationId: Int,
        val store: StringKeyedStore,
        val registrationDto: DeviceRegistration,
    )

    private fun createDevice(username: String, regId: Int = 1000): SimulatedDevice {
        val id = IdentityKeyPair.generate()
        val store = StringKeyedStore(id, regId)
        val spk = ECKeyPair.generate()
        val spkRec = SignedPreKeyRecord(1, System.currentTimeMillis(), spk, id.privateKey.calculateSignature(spk.publicKey.serialize()))
        store.storeSignedPreKey(1, spkRec)

        val kyberLast = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberLastRec = KyberPreKeyRecord(1, System.currentTimeMillis(), kyberLast, id.privateKey.calculateSignature(kyberLast.publicKey.serialize()))
        store.storeKyberPreKey(1, kyberLastRec)

        val preKeys = (1..5).map {
            val kp = ECKeyPair.generate()
            PreKeyRecord(it, kp).also { rec -> store.storePreKey(it, rec) }
        }
        val kyberPreKeys = (2..6).map {
            val kk = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            KyberPreKeyRecord(it, System.currentTimeMillis(), kk, id.privateKey.calculateSignature(kk.publicKey.serialize())).also { rec ->
                store.storeKyberPreKey(it, rec)
            }
        }

        val regDto = DeviceRegistration(
            registrationId = regId,
            identityKey = b64e.encodeToString(id.publicKey.serialize()),
            signedPreKey = SignedPreKeyDto(1, b64e.encodeToString(spk.publicKey.serialize()), b64e.encodeToString(spkRec.signature)),
            kyberLastResort = KyberPreKeyDto(1, b64e.encodeToString(kyberLast.publicKey.serialize()), b64e.encodeToString(kyberLastRec.signature)),
            preKeys = preKeys.map { PreKeyDto(it.id, b64e.encodeToString(it.keyPair.publicKey.serialize())) },
            kyberPreKeys = kyberPreKeys.map { KyberPreKeyDto(it.id, b64e.encodeToString(it.keyPair.publicKey.serialize()), b64e.encodeToString(it.signature)) },
            label = "Simulated $username",
            hwid = "hwid-$username-$regId",
        )
        return SimulatedDevice(username, id, regId, store, regDto)
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: Any, token: String? = null, ip: String = "192.168.1.1"): Pair<HttpStatusCode, String> {
        val r = client.post(path) {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", ip)
            token?.let { header("Authorization", "Bearer $it") }
            val jsonText = when (body) {
                is AuthRequest -> VesperJson.encodeToString(AuthRequest.serializer(), body)
                is RefreshRequest -> VesperJson.encodeToString(RefreshRequest.serializer(), body)
                is AckIds -> VesperJson.encodeToString(AckIds.serializer(), body)
                is ReceiptQuery -> VesperJson.encodeToString(ReceiptQuery.serializer(), body)
                is BackupKey -> VesperJson.encodeToString(BackupKey.serializer(), body)
                is BackupItems -> VesperJson.encodeToString(BackupItems.serializer(), body)
                is String -> body
                else -> error("unsupported body: $body")
            }
            setBody(jsonText)
        }
        return r.status to r.bodyAsText()
    }

    private suspend fun ApplicationTestBuilder.putJson(path: String, body: Any, token: String? = null, ip: String = "192.168.1.1"): Pair<HttpStatusCode, String> {
        val r = client.put(path) {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", ip)
            token?.let { header("Authorization", "Bearer $it") }
            val jsonText = when (body) {
                is BackupKey -> VesperJson.encodeToString(BackupKey.serializer(), body)
                is BackupItems -> VesperJson.encodeToString(BackupItems.serializer(), body)
                is String -> body
                else -> error("unsupported body: $body")
            }
            setBody(jsonText)
        }
        return r.status to r.bodyAsText()
    }

    private suspend fun ApplicationTestBuilder.getJson(path: String, token: String? = null, ip: String = "192.168.1.1"): Pair<HttpStatusCode, String> {
        val r = client.get(path) {
            header("X-Forwarded-For", ip)
            token?.let { header("Authorization", "Bearer $it") }
        }
        return r.status to r.bodyAsText()
    }

    @Test
    fun testAuthenticationLifecycleAndSecurityRules() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val alice = createDevice("alice_user", 101)

        // 1. Weak password rejected
        val (weakStatus, weakBody) = postJson("/v1/auth/signup", AuthRequest("alice_user", "12345", alice.registrationDto), ip = "10.0.1.1")
        assertEquals(HttpStatusCode.BadRequest, weakStatus)
        assertTrue(weakBody.contains("weak") || weakBody.contains("password"))

        // 2. Valid signup succeeds
        val (signupStatus, signupBody) = postJson("/v1/auth/signup", AuthRequest("alice_user", "Strong-Password-Alice-2026!", alice.registrationDto), ip = "10.0.1.2")
        assertEquals(HttpStatusCode.OK, signupStatus, signupBody)
        val aliceAuth = VesperJson.decodeFromString(AuthResponse.serializer(), signupBody)
        assertEquals("alice_user", aliceAuth.user.username)
        assertEquals(1, aliceAuth.deviceId)
        assertFalse(aliceAuth.user.isAdmin)

        // 3. Duplicate username signup fails
        val (dupStatus, _) = postJson("/v1/auth/signup", AuthRequest("alice_user", "Strong-Password-Alice-2026!", alice.registrationDto), ip = "10.0.1.3")
        assertEquals(HttpStatusCode.BadRequest, dupStatus)

        // 4. Case-variant username signup fails ("Alice_User")
        val (caseStatus, _) = postJson("/v1/auth/signup", AuthRequest("Alice_User", "Strong-Password-Alice-2026!", alice.registrationDto), ip = "10.0.1.4")
        assertEquals(HttpStatusCode.BadRequest, caseStatus)

        // 5. Cannot sign up with reserved admin username
        val (adminSignupStatus, _) = postJson("/v1/auth/signup", AuthRequest("vesperadmin", "Strong-Password-Admin-2026!", alice.registrationDto), ip = "10.0.1.5")
        assertEquals(HttpStatusCode.BadRequest, adminSignupStatus)

        // 6. Sign in with wrong password returns 401
        val (wrongPwStatus, _) = postJson("/v1/auth/signin", AuthRequest("alice_user", "Wrong-Password-999!", alice.registrationDto), ip = "10.0.1.6")
        assertEquals(HttpStatusCode.Unauthorized, wrongPwStatus)

        // 7. Sign in with correct password succeeds
        val (signinStatus, signinBody) = postJson("/v1/auth/signin", AuthRequest("alice_user", "Strong-Password-Alice-2026!", alice.registrationDto), ip = "10.0.1.7")
        assertEquals(HttpStatusCode.OK, signinStatus, signinBody)
        val signinAuth = VesperJson.decodeFromString(AuthResponse.serializer(), signinBody)
        assertEquals(aliceAuth.user.id, signinAuth.user.id)

        // 8. Token Refresh succeeds
        val (refreshStatus, refreshBody) = postJson("/v1/auth/refresh", RefreshRequest(signinAuth.refreshToken), ip = "10.0.1.8")
        assertEquals(HttpStatusCode.OK, refreshStatus, refreshBody)
        val newTokens = VesperJson.decodeFromString(TokenPair.serializer(), refreshBody)
        assertTrue(newTokens.accessToken.isNotBlank())
        assertNotEquals(signinAuth.refreshToken, newTokens.refreshToken)
        assertNotEquals(signinAuth.accessToken, newTokens.accessToken)

        // 9. Admin Sign In with seeded argon2 password succeeds
        val (adminStatus, adminBody) = postJson("/v1/auth/signin", AuthRequest("vesperadmin", adminPw, alice.registrationDto), ip = "10.0.1.9")
        assertEquals(HttpStatusCode.OK, adminStatus, adminBody)
        val adminAuth = VesperJson.decodeFromString(AuthResponse.serializer(), adminBody)
        assertTrue(adminAuth.user.isAdmin)
    }

    @Test
    fun testPqxdhKeyDistributionAndPreKeyExhaustion() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val bob = createDevice("bob_keys", 201)
        val (bobSignupStatus, bobSignup) = postJson("/v1/auth/signup", AuthRequest("bob_keys", "Strong-Password-Bob-2026!", bob.registrationDto), ip = "10.0.2.1")
        assertEquals(HttpStatusCode.OK, bobSignupStatus, bobSignup)
        val bobAuth = VesperJson.decodeFromString(AuthResponse.serializer(), bobSignup)

        val alice = createDevice("alice_seeker", 101)
        val (aliceSignupStatus, aliceSignup) = postJson("/v1/auth/signup", AuthRequest("alice_seeker", "Strong-Password-Alice-2026!", alice.registrationDto), ip = "10.0.2.2")
        assertEquals(HttpStatusCode.OK, aliceSignupStatus, aliceSignup)
        val aliceAuth = VesperJson.decodeFromString(AuthResponse.serializer(), aliceSignup)

        // 1. Prekey counts
        val (countStatus, countBody) = getJson("/v1/keys/count", token = bobAuth.accessToken)
        assertEquals(HttpStatusCode.OK, countStatus)
        val counts = VesperJson.decodeFromString(PreKeyCounts.serializer(), countBody)
        assertEquals(5, counts.ec)
        assertEquals(5, counts.kyber)

        // 2. Fetch Bob's devices
        val (devStatus, devBody) = getJson("/v1/keys/${bobAuth.user.id}/devices", token = aliceAuth.accessToken)
        assertEquals(HttpStatusCode.OK, devStatus)
        val devList = VesperJson.decodeFromString(DeviceList.serializer(), devBody)
        assertEquals(listOf(1), devList.deviceIds)

        // 3. Fetch Bob's bundle (consumes 1 one-time EC prekey and 1 one-time Kyber prekey)
        val (bundleStatus, bundleBody) = getJson("/v1/keys/${bobAuth.user.id}", token = aliceAuth.accessToken)
        assertEquals(HttpStatusCode.OK, bundleStatus)
        val bundleResp = VesperJson.decodeFromString(KeysResponse.serializer(), bundleBody)
        assertEquals(1, bundleResp.devices.size)
        val b0 = bundleResp.devices[0]
        assertNotNull(b0.preKey)
        assertNotNull(b0.kyberPreKey)

        // 4. Verify count decremented to 4
        val (_, countBody2) = getJson("/v1/keys/count", token = bobAuth.accessToken)
        val counts2 = VesperJson.decodeFromString(PreKeyCounts.serializer(), countBody2)
        assertEquals(4, counts2.ec)
        assertEquals(4, counts2.kyber)

        // 5. Consume remaining 4 prekeys to test exhaustion fallback
        repeat(4) {
            getJson("/v1/keys/${bobAuth.user.id}", token = aliceAuth.accessToken)
        }
        val (_, countBodyExhausted) = getJson("/v1/keys/count", token = bobAuth.accessToken)
        val countsExhausted = VesperJson.decodeFromString(PreKeyCounts.serializer(), countBodyExhausted)
        assertEquals(0, countsExhausted.ec)
        assertEquals(0, countsExhausted.kyber)

        // 6. When exhausted, fetching bundle still succeeds by falling back to signed prekey & kyberLastResort!
        val (fallbackStatus, fallbackBody) = getJson("/v1/keys/${bobAuth.user.id}", token = aliceAuth.accessToken)
        assertEquals(HttpStatusCode.OK, fallbackStatus)
        val fallbackResp = VesperJson.decodeFromString(KeysResponse.serializer(), fallbackBody)
        val fbDev = fallbackResp.devices[0]
        assertNull(fbDev.preKey) // null when one-time prekeys are exhausted
        assertEquals(1, fbDev.kyberPreKey.id) // falls back to kyberLastResort ID 1!
    }

    @Test
    fun testEndToEndEncryptionAndOfflinePendingDirectSync() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val alice = createDevice("alice_e2ee", 101)
        val (aliceStatus, aliceSignup) = postJson("/v1/auth/signup", AuthRequest("alice_e2ee", "Pass-Alice-E2EE-2026!", alice.registrationDto), ip = "10.0.3.1")
        assertEquals(HttpStatusCode.OK, aliceStatus, aliceSignup)
        val aliceAuth = VesperJson.decodeFromString(AuthResponse.serializer(), aliceSignup)

        val bob = createDevice("bob_e2ee", 201)
        val (bobStatus, bobSignup) = postJson("/v1/auth/signup", AuthRequest("bob_e2ee", "Pass-Bob-E2EE-2026!", bob.registrationDto), ip = "10.0.3.2")
        assertEquals(HttpStatusCode.OK, bobStatus, bobSignup)
        val bobAuth = VesperJson.decodeFromString(AuthResponse.serializer(), bobSignup)

        // Alice fetches Bob's PQXDH bundle and establishes Double Ratchet session
        val (_, bundleBody) = getJson("/v1/keys/${bobAuth.user.id}", token = aliceAuth.accessToken)
        val bundleResp = VesperJson.decodeFromString(KeysResponse.serializer(), bundleBody)
        val bDev = bundleResp.devices[0]

        val bobAddr = SignalProtocolAddress(bobAuth.user.id, bDev.deviceId)
        val aliceAddr = SignalProtocolAddress(aliceAuth.user.id, aliceAuth.deviceId)
        val bundle = PreKeyBundle(
            bDev.registrationId, bDev.deviceId,
            bDev.preKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID, bDev.preKey?.let { ECPublicKey(b64d.decode(it.publicKey)) },
            bDev.signedPreKey.id, ECPublicKey(b64d.decode(bDev.signedPreKey.publicKey)), b64d.decode(bDev.signedPreKey.signature),
            IdentityKey(b64d.decode(bDev.identityKey)), bDev.kyberPreKey.id, KEMPublicKey(b64d.decode(bDev.kyberPreKey.publicKey)), b64d.decode(bDev.kyberPreKey.signature),
        )
        SessionBuilder(alice.store, bobAddr, aliceAddr).process(bundle)
        assertTrue(alice.store.containsSession(bobAddr))

        // Alice encrypts secret message
        val secretText = "CANARY_TOP_SECRET_E2EE_MESSAGE_HELLO_BOB"
        val cmid = UUID.randomUUID().toString()
        val payloadBytes = VesperJson.encodeToString(
            DirectPayload.serializer(),
            DirectPayload(cmid, MessageKind.TEXT, MessageBody(secretText), aliceAuth.user.id, bobAuth.user.id, System.currentTimeMillis()),
        ).toByteArray()
        val ciphertext = SessionCipher(alice.store, aliceAddr, bobAddr).encrypt(payloadBytes)
        val outEnvelope = OutEnvelope(bobAuth.user.id, bDev.deviceId, ciphertext.type, b64e.encodeToString(ciphertext.serialize()))

        // Direct send via WebSocket while Bob is offline
        val clientWs = createClient { install(WebSockets) }
        clientWs.webSocket("/v1/ws", request = { header("Authorization", "Bearer ${aliceAuth.accessToken}") }) {
            send(WsFrame.Text(VesperJson.encodeToString(Frame.serializer(), DirectSend(cmid, bobAuth.user.id, MessageKind.TEXT, null, listOf(outEnvelope)))))
            // The server greets with presence frames first; wait for our ack.
            var ack: Ack? = null
            while (ack == null) {
                val f = VesperJson.decodeFromString(Frame.serializer(), (incoming.receive() as WsFrame.Text).readText())
                if (f is Ack) ack = f
            }
            assertEquals(cmid, ack.cmid)
        }

        // Verify Server Stored Ciphertext only (Canary check in MongoDB)
        val envDoc = db.envelopes.find(Document("toUid", bobAuth.user.id)).toList().first()
        assertFalse(envDoc.getString("body").contains(secretText)) // Body is Base64 ciphertext, never contains plaintext!
        assertEquals(MessageKind.TEXT.name, envDoc.getString("k"))

        // Bob comes online and pulls pending envelopes
        val (pendingStatus, pendingBody) = getJson("/v1/direct/pending", token = bobAuth.accessToken)
        assertEquals(HttpStatusCode.OK, pendingStatus)
        val pending = VesperJson.decodeFromString(PendingEnvelopes.serializer(), pendingBody)
        assertEquals(1, pending.items.size)
        val item = pending.items[0]

        // Bob decrypts the message using libsignal
        val cipherBytes = b64d.decode(item.body)
        val bobCipher = SessionCipher(bob.store, bobAddr, aliceAddr)
        val plainBytes = if (item.type == CiphertextMessage.PREKEY_TYPE) {
            bobCipher.decrypt(PreKeySignalMessage(cipherBytes))
        } else {
            bobCipher.decrypt(SignalMessage(cipherBytes))
        }
        val decryptedPayload = VesperJson.decodeFromString(DirectPayload.serializer(), String(plainBytes))
        assertEquals(secretText, decryptedPayload.body.text)
        assertEquals(aliceAuth.user.id, decryptedPayload.fromUserId)

        // Bob ACKs the message
        val (ackStatus, _) = postJson("/v1/direct/ack", AckIds(listOf(item.id)), token = bobAuth.accessToken)
        assertEquals(HttpStatusCode.OK, ackStatus)

        // Envelope deleted from pending queue on server
        val remainingPending = db.envelopes.find(Document("toUid", bobAuth.user.id)).toList()
        assertTrue(remainingPending.isEmpty())
    }

    @Test
    fun testPublicHallMessagingSequencingAndAtRestEncryption() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val userA = createDevice("hall_user_a", 101)
        val (statusA, authAJson) = postJson("/v1/auth/signup", AuthRequest("hall_user_a", "Pass-Hall-A-2026!", userA.registrationDto), ip = "10.0.4.1")
        assertEquals(HttpStatusCode.OK, statusA, authAJson)
        val authA = VesperJson.decodeFromString(AuthResponse.serializer(), authAJson)

        val userB = createDevice("hall_user_b", 201)
        val (statusB, authBJson) = postJson("/v1/auth/signup", AuthRequest("hall_user_b", "Pass-Hall-B-2026!", userB.registrationDto), ip = "10.0.4.2")
        assertEquals(HttpStatusCode.OK, statusB, authBJson)
        val authB = VesperJson.decodeFromString(AuthResponse.serializer(), authBJson)

        val clientWs = createClient { install(WebSockets) }
        val hallSecret = "CANARY_HALL_PUBLIC_MESSAGE_1234"

        // User A sends message to Hall via WebSocket
        clientWs.webSocket("/v1/ws", request = { header("Authorization", "Bearer ${authA.accessToken}") }) {
            val cmid = UUID.randomUUID().toString()
            send(WsFrame.Text(VesperJson.encodeToString(Frame.serializer(), HallSend(cmid, MessageKind.TEXT, MessageBody(hallSecret)))))
            val frames = mutableListOf<Frame>()
            // Collect until both the broadcast and the ack arrived (presence frames may come first).
            while (frames.none { it is Ack } || frames.none { it is HallMsg }) {
                val f = incoming.receive() as WsFrame.Text
                frames += VesperJson.decodeFromString(Frame.serializer(), f.readText())
            }
            assertTrue(frames.any { it is HallMsg && (it as HallMsg).msg.body.text == hallSecret }, "Expected HallMsg broadcast")
            val ack = frames.firstOrNull { it is Ack && (it as Ack).cmid == cmid } as? Ack
            assertNotNull(ack, "Expected Ack frame")
            assertNotNull(ack.seq)
        }

        // Verify At-Rest Encryption in MongoDB: Raw document in `hall_messages` MUST NOT contain plaintext!
        val hallDocs = db.hall.find().toList()
        assertEquals(1, hallDocs.size)
        val doc = hallDocs[0]
        assertFalse(doc.toJson().contains(hallSecret)) // Encrypted by Google Tink!
        assertNotNull(doc.getString("enc"))

        // User B reads Hall via REST pagination
        val (pageStatus, pageBody) = getJson("/v1/hall?afterSeq=0&limit=50", token = authB.accessToken)
        assertEquals(HttpStatusCode.OK, pageStatus)
        val page = VesperJson.decodeFromString(HallPage.serializer(), pageBody)
        assertEquals(1, page.items.size)
        assertEquals(hallSecret, page.items[0].body.text)
        assertEquals(authA.user.username, page.items[0].senderName)

        // Presence check: GET /v1/online returns count
        val (onlineStatus, onlineBody) = getJson("/v1/online", token = authB.accessToken)
        assertEquals(HttpStatusCode.OK, onlineStatus)
        val count = VesperJson.decodeFromString(OnlineCount.serializer(), onlineBody)
        assertTrue(count.count >= 0)
    }

    @Test
    fun testEncryptedChatBackupLifecycle() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val user = createDevice("backup_tester", 501)
        val (status, authJson) = postJson("/v1/auth/signup", AuthRequest("backup_tester", "Pass-Backup-2026!", user.registrationDto), ip = "10.0.5.1")
        assertEquals(HttpStatusCode.OK, status, authJson)
        val auth = VesperJson.decodeFromString(AuthResponse.serializer(), authJson)

        // 1. Initial backup key is empty
        val (keyStatus, keyBody) = getJson("/v1/backup/key", token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, keyStatus)
        val initialKey = VesperJson.decodeFromString(BackupKey.serializer(), keyBody)
        assertEquals("", initialKey.salt)

        // 2. Set backup key via PUT then overwrite via POST
        val (setKeyStatus, _) = putJson("/v1/backup/key", BackupKey("c2FsdDEyMzQ1Njc4", 65536, "a2V5aGFzaDEyMzQ1Njc4"), token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, setKeyStatus)
        val (postKeyStatus, _) = postJson("/v1/backup/key", BackupKey("c2FsdFBPU1QxMjM0", 65536, "cG9zdEtleUhhc2gxMjM="), token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, postKeyStatus)

        // 3. Upload encrypted backup item via PUT and POST (future expiresAt)
        val futureExpiry = System.currentTimeMillis() + 86400_000L
        val uploadPut = BackupItems(listOf(BackupItem("d:msg-1", "YWVzLWNpcGhlcnRleHQtZGF0YQ==", futureExpiry)))
        val (uploadPutStatus, _) = putJson("/v1/backup/items", uploadPut, token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, uploadPutStatus)

        val uploadPost = BackupItems(listOf(BackupItem("d:msg-2", "cG9zdC1jaXBoZXJ0ZXh0LWRhdGE=", futureExpiry)))
        val (uploadPostStatus, _) = postJson("/v1/backup/items", uploadPost, token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, uploadPostStatus)

        // 4. List backup items on fresh device
        val (listStatus, listBody) = getJson("/v1/backup/items", token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, listStatus)
        val list = VesperJson.decodeFromString(BackupItems.serializer(), listBody)
        assertEquals(2, list.items.size)
        assertEquals("d:msg-1", list.items[0].id)
        assertEquals("YWVzLWNpcGhlcnRleHQtZGF0YQ==", list.items[0].blob)
    }

    @Test
    fun testAdminAuthorizationAndSecurityBoundaries() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val normalUser = createDevice("regular_user", 601)
        val (status, normalJson) = postJson("/v1/auth/signup", AuthRequest("regular_user", "Pass-Regular-2026!", normalUser.registrationDto), ip = "10.0.6.1")
        assertEquals(HttpStatusCode.OK, status, normalJson)
        val normalAuth = VesperJson.decodeFromString(AuthResponse.serializer(), normalJson)

        val (adminStatus, adminJson) = postJson("/v1/auth/signin", AuthRequest("vesperadmin", adminPw, normalUser.registrationDto), ip = "10.0.6.2")
        assertEquals(HttpStatusCode.OK, adminStatus, adminJson)
        val adminAuth = VesperJson.decodeFromString(AuthResponse.serializer(), adminJson)

        val adminPaths = listOf("/v1/admin/overview", "/v1/admin/members", "/v1/admin/conversations", "/v1/admin/activity")

        // Regular users must receive 403 Forbidden on all admin endpoints
        for (path in adminPaths) {
            val (stat, _) = getJson(path, token = normalAuth.accessToken)
            assertEquals(HttpStatusCode.Forbidden, stat, "Expected 403 on $path for non-admin")
        }

        // Unauthenticated requests must receive 401 Unauthorized
        for (path in adminPaths) {
            val (stat, _) = getJson(path, token = null)
            assertEquals(HttpStatusCode.Unauthorized, stat, "Expected 401 on $path without token")
        }

        // Admin succeeds on admin endpoints
        val (overviewStatus, overviewBody) = getJson("/v1/admin/overview", token = adminAuth.accessToken)
        assertEquals(HttpStatusCode.OK, overviewStatus)
        val overview = VesperJson.decodeFromString(AdminOverview.serializer(), overviewBody)
        assertTrue(overview.registered >= 1)
    }
}
