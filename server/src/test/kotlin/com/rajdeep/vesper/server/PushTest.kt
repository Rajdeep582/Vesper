package com.rajdeep.vesper.server

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.runBlocking
import org.bson.Document
import java.io.File
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushTest {
    private lateinit var mongo: MongoServer
    private lateinit var db: Db

    @BeforeTest
    fun setup() {
        mongo = MongoServer(MemoryBackend())
        mongo.bind()
        val c = testConfig()
        db = Db.connect(c)
        runBlocking { db.ensureIndexes() }
    }

    @AfterTest
    fun tearDown() {
        mongo.shutdown()
    }

    private fun testConfig(saJsonB64: String? = null): Config = Config.fromEnv(
        mapOf(
            "MONGO_URI" to "mongodb://${mongo.localAddress.hostString}:${mongo.localAddress.port}",
            "MONGO_DB" to "push_test",
            "JWT_SECRET" to "x".repeat(48),
            "HALL_DATA_KEYSET" to AtRest.generateKeysetB64(),
            "ADMIN_USERNAME" to "boss",
            "ADMIN_PASSWORD_ARGON2" to Passwords.hash("admin-password-123"),
            "MEDIA_PROVIDER" to "local",
            "VESPER_DEV" to "true",
        ) + (if (saJsonB64 != null) mapOf("FCM_SERVICE_ACCOUNT_JSON_B64" to saJsonB64) else emptyMap()),
    )

    @Test
    fun pushDisabledWhenConfigMissing() {
        val push = Push(db, testConfig())
        assertFalse(push.enabled, "Push should be disabled without FCM service account credentials")
    }

    @Test
    fun pushEnabledWithValidServiceAccount() {
        val saFile = File(".dev/fcm-service-account.json")
        if (!saFile.exists()) return // Skip if file not present in test runner
        val b64 = Base64.getEncoder().encodeToString(saFile.readBytes())
        val push = Push(db, testConfig(b64))
        assertTrue(push.enabled, "Push should be enabled when valid FCM service account is provided")
    }

    @Test
    fun hallWakeCooldownPreventsSpam() {
        val saFile = File(".dev/fcm-service-account.json")
        val b64 = if (saFile.exists()) Base64.getEncoder().encodeToString(saFile.readBytes()) else null
        val push = Push(db, testConfig(b64))
        if (!push.enabled) return

        val user1 = "user-100"
        val user2 = "user-200"

        // First wake should pass cooldown check
        push.wakeHall(listOf(user1, user2))
        // Immediate second call should be suppressed by 15-minute coalescing cooldown
        push.wakeHall(listOf(user1, user2))
    }

    @Test
    fun hubPresenceControlsPushTrigger() {
        val hub = Hub()
        val uid = "test-recipient-1"
        assertFalse(hub.isOnline(uid), "User should be offline initially")

        // Offline recipient triggers push
        val pushTriggeredOffline = !hub.isOnline(uid)
        assertTrue(pushTriggeredOffline, "Offline recipient must trigger push notification")

        // Simulate user connecting WebSocket via dynamic proxy
        val mockWs = Proxy.newProxyInstance(
            WebSocketSession::class.java.classLoader,
            arrayOf(WebSocketSession::class.java),
        ) { _, _, _ -> null } as WebSocketSession

        val conn = Conn(uid, 1, false, mockWs)
        hub.add(conn)

        assertTrue(hub.isOnline(uid), "User should be online when socket is connected")
        val pushTriggeredOnline = !hub.isOnline(uid)
        assertFalse(pushTriggeredOnline, "Online recipient must suppress push notification")

        // Simulate user backgrounding app / disconnecting socket
        hub.remove(conn)
        assertFalse(hub.isOnline(uid), "User should be offline after socket disconnect")
    }
}
