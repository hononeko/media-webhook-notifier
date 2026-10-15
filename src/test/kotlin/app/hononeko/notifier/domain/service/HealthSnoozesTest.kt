package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HealthSnoozesTest {
    private var now = 1_760_000_000_000L
    private val snoozes = HealthSnoozes(InMemoryStateStore()) { now }

    private fun health(
        type: String? = "IndexerStatusCheck",
        instance: String? = "Sonarr-TV",
        source: AppSource = AppSource.SONARR,
        message: String = "Indexers unavailable",
        event: EventType = EventType.HEALTH_ISSUE
    ) = MediaPayload.ServarrHealth(
        source = source,
        eventType = event,
        level = "warning",
        message = message,
        type = type,
        instanceName = instance
    )

    @Test
    fun `a check is source, instance and type, so issue and restored share it`() {
        assertEquals("SONARR|Sonarr-TV|IndexerStatusCheck", HealthSnoozes.checkOf(health()))
        assertEquals(
            HealthSnoozes.checkOf(health()),
            HealthSnoozes.checkOf(health(message = "Indexers available again", event = EventType.HEALTH_RESTORED))
        )
    }

    @Test
    fun `other instances, sources and types are different checks`() {
        val base = HealthSnoozes.checkOf(health())
        assertNotEquals(base, HealthSnoozes.checkOf(health(instance = "Sonarr-Anime")))
        assertNotEquals(base, HealthSnoozes.checkOf(health(source = AppSource.RADARR)))
        assertNotEquals(base, HealthSnoozes.checkOf(health(type = "DownloadClientCheck")))
    }

    @Test
    fun `without a type the message identifies the check`() {
        assertEquals("SONARR|Sonarr-TV|Disk full", HealthSnoozes.checkOf(health(type = " ", message = "Disk full")))
    }

    @Test
    fun `snooze lasts 24h and reports when it ends`() =
        runTest {
            val until = snoozes.snooze("c", "@alex")

            assertEquals(now + 86_400_000L, until)
            assertTrue(snoozes.isSnoozed("c"))
            now += 86_400_000L
            assertFalse(snoozes.isSnoozed("c"))
        }

    @Test
    fun `unsnooze ends it early and says whether it was still active`() =
        runTest {
            snoozes.snooze("c", "@alex")

            assertTrue(snoozes.unsnooze("c"))
            assertFalse(snoozes.isSnoozed("c"))
            assertFalse(snoozes.unsnooze("c"))
        }

    @Test
    fun `unsnooze after expiry reports it had already ended`() =
        runTest {
            snoozes.snooze("c", "@alex")
            now += 86_400_001L

            assertFalse(snoozes.unsnooze("c"))
        }
}
