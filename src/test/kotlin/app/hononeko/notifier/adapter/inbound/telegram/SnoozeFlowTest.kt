package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.inbound.telegram.TelegramFlow.Companion.NOW
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.service.AcknowledgeHandler
import app.hononeko.notifier.domain.service.HealthSnoozes
import app.hononeko.notifier.domain.service.SnoozeHandler
import app.hononeko.notifier.domain.service.SystemHealthService
import app.hononeko.notifier.domain.service.UnsnoozeHandler
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A health issue reaches two admins with Acknowledge + Snooze. One admin snoozes it: both copies are stamped and keep
 * only Unsnooze, and repeats of that check (issue and restored) are dropped. The other admin unsnoozes it, and the
 * next issue is delivered again.
 */
class SnoozeFlowTest {
    private fun issue() =
        MediaPayload.ServarrHealth(
            source = AppSource.SONARR,
            level = "warning",
            message = "All indexers are unavailable",
            type = "IndexerStatusCheck",
            instanceName = "Sonarr-TV"
        )

    @Test
    fun `admins snooze a check, repeats are dropped, unsnooze brings it back`(): Unit =
        runBlocking {
            lateinit var snoozes: HealthSnoozes
            TelegramFlow(adminUsers = setOf(1L, 2L)) {
                snoozes = HealthSnoozes(store) { NOW }
                listOf(
                    AcknowledgeHandler(publisher, registry),
                    SnoozeHandler(snoozes, publisher, registry, timeZone = TimeZone.UTC),
                    UnsnoozeHandler(snoozes, publisher, registry, { NOW + 60_000 }, TimeZone.UTC)
                )
            }.use { telegram ->
                val health =
                    SystemHealthService(telegram.publisher, adminRecipients = listOf("1", "2"), snoozes = snoozes)

                health.announce(issue())
                val sends = telegram.calls("sendMessage")
                assertEquals(listOf("1", "2"), sends.map { it.text("chat_id") })
                assertEquals(listOf("✅ Acknowledge", "🔕 Snooze 24h"), sends[0].buttons.keys.toList())

                assertEquals(
                    "🔕 Snoozed for 24h",
                    telegram.press(sends[0].buttons.getValue("🔕 Snooze 24h"), userId = 1, messageId = 0)
                )

                val snoozedEdits = telegram.calls("editMessageText")
                assertEquals(setOf("1", "2"), snoozedEdits.map { it.text("chat_id") }.toSet())
                snoozedEdits.forEach { edit ->
                    assertTrue(
                        edit.text("text").endsWith("🔕 Snoozed until tomorrow 08:53 by @user1"),
                        edit.body.toString()
                    )
                    assertEquals(listOf("🔔 Unsnooze"), edit.buttons.keys.toList())
                }

                health.announce(issue())
                health.announce(
                    issue().copy(eventType = EventType.HEALTH_RESTORED, level = "ok", message = "Indexers available")
                )
                assertEquals(2, telegram.calls("sendMessage").size, "snoozed check must not send anything")

                // The other admin unsnoozes from their own copy: both copies must still share the ref.
                val unsnooze = snoozedEdits.single { it.text("chat_id") == "2" }.buttons.getValue("🔔 Unsnooze")
                assertEquals("🔔 Unsnoozed", telegram.press(unsnooze, userId = 2, messageId = 1))

                val unsnoozedEdits = telegram.calls("editMessageText").drop(snoozedEdits.size)
                assertEquals(setOf("1", "2"), unsnoozedEdits.map { it.text("chat_id") }.toSet())
                unsnoozedEdits.forEach {
                    assertTrue(
                        it.text("text").endsWith("🔔 Unsnoozed by @user2 · 08:54"),
                        it.body.toString()
                    )
                }

                health.announce(issue())
                assertEquals(
                    4,
                    telegram.calls("sendMessage").size,
                    "after unsnooze the check reaches both admins again"
                )
            }
        }
}
