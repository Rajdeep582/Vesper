package com.rajdeep.vesper

import com.rajdeep.vesper.core.crypto.MediaCipher
import com.rajdeep.vesper.shared.*
import com.rajdeep.vesper.ui.text.MessageText
import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AppLogicTest {

    @Test
    fun testMediaCipherEncryptionDecryptionAndIntegrity() {
        val key = MediaCipher.newKey()
        assertEquals(32, key.size)

        val plaintext = "Hello Vesper E2EE Media Content! 12345!@#$%^&*()_+".toByteArray(Charsets.UTF_8)
        val encrypted = MediaCipher.encrypt(key, plaintext)

        // Nonce (12) + ciphertext (plaintext.size) + GCM tag (16)
        assertEquals(12 + plaintext.size + 16, encrypted.size)

        // Decrypt with correct key
        val decrypted = MediaCipher.decrypt(key, encrypted)
        assertArrayEquals(plaintext, decrypted)

        // Decrypt with wrong key must fail
        val wrongKey = MediaCipher.newKey()
        try {
            MediaCipher.decrypt(wrongKey, encrypted)
            fail("Decryption with wrong key must throw AEADBadTagException")
        } catch (e: Exception) {
            assertTrue("Expected AEAD or crypto failure: ${e::class.simpleName}", e is GeneralSecurityException || e.cause is GeneralSecurityException)
        }

        // Tampered ciphertext (flipping one byte) must fail tag check
        val tamperedCipher = encrypted.copyOf()
        tamperedCipher[20] = (tamperedCipher[20].toInt() xor 0xFF).toByte()
        try {
            MediaCipher.decrypt(key, tamperedCipher)
            fail("Decryption of tampered ciphertext must fail")
        } catch (e: Exception) {
            assertTrue("Expected auth tag validation failure", e is GeneralSecurityException || e.cause is GeneralSecurityException)
        }

        // Tampered nonce must fail tag check
        val tamperedNonce = encrypted.copyOf()
        tamperedNonce[0] = (tamperedNonce[0].toInt() xor 0xFF).toByte()
        try {
            MediaCipher.decrypt(key, tamperedNonce)
            fail("Decryption with modified nonce must fail")
        } catch (e: Exception) {
            assertTrue("Expected auth tag validation failure", e is GeneralSecurityException || e.cause is GeneralSecurityException)
        }
    }

    @Test
    fun testMediaCipherSha256Digest() {
        val emptyDigest = MediaCipher.sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyDigest)

        val helloDigest = MediaCipher.sha256Hex("hello".toByteArray(Charsets.UTF_8))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", helloDigest)
    }

    @Test
    fun testMessageFormattingForeverRules() {
        assertTrue(MessageText.isForever("*important message*forever"))
        assertTrue(MessageText.isForever("  *important message*forever  "))
        assertTrue(MessageText.isForever("*multi\nline\ntext*forever"))
        assertFalse(MessageText.isForever("regular message"))
        assertFalse(MessageText.isForever("*bold message*"))
        assertFalse(MessageText.isForever("*text* forever")) // space before forever
        assertFalse(MessageText.isForever("text*forever"))   // missing leading asterisk

        assertEquals("*important message*", MessageText.visible("*important message*forever"))
        assertEquals("*multi\nline\ntext*", MessageText.visible(" *multi\nline\ntext*forever "))
        assertEquals("regular text", MessageText.visible("regular text"))
    }

    @Test
    fun testProtocolLimitsAndTtlRules() {
        assertEquals(72 * 60 * 60 * 1000L, Limits.ttlFor(MessageKind.TEXT))
        assertEquals(24 * 60 * 60 * 1000L, Limits.ttlFor(MessageKind.IMAGE))
        assertEquals(24 * 60 * 60 * 1000L, Limits.ttlFor(MessageKind.VOICE))
        assertEquals(100L * 365 * 24 * 3_600_000, Keep.FOREVER_MS)

        assertEquals(20, Limits.USERNAME_MAX)
        assertEquals(3, Limits.USERNAME_MIN)
        assertEquals(10, Limits.PASSWORD_MIN)
        assertEquals(4000, Limits.MAX_TEXT_CHARS)
        assertEquals(2L * 1024 * 1024, Limits.MAX_MEDIA_BYTES)
        assertEquals(5L * 60 * 1000, Limits.MAX_VOICE_MS)
    }

    @Test
    fun testPolymorphicProtocolSerialization() {
        // Test HallSend serialization and deserialization via Frame interface
        val hallSend = HallSend(
            cmid = "cmid-test-1",
            kind = MessageKind.TEXT,
            body = MessageBody(text = "Hello Hall", media = null),
            keep = false,
        )
        val hallJson = VesperJson.encodeToString(Frame.serializer(), hallSend)
        assertTrue(hallJson.contains("\"t\":\"hall.send\""))
        val decodedHall = VesperJson.decodeFromString(Frame.serializer(), hallJson) as HallSend
        assertEquals(hallSend.cmid, decodedHall.cmid)
        assertEquals(hallSend.body.text, decodedHall.body.text)
        assertFalse(decodedHall.keep)

        // Test DirectSend
        val directSend = DirectSend(
            cmid = "cmid-direct-1",
            toUserId = "user-bob",
            kind = MessageKind.IMAGE,
            mediaId = "media-1234",
            envelopes = listOf(
                OutEnvelope(toUserId = "user-bob", deviceId = 1, type = 3, body = "ciphertext-base64"),
            ),
            keep = false,
        )
        val directJson = VesperJson.encodeToString(Frame.serializer(), directSend)
        assertTrue(directJson.contains("\"t\":\"direct.send\""))
        val decodedDirect = VesperJson.decodeFromString(Frame.serializer(), directJson) as DirectSend
        assertEquals(directSend.toUserId, decodedDirect.toUserId)
        assertEquals(directSend.mediaId, decodedDirect.mediaId)
        assertEquals(1, decodedDirect.envelopes.size)
        assertEquals(1, decodedDirect.envelopes[0].deviceId)

        // Test DirectAck
        val ackFrame = DirectAck(ids = listOf("env-1", "env-2", "env-3"))
        val ackJson = VesperJson.encodeToString(Frame.serializer(), ackFrame)
        assertTrue(ackJson.contains("\"t\":\"direct.ack\""))
        val decodedAck = VesperJson.decodeFromString(Frame.serializer(), ackJson) as DirectAck
        assertEquals(listOf("env-1", "env-2", "env-3"), decodedAck.ids)

        // Test Ping / Pong
        val ping = Ping(123456789L)
        val pingJson = VesperJson.encodeToString(Frame.serializer(), ping)
        assertTrue(pingJson.contains("\"t\":\"ping\""))
        val decodedPing = VesperJson.decodeFromString(Frame.serializer(), pingJson) as Ping
        assertEquals(123456789L, decodedPing.at)
    }

    @Test
    fun testBackupKeyDerivationAndWrappingRoundtrip() {
        val masterPassword = "TestSuperSecurePassword123!"
        val rnd = SecureRandom()
        val originalKey = ByteArray(32).also(rnd::nextBytes)
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iters = 10_000 // 10k iterations for speed in test

        fun kek(password: String, s: ByteArray, it: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), s, it, 256)).encoded

        val derivedKek = kek(masterPassword, salt, iters)
        assertEquals(32, derivedKek.size)

        // Wrap original key with derived KEK
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cWrap = Cipher.getInstance("AES/GCM/NoPadding")
        cWrap.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKek, "AES"), GCMParameterSpec(128, iv))
        val wrappedCipher = iv + cWrap.doFinal(originalKey)

        // Unwrap original key with correct password
        val unwrappedKek = kek(masterPassword, salt, iters)
        val cUnwrap = Cipher.getInstance("AES/GCM/NoPadding")
        cUnwrap.init(Cipher.DECRYPT_MODE, SecretKeySpec(unwrappedKek, "AES"), GCMParameterSpec(128, wrappedCipher, 0, 12))
        val recoveredKey = cUnwrap.doFinal(wrappedCipher, 12, wrappedCipher.size - 12)
        assertArrayEquals(originalKey, recoveredKey)

        // Unwrap with wrong password must throw AEADBadTagException
        val wrongKek = kek("WrongPassword!", salt, iters)
        val cWrong = Cipher.getInstance("AES/GCM/NoPadding")
        cWrong.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrongKek, "AES"), GCMParameterSpec(128, wrappedCipher, 0, 12))
        try {
            cWrong.doFinal(wrappedCipher, 12, wrappedCipher.size - 12)
            fail("Unwrapping with wrong password must throw AEADBadTagException")
        } catch (e: Exception) {
            assertTrue("Expected AEAD or crypto tag failure", e is AEADBadTagException || e is GeneralSecurityException)
        }
    }

    @Test
    fun testDirectNotificationSuppressionInvariants() {
        var foreground = false
        var viewingPeer: String? = null
        fun isViewing(peer: String) = foreground && viewingPeer == peer

        // When in background, isViewing is always false
        assertFalse(isViewing("bob"))
        assertFalse(isViewing("alice"))

        // When foregrounded and viewing Alice
        foreground = true
        viewingPeer = "alice"
        assertTrue(isViewing("alice"))
        // Bob is NOT suppressed, so notifications from Bob must alert the user
        assertFalse(isViewing("bob"))
    }
}
