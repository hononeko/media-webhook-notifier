package app.hononeko.notifier.adapter.inbound.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ExponentialBackoffTest {
    @Test
    fun `doubles up to max and resets`() {
        val backoff = ExponentialBackoff(initial = 1.seconds, max = 30.seconds)

        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds }, List(7) { backoff.next() })
        backoff.reset()
        assertEquals(1.seconds, backoff.next())
    }
}
