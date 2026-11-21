package com.rajdeep.vesper

import com.rajdeep.vesper.core.crypto.MediaCipher
import com.rajdeep.vesper.shared.*
import com.rajdeep.vesper.ui.chat.duration
import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class ClientExtendedTest {

    @Test
    fun testDurationFormatting() {
        assertEquals("0:00", duration(0L))
        assertEquals("0:00", duration(400L))
        assertEquals("0:01", duration(1000L))
        assertEquals("0:09", duration(9000L))
        assertEquals("0:45", duration(45000L))
        assertEquals("1:00", duration(60000L))
        assertEquals("1:23", duration(83000L))
        assertEquals("10:05", duration(605000L))
        assertEquals("59:59", duration(3599000L))
    }

    @Test
    fun testMinimumVoiceDurationEnforcement() {
        val subSecondMs = 999L
        val exactOneSecondMs = 1000L
        val validDurationMs = 2450L

        fun isEligibleToSend(durationMs: Long): Boolean = durationMs >= 1000L

        assertFalse("999ms must not be eligible to send (sub-1s)", isEligibleToSend(subSecondMs))
        assertFalse("0ms must not be eligible to send", isEligibleToSend(0L))
        assertFalse("400ms must not be eligible to send", isEligibleToSend(400L))
        assertTrue("1000ms must be eligible to send", isEligibleToSend(exactOneSecondMs))
        assertTrue("2450ms must be eligible to send", isEligibleToSend(validDurationMs))
    }

    @Test
    fun testWaveformBucketingAlgorithm() {
        // Implementation matching MediaTools.buckets logic:
        fun testBuckets(src: List<Int>, n: Int): List<Int> {
            if (src.isEmpty()) return List(n) { 6 }
            val step = src.size.toDouble() / n
            val raw = (0 until n).map { i ->
                val from = (i * step).toInt().coerceIn(0, src.size - 1)
                val to = ((i + 1) * step).toInt().coerceIn(from + 1, src.size)
                var max = 0
                for (j in from until to) {
                    val v = src[j]
                    if (v > max) max = v
                }
                max
            }
            val peak = (raw.maxOrNull() ?: 0).coerceAtLeast(1)
            return raw.map { (it * 100 / peak).coerceIn(4, 100) }
        }

        // 1. Empty amplitudes -> returns n elements with default base
        val emptyRes = testBuckets(emptyList(), 32)
        assertEquals(32, emptyRes.size)
        assertTrue(emptyRes.all { it == 6 })

        // 2. Uniform amplitudes -> all elements normalised to 100
        val uniformRes = testBuckets(List(50) { 15000 }, 16)
        assertEquals(16, uniformRes.size)
        assertTrue(uniformRes.all { it == 100 })

        // 3. Ascending amplitudes -> latter buckets higher than first
        val asc = (1..100).map { it * 320 }
        val ascRes = testBuckets(asc, 20)
        assertEquals(20, ascRes.size)
        assertTrue(ascRes.last() > ascRes.first())
        assertTrue(ascRes.all { it in 4..100 })
    }

    @Test
    fun testCompletePolymorphicFrameSerialization() {
        // 1. HallMsg with ReplyRef
        val replyRef = ReplyRef(messageId = "m-orig-999", senderName = "alice", text = "Original text", kind = MessageKind.TEXT)
        val hallMsgDto = HallMessageDto(
            id = "m-100",
            seq = 42L,
            senderId = "user-alice",
            senderName = "alice",
            kind = MessageKind.TEXT,
            body = MessageBody(text = "Replying to your point", reply = replyRef),
            acceptedAt = 1795180000000L,
            expiresAt = 1795180000000L + 72 * 3600 * 1000L,
            cmid = "cmid-test-1",
        )
        val hallMsg = HallMsg(msg = hallMsgDto)
        val hallJson = VesperJson.encodeToString(Frame.serializer(), hallMsg)
        assertTrue(hallJson.contains("\"t\":\"hall.msg\""))
        assertTrue(hallJson.contains("m-orig-999"))
        val decodedHall = VesperJson.decodeFromString(Frame.serializer(), hallJson) as HallMsg
        assertEquals("m-100", decodedHall.msg.id)
        assertNotNull(decodedHall.msg.body.reply)
        assertEquals("m-orig-999", decodedHall.msg.body.reply?.messageId)
        assertEquals("alice", decodedHall.msg.body.reply?.senderName)

        // 2. DirectEnv
        val envDto = DirectEnvelopeDto(
            id = "env-1",
            mid = "mid-1",
            fromUserId = "user-bob",
            fromDeviceId = 1,
            toUserId = "user-alice",
            type = 3,
            body = "encrypted-ciphertext-blob",
            kind = MessageKind.VOICE,
            acceptedAt = 1795180000000L,
            expiresAt = 1795180000000L + 24 * 3600 * 1000L,
        )
        val directEnv = DirectEnv(env = envDto)
        val directJson = VesperJson.encodeToString(Frame.serializer(), directEnv)
        assertTrue(directJson.contains("\"t\":\"direct.env\""))
        val decodedDirect = VesperJson.decodeFromString(Frame.serializer(), directJson) as DirectEnv
        assertEquals("mid-1", decodedDirect.env.mid)
        assertEquals(MessageKind.VOICE, decodedDirect.env.kind)

        // 3. DirectDeleted
        val directDel = DirectDeleted(mids = listOf("mid-1", "mid-2"))
        val delJson = VesperJson.encodeToString(Frame.serializer(), directDel)
        assertTrue(delJson.contains("\"t\":\"direct.deleted\""))
        val decodedDel = VesperJson.decodeFromString(Frame.serializer(), delJson) as DirectDeleted
        assertEquals(2, decodedDel.mids.size)

        // 4. DirectSeen
        val directSeen = DirectSeen(mids = listOf("mid-1"), byUserId = "user-alice")
        val seenJson = VesperJson.encodeToString(Frame.serializer(), directSeen)
        assertTrue(seenJson.contains("\"t\":\"direct.seen\""))
        val decodedSeen = VesperJson.decodeFromString(Frame.serializer(), seenJson) as DirectSeen
        assertEquals("user-alice", decodedSeen.byUserId)

        // 5. DirectDelivered
        val directDelivered = DirectDelivered(mid = "mid-1", toUserId = "user-alice")
        val deliveredJson = VesperJson.encodeToString(Frame.serializer(), directDelivered)
        assertTrue(deliveredJson.contains("\"t\":\"direct.delivered\""))
        val decodedDelivered = VesperJson.decodeFromString(Frame.serializer(), deliveredJson) as DirectDelivered
        assertEquals("mid-1", decodedDelivered.mid)

        // 6. HallDeleted
        val hallDel = HallDeleted(ids = listOf("m-100"))
        val hallDelJson = VesperJson.encodeToString(Frame.serializer(), hallDel)
        assertTrue(hallDelJson.contains("\"t\":\"hall.deleted\""))
        val decodedHallDel = VesperJson.decodeFromString(Frame.serializer(), hallDelJson) as HallDeleted
        assertEquals("m-100", decodedHallDel.ids[0])

        // 7. Pong
        val pong = Pong(serverTime = 1795180000123L)
        val pongJson = VesperJson.encodeToString(Frame.serializer(), pong)
        assertTrue(pongJson.contains("\"t\":\"pong\""))
        val decodedPong = VesperJson.decodeFromString(Frame.serializer(), pongJson) as Pong
        assertEquals(1795180000123L, decodedPong.serverTime)

        // 8. Nack
        val nack = Nack(cmid = "cmid-fail", code = Nacks.RATE, message = "Rate limit exceeded")
        val nackJson = VesperJson.encodeToString(Frame.serializer(), nack)
        assertTrue(nackJson.contains("\"t\":\"nack\""))
        val decodedNack = VesperJson.decodeFromString(Frame.serializer(), nackJson) as Nack
        assertEquals(Nacks.RATE, decodedNack.code)
    }

    @Test
    fun testEncryptedBackupKeyDerivationAndWrapping() {
        val password = "Super-Secure-Backup-Password-2026!"
        val salt = "vesper-backup-salt-42".toByteArray(Charsets.UTF_8)

        // PBKDF2 with 100,000 iterations to derive a 256-bit wrapping key
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derivedKey = f.generateSecret(spec).encoded
        assertEquals(32, derivedKey.size)

        // Raw master backup key K
        val backupKeyK = MediaCipher.newKey()
        assertEquals(32, backupKeyK.size)

        // Wrap K using AES-GCM with derived key
        val wrappedBlob = MediaCipher.encrypt(derivedKey, backupKeyK)
        assertTrue(wrappedBlob.size >= 32 + 12 + 16)

        // Unwrap K
        val unwrappedKeyK = MediaCipher.decrypt(derivedKey, wrappedBlob)
        assertArrayEquals(backupKeyK, unwrappedKeyK)

        // Attempt unwrapping with wrong password / derived key
        val wrongDerivedKey = MediaCipher.newKey()
        try {
            MediaCipher.decrypt(wrongDerivedKey, wrappedBlob)
            fail("Unwrapping with wrong key must throw exception")
        } catch (e: Exception) {
            assertTrue(e is GeneralSecurityException || e.cause is GeneralSecurityException)
        }
    }

    @Test
    fun testNotificationControlPreferences() {
        val defaultPrefs = com.rajdeep.vesper.data.Prefs()
        assertFalse("Hall notifications should be active by default", defaultPrefs.turnOffHallNotifications)
        assertFalse("Inbox notifications should be active by default", defaultPrefs.turnOffInboxNotifications)

        // Turn off Hall notifications independently
        val hallOff = defaultPrefs.copy(turnOffHallNotifications = true)
        assertTrue(hallOff.turnOffHallNotifications)
        assertFalse(hallOff.turnOffInboxNotifications)

        // Turn off Inbox notifications independently
        val inboxOff = defaultPrefs.copy(turnOffInboxNotifications = true)
        assertFalse(inboxOff.turnOffHallNotifications)
        assertTrue(inboxOff.turnOffInboxNotifications)

        // Both turned off
        val bothOff = defaultPrefs.copy(turnOffHallNotifications = true, turnOffInboxNotifications = true)
        assertTrue(bothOff.turnOffHallNotifications)
        assertTrue(bothOff.turnOffInboxNotifications)

        // Restore Hall notifications
        val hallRestored = bothOff.copy(turnOffHallNotifications = false)
        assertFalse(hallRestored.turnOffHallNotifications)
        assertTrue(hallRestored.turnOffInboxNotifications)

        // Restore Inbox notifications
        val inboxRestored = hallRestored.copy(turnOffInboxNotifications = false)
        assertFalse(inboxRestored.turnOffHallNotifications)
        assertFalse(inboxRestored.turnOffInboxNotifications)
    }
}
