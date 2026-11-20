package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.*
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
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
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import kotlin.test.*

class ComprehensiveExtendedTest {
    private lateinit var mongo: MongoServer
    private val adminPw = "Admin-Secret-Pass-2026!"
    private val b64e = Base64.getEncoder()

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
            "MONGO_DB" to "vesper_extended_test",
            "JWT_SECRET" to "VesperJwtSecretKeyMustBeAtLeast48CharactersLongHereForTestingPurpose!",
            "HALL_DATA_KEYSET" to AtRest.generateKeysetB64(),
            "ADMIN_USERNAME" to "vesperadmin",
            "ADMIN_PASSWORD_ARGON2" to Passwords.hash(adminPw),
            "MEDIA_PROVIDER" to "local",
            "VESPER_DEV" to "true",
        ),
    )

    private fun createDevice(username: String, regId: Int = 1000): Pair<SimulatedDevice, DeviceRegistration> {
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
        return SimulatedDevice(username, id, regId, store, regDto) to regDto
    }

    private data class SimulatedDevice(
        val username: String,
        val identityKeyPair: IdentityKeyPair,
        val registrationId: Int,
        val store: StringKeyedStore,
        val registrationDto: DeviceRegistration,
    )

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String, token: String? = null, ip: String = "192.168.1.1"): Pair<HttpStatusCode, String> {
        val r = client.post(path) {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", ip)
            token?.let { header("Authorization", "Bearer $it") }
            setBody(body)
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
    fun testPasswordChangeAndAccountLifecycle() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val (charlie, regDto) = createDevice("charlie_test", 301)
        val initialPw = "Charlie-Strong-Pass-2026!"

        // 1. Sign up charlie
        val signupJson = VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest("charlie_test", initialPw, regDto))
        val (s1, b1) = postJson("/v1/auth/signup", signupJson, ip = "10.0.5.1")
        assertEquals(HttpStatusCode.OK, s1, b1)
        val auth1 = VesperJson.decodeFromString(AuthResponse.serializer(), b1)

        // 2. Change password with incorrect current password -> Bad Request
        val wrongChangeJson = VesperJson.encodeToString(PasswordChange.serializer(), PasswordChange("Wrong-Old-Pass!", "New-Strong-Pass-2026!", "charlie_test", null))
        val (s2, _) = postJson("/v1/me/password", wrongChangeJson, token = auth1.accessToken, ip = "10.0.5.2")
        assertEquals(HttpStatusCode.BadRequest, s2)

        // 3. Change password with weak new password -> Bad Request
        val weakChangeJson = VesperJson.encodeToString(PasswordChange.serializer(), PasswordChange(initialPw, "12345", "charlie_test", null))
        val (s3, _) = postJson("/v1/me/password", weakChangeJson, token = auth1.accessToken, ip = "10.0.5.3")
        assertEquals(HttpStatusCode.BadRequest, s3)

        // 4. Change password with valid credentials -> OK
        val nextPw = "Charlie-New-Strong-Pass-2026!"
        val validChangeJson = VesperJson.encodeToString(PasswordChange.serializer(), PasswordChange(initialPw, nextPw, "charlie_test", null))
        val (s4, _) = postJson("/v1/me/password", validChangeJson, token = auth1.accessToken, ip = "10.0.5.4")
        assertEquals(HttpStatusCode.OK, s4)

        // 5. Old password no longer works
        val oldSigninJson = VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest("charlie_test", initialPw, regDto))
        val (s5, _) = postJson("/v1/auth/signin", oldSigninJson, ip = "10.0.5.5")
        assertEquals(HttpStatusCode.Unauthorized, s5)

        // 6. New password works
        val newSigninJson = VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest("charlie_test", nextPw, regDto))
        val (s6, b6) = postJson("/v1/auth/signin", newSigninJson, ip = "10.0.5.6")
        assertEquals(HttpStatusCode.OK, s6)
        val auth2 = VesperJson.decodeFromString(AuthResponse.serializer(), b6)

        // 7. Delete account with wrong password -> Bad Request
        val wrongDeleteJson = VesperJson.encodeToString(PasswordConfirm.serializer(), PasswordConfirm("Wrong-Pass-Delete!"))
        val (s7, _) = postJson("/v1/me/delete", wrongDeleteJson, token = auth2.accessToken, ip = "10.0.5.7")
        assertEquals(HttpStatusCode.BadRequest, s7)

        // 8. Delete account with correct password -> OK
        val validDeleteJson = VesperJson.encodeToString(PasswordConfirm.serializer(), PasswordConfirm(nextPw))
        val (s8, _) = postJson("/v1/me/delete", validDeleteJson, token = auth2.accessToken, ip = "10.0.5.8")
        assertEquals(HttpStatusCode.OK, s8)

        // 9. Sign in after deletion fails
        val (s9, _) = postJson("/v1/auth/signin", newSigninJson, ip = "10.0.5.9")
        assertEquals(HttpStatusCode.Unauthorized, s9)
    }

    @Test
    fun testPeopleSearchAndProfiles() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val (_, regDan) = createDevice("dan_alpha", 401)
        val (_, regDave) = createDevice("dave_beta", 402)
        val pw = "Common-Strong-Pass-2026!"

        val (sDan, bDan) = postJson("/v1/auth/signup", VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest("dan_alpha", pw, regDan)), ip = "10.0.6.1")
        assertEquals(HttpStatusCode.OK, sDan)
        val authDan = VesperJson.decodeFromString(AuthResponse.serializer(), bDan)

        postJson("/v1/auth/signup", VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest("dave_beta", pw, regDave)), ip = "10.0.6.2")

        // 1. Search with matching prefix "da" -> returns both
        val (sSearch, bSearch) = getJson("/v1/users?query=da", token = authDan.accessToken)
        assertEquals(HttpStatusCode.OK, sSearch)
        val page = VesperJson.decodeFromString(UserPage.serializer(), bSearch)
        assertTrue(page.items.any { it.username == "dan_alpha" })
        assertTrue(page.items.any { it.username == "dave_beta" })

        // 2. Search with non-existent query -> returns empty
        val (sEmpty, bEmpty) = getJson("/v1/users?query=nonexistent_xyz", token = authDan.accessToken)
        assertEquals(HttpStatusCode.OK, sEmpty)
        val emptyPage = VesperJson.decodeFromString(UserPage.serializer(), bEmpty)
        assertTrue(emptyPage.items.isEmpty())

        // 3. Fetch user profile -> returns valid summary
        val (sProf, bProf) = getJson("/v1/users/${authDan.user.id}", token = authDan.accessToken)
        assertEquals(HttpStatusCode.OK, sProf)
        val profile = VesperJson.decodeFromString(UserSummary.serializer(), bProf)
        assertEquals("dan_alpha", profile.username)
        assertEquals(authDan.user.id, profile.id)

        // 4. Fetch invalid user profile -> 404
        val (sInvalid, _) = getJson("/v1/users/nonexistent-user-id", token = authDan.accessToken)
        assertEquals(HttpStatusCode.NotFound, sInvalid)
    }

    @Test
    fun testDevicesManagement() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val (_, reg1) = createDevice("multidevice_user", 501)
        val pw = "Multi-Device-Pass-2026!"

        val (s1, b1) = postJson("/v1/auth/signup", VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest("multidevice_user", pw, reg1)), ip = "10.0.7.1")
        assertEquals(HttpStatusCode.OK, s1)
        val auth = VesperJson.decodeFromString(AuthResponse.serializer(), b1)

        // 1. List devices -> contains deviceId 1
        val (sDevs, bDevs) = getJson("/v1/me/devices", token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, sDevs)
        val devs = VesperJson.decodeFromString(AdminDeviceList.serializer(), bDevs)
        assertEquals(1, devs.items.size)
        assertEquals(1, devs.items[0].deviceId)

        // 2. Sign out others (when only 1 device) succeeds
        val (sSignoutOthers, _) = postJson("/v1/me/devices/signout-others", "", token = auth.accessToken)
        assertEquals(HttpStatusCode.OK, sSignoutOthers)
    }

    @Test
    fun testMediaUploadValidationAndLimits() = testApplication {
        val c = cfg()
        val db = Db.connect(c)
        application { vesper(c, db, startJobs = false) }

        val (_, reg) = createDevice("media_user", 601)
        val (sAuth, bAuth) = postJson("/v1/auth/signup", VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest("media_user", "Media-User-Pass-2026!", reg)), ip = "10.0.8.1")
        assertEquals(HttpStatusCode.OK, sAuth)
        val auth = VesperJson.decodeFromString(AuthResponse.serializer(), bAuth)

        // 1. Unauthenticated media upload intent -> 401
        val dummyIntent = MediaIntentRequest(
            kind = MessageKind.IMAGE,
            scope = MediaScope.HALL,
            bytes = 1024,
            sha256 = "0".repeat(64)
        )
        val (sUnauth, _) = postJson("/v1/media/intents", VesperJson.encodeToString(MediaIntentRequest.serializer(), dummyIntent))
        assertEquals(HttpStatusCode.Unauthorized, sUnauth)

        // 2. Oversized payload (> MAX_MEDIA_BYTES) -> 400 Bad Request
        val oversizedIntent = MediaIntentRequest(
            kind = MessageKind.IMAGE,
            scope = MediaScope.HALL,
            bytes = Limits.MAX_MEDIA_BYTES + 1024L,
            sha256 = "0".repeat(64)
        )
        val (sOversized, _) = postJson("/v1/media/intents", VesperJson.encodeToString(MediaIntentRequest.serializer(), oversizedIntent), token = auth.accessToken)
        assertEquals(HttpStatusCode.BadRequest, sOversized)

        // 3. Kind/media mismatch (TEXT kind) -> 400 Bad Request
        val textIntent = MediaIntentRequest(
            kind = MessageKind.TEXT,
            scope = MediaScope.HALL,
            bytes = 1024,
            sha256 = "0".repeat(64)
        )
        val (sText, _) = postJson("/v1/media/intents", VesperJson.encodeToString(MediaIntentRequest.serializer(), textIntent), token = auth.accessToken)
        assertEquals(HttpStatusCode.BadRequest, sText)

        // 4. Invalid SHA256 digest -> 400 Bad Request
        val badDigestIntent = MediaIntentRequest(
            kind = MessageKind.IMAGE,
            scope = MediaScope.HALL,
            bytes = 1024,
            sha256 = "invalid-sha"
        )
        val (sBadDigest, _) = postJson("/v1/media/intents", VesperJson.encodeToString(MediaIntentRequest.serializer(), badDigestIntent), token = auth.accessToken)
        assertEquals(HttpStatusCode.BadRequest, sBadDigest)
    }
}
