package com.rajdeep.vesper

import com.rajdeep.vesper.core.db.MessageEntity
import com.rajdeep.vesper.data.Mentions
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.Pins
import com.rajdeep.vesper.shared.PinRequest
import com.rajdeep.vesper.shared.PinList
import com.rajdeep.vesper.shared.PinChanged
import com.rajdeep.vesper.shared.PinDto
import com.rajdeep.vesper.shared.Frame
import com.rajdeep.vesper.shared.VesperJson
import com.rajdeep.vesper.ui.chat.pinLeft
import com.rajdeep.vesper.ui.chat.pinPreview
import org.junit.Assert.*
import org.junit.Test

/** Mentions, pins and their wire formats (pure logic, JVM). */
class FeatureLogicTest {
    @Test fun mentionHitsByNameCaseInsensitive() {
        assertTrue(Mentions.hits("hey @VTest_RB look", "vtest_rb"))
        assertFalse(Mentions.hits("hey @vtest_rb2 look", "vtest_rb"))
        assertFalse(Mentions.hits("mail vtest_rb@x.com", "vtest_rb"))
    }

    @Test fun mentionEveryone() {
        assertTrue(Mentions.hits("@all meeting now", "anyone"))
        assertTrue(Mentions.hits("@everyone", "anyone"))
        assertTrue(Mentions.everyone("ok @ALL."))
        assertFalse(Mentions.everyone("@allison hi"))
        assertFalse(Mentions.hits(null, "x"))
        assertFalse(Mentions.hits("", "x"))
    }

    @Test fun mentionNamesTrimTrailingDot() {
        assertEquals(setOf("rajdeep"), Mentions.names("thanks @rajdeep."))
        assertEquals(setOf("a.b", "c_d"), Mentions.names("@a.b,@c_d"))
        assertTrue(Mentions.names("just @ alone").isEmpty())
    }

    private fun msg(kind: MessageKind, text: String? = null) = MessageEntity(
        localId = "l", scope = "hall", senderId = "s", senderName = "n", kind = kind.name, text = text, state = 1, outgoing = false, createdAt = 0,
    )

    @Test fun pinPreviewPerKind() {
        assertEquals("hello world", pinPreview(msg(MessageKind.TEXT, "hello\nworld")))
        assertEquals("Photo", pinPreview(msg(MessageKind.IMAGE)))
        assertEquals("Voice message", pinPreview(msg(MessageKind.VOICE)))
        assertEquals("File", pinPreview(msg(MessageKind.FILE)))
    }

    @Test fun pinTimeLeftLabels() {
        val now = 1_000_000_000L
        assertEquals("7d left", pinLeft(now + 7 * Pins.DAY_MS, now))
        assertEquals("20h left", pinLeft(now + 20 * 3_600_000L, now))
        assertEquals("2d left", pinLeft(now + 36 * 3_600_000L, now))
        assertEquals("5h left", pinLeft(now + 5 * 3_600_000L + 120_000, now))
        assertEquals("1m left", pinLeft(now + 10_000, now))
        assertEquals("1m left", pinLeft(now - 10_000, now))
    }

    @Test fun pinDurationsAndLimits() {
        assertEquals(listOf(1, 3, 7), Pins.DAYS)
        assertEquals(3, Pins.MAX_PER_CHAT)
    }

    @Test fun pinWireFormatsRoundTrip() {
        val req = PinRequest("m1", "peer", 3)
        assertEquals(req, VesperJson.decodeFromString(PinRequest.serializer(), VesperJson.encodeToString(PinRequest.serializer(), req)))
        val list = PinList(direct = listOf(PinDto("m1", "p", 10, "u", "name", 5)))
        assertEquals(list, VesperJson.decodeFromString(PinList.serializer(), VesperJson.encodeToString(PinList.serializer(), list)))
        val f: Frame = PinChanged("m1", null, null)
        val json = VesperJson.encodeToString(Frame.serializer(), f)
        assertTrue(json.contains("\"t\":\"pin.changed\""))
        assertEquals(f, VesperJson.decodeFromString(Frame.serializer(), json))
        // Older servers / missing fields must still decode.
        assertEquals(PinList(), VesperJson.decodeFromString(PinList.serializer(), "{}"))
    }
}
