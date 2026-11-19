package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.AuthRequest
import com.rajdeep.vesper.shared.AuthResponse
import com.rajdeep.vesper.shared.DeviceRegistration
import com.rajdeep.vesper.shared.KyberPreKeyDto
import com.rajdeep.vesper.shared.PreKeyDto
import com.rajdeep.vesper.shared.SignedPreKeyDto
import com.rajdeep.vesper.shared.VesperJson
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private lateinit var mongo: MongoServer
    private val adminPw = "admin-password-for-tests"

    @BeforeTest fun start() { mongo = MongoServer(MemoryBackend()); mongo.bind() }
    @AfterTest fun stop() { mongo.shutdown() }

    private fun cfg() = Config.fromEnv(
        mapOf(
            "MONGO_URI" to "mongodb://${mongo.localAddress.hostString}:${mongo.localAddress.port}",
            "MONGO_DB" to "t",
            "JWT_SECRET" to "x".repeat(48),
            "HALL_DATA_KEYSET" to AtRest.generateKeysetB64(),
            "ADMIN_USERNAME" to "boss",
            "ADMIN_PASSWORD_ARGON2" to Passwords.hash(adminPw),
            "MEDIA_PROVIDER" to "local",
            "VESPER_DEV" to "true",
        ),
    )

    private fun device() = DeviceRegistration(
        42, "aWs=", SignedPreKeyDto(1, "c3Br", "c2ln"), KyberPreKeyDto(1, "a3k=", "c2ln"),
        listOf(PreKeyDto(1, "cGs=")), emptyList(),
    )

    private suspend fun ApplicationTestBuilder.auth(path: String, u: String, p: String): Pair<HttpStatusCode, String> {
        val r = client.post(path) { contentType(ContentType.Application.Json); setBody(VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest(u, p, device()))) }
        return r.status to r.bodyAsText()
    }

    @Test
    fun adminRoutesRequireAdminAndPrivateContentIsNeverExposed() = testApplication {
        val c = cfg()
        application { vesper(c, Db.connect(c), startJobs = false) }
        val (s1, b1) = auth("/v1/auth/signup", "member1", "a-long-password-1")
        assertEquals(HttpStatusCode.OK, s1, b1)
        val member = VesperJson.decodeFromString(AuthResponse.serializer(), b1)
        assertEquals(false, member.user.isAdmin)

        // Members get 403 on every admin route.
        listOf("/v1/admin/overview", "/v1/admin/members", "/v1/admin/conversations", "/v1/admin/activity").forEach {
            assertEquals(HttpStatusCode.Forbidden, client.get(it) { header("Authorization", "Bearer ${member.accessToken}") }.status, it)
        }
        // Signing up with the admin name is impossible; admin signs in with the seeded hash.
        assertEquals(HttpStatusCode.BadRequest, auth("/v1/auth/signup", "boss", "a-long-password-2").first)
        val (s2, b2) = auth("/v1/auth/signin", "boss", adminPw)
        assertEquals(HttpStatusCode.OK, s2, b2)
        val admin = VesperJson.decodeFromString(AuthResponse.serializer(), b2)
        assertTrue(admin.user.isAdmin)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/overview") { header("Authorization", "Bearer ${admin.accessToken}") }.status)
        // No admin route can reach envelope bodies; only the addressed device can fetch its pending envelopes.
        val pending = client.get("/v1/direct/pending") { header("Authorization", "Bearer ${admin.accessToken}") }.bodyAsText()
        assertTrue(pending.contains("\"items\":[]"))
        // Wrong password is rejected; common passwords are rejected at sign-up.
        assertEquals(HttpStatusCode.Unauthorized, auth("/v1/auth/signin", "member1", "wrong-password-xx").first)
        assertEquals(HttpStatusCode.BadRequest, auth("/v1/auth/signup", "member2", "password123").first)
    }
}
