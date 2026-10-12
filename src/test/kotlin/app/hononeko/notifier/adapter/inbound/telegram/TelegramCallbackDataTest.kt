package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.inbound.telegram.TelegramCallbackData.Decoded
import app.hononeko.notifier.adapter.inbound.telegram.TelegramCallbackData.decode
import app.hononeko.notifier.adapter.inbound.telegram.TelegramCallbackData.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramCallbackDataTest {
    @Test
    fun `round trips`() {
        assertEquals("mwn1:pause:k3Zq9xT2bA", encode("pause", "k3Zq9xT2bA"))
        assertEquals(Decoded("pause", "k3Zq9xT2bA"), decode(encode("pause", "k3Zq9xT2bA")))
    }

    @Test
    fun `encode refuses payloads over 64 bytes`() {
        assertNull(encode("a".repeat(50), "b".repeat(20)))
    }

    @Test
    fun `encode refuses invalid operation or ref`() {
        for ((op, ref) in listOf(
            "" to "abc",
            "Pause" to "abc",
            "pa:use" to "abc",
            "pause" to "",
            "pause" to "a:b",
            "pause" to "ab-c"
        )) {
            assertNull(encode(op, ref), "$op / $ref")
        }
    }

    @Test
    fun `decode rejects foreign or malformed data`() {
        for (raw in listOf(
            null,
            "",
            "dl:abc:pause",
            "mwn1:pause",
            "mwn1:pause:",
            "mwn1:pause:abc:extra",
            "mwn1:Pause:abc",
            "mwn1:pause:ab-c",
            "mwn1:pause:äbc"
        )) {
            assertNull(decode(raw), raw)
        }
    }
}
