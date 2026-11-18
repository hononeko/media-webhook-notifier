package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.EventTemplate
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.model.TemplateConfig
import app.hononeko.notifier.domain.port.outbound.LockRenewal
import app.hononeko.notifier.domain.port.outbound.MediaServerHealthPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import app.hononeko.notifier.domain.port.outbound.resolveDestination
import app.hononeko.notifier.testing.RecordingPublisher
import arrow.core.Either
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pinned media server status message (MWN-36). Checks run every 30 s with a 90 s grace period. */
class MediaServerStatusMonitorTest {
    // 2026-11-18 14:00:00 UTC
    private val start = 1_795_010_400_000L
    private var now = start
    private var healthy = true
    private var checks = 0
    private val store = InMemoryStateStore()
    private val publisher = StatusPublisher()

    private val unreachable =
        DomainError.MediaServerError.Unreachable(
            "http://plex:32400/identity",
            "Connection refused"
        )

    private fun monitor(
        owner: String = "pod-a",
        stateStore: StateStorePort = store,
        refreshSeconds: Long = 300,
        recoverySeconds: Long = 3600,
        pinEnabled: Boolean = true,
        graceSeconds: Long = 90,
        engine: () -> TemplateEngine = { TemplateEngine() }
    ) = MediaServerStatusMonitor(
        healthCheck =
            MediaServerHealthPort {
                checks++
                if (healthy) Either.Right(Unit) else Either.Left(unreachable)
            },
        publisher = publisher,
        stateStore = stateStore,
        config =
            MediaServerStatusConfig(
                serverName = "Kerrlab Plex",
                sourceName = "Plex",
                ownerId = owner,
                pollIntervalSeconds = 30,
                graceSeconds = graceSeconds,
                refreshSeconds = refreshSeconds,
                recoverySeconds = recoverySeconds,
                pinEnabled = pinEnabled,
                zone = ZoneOffset.UTC
            ),
        clock = { now },
        engine = engine
    )

    /** Runs [count] checks, 30 s apart, the first one at the current time. */
    private suspend fun MediaServerStatusMonitor.checks(count: Int) {
        repeat(count) {
            if (it > 0) now += 30_000
            tick()
        }
    }

    @Test
    fun `the first healthy check sends and pins one silent online message`() =
        runTest {
            monitor().tick()

            val card = publisher.sent.single()
            assertEquals("🟢 Kerrlab Plex — Online", card.title)
            assertTrue(card.silent)
            assertEquals(listOf("msg1" to true), publisher.pinned)
            // Before any outage there is no "back up" line.
            assertEquals("▪ <b>Last checked:</b> 14:00", card.customBody)
        }

    @Test
    fun `a blip shorter than the grace period changes nothing`() =
        runTest {
            val monitor = monitor()
            monitor.tick()
            healthy = false
            monitor.checks(3) // 0 s, 30 s, 60 s of failures
            healthy = true
            now += 30_000
            monitor.tick()

            assertEquals(1, publisher.sent.size)
            assertTrue(publisher.edits.isEmpty())
        }

    @Test
    fun `an outage past the grace period edits the message to offline`() =
        runTest {
            val monitor = monitor()
            monitor.tick()
            now += 30_000
            healthy = false
            monitor.checks(4) // the 4th check is 90 s after the first failure

            assertEquals(1, publisher.sent.size)
            val offline = publisher.edits.single()
            assertEquals("msg1", offline.first.messageReferenceId)
            assertEquals("🔴 Kerrlab Plex — Offline", offline.second.title)
            assertEquals(
                "▪ <b>Down since:</b> 14:00\n▪ <b>Downtime:</b> 1m 30s\n▪ <b>Error:</b> Connection refused\n" +
                    "▪ <b>Last checked:</b> 14:02",
                offline.second.customBody
            )
        }

    @Test
    fun `recovery replaces and re-pins the message audibly with the downtime`() =
        runTest {
            val monitor = monitor()
            monitor.tick()
            healthy = false
            now += 10_000 // first failure at 14:00:10
            monitor.checks(4)
            now += 168_000 // 14:04:28
            healthy = true
            monitor.tick()

            val online = publisher.sent.last()
            assertEquals("🟢 Kerrlab Plex — Online", online.title)
            assertFalse(online.silent)
            assertEquals(
                "▪ <b>Back up at:</b> 14:04 (downtime: 4m 18s)\n▪ <b>Last checked:</b> 14:04",
                online.customBody
            )
            assertEquals(2, publisher.sent.size)
            assertEquals(listOf("msg1"), publisher.deleted.map { it.messageReferenceId })
            assertEquals(listOf("msg1" to true, "msg2" to true), publisher.pinned)
        }

    @Test
    fun `recovery edits in place silently when status_online template sets silent true`() =
        runTest {
            val silentEngine =
                TemplateEngine(
                    TemplateConfig(
                        events = mapOf(MediaServerStatusCards.ONLINE_EVENT to EventTemplate(silent = true))
                    )
                )
            val monitor = monitor(engine = { silentEngine })
            monitor.tick()
            healthy = false
            now += 10_000
            monitor.checks(4)
            now += 168_000
            healthy = true
            monitor.tick()

            assertEquals(1, publisher.sent.size)
            assertTrue(publisher.deleted.isEmpty())
            val edited = publisher.edits.last().second
            assertEquals("🟢 Kerrlab Plex — Online", edited.title)
            assertTrue(edited.silent)
        }

    @Test
    fun `after the recovery window expires the back-up banner is edited away in place`() =
        runTest {
            val monitor = monitor(refreshSeconds = 300, recoverySeconds = 360)
            monitor.tick()
            healthy = false
            now += 10_000
            monitor.checks(4)
            now += 168_000 // 14:04:28 recovery
            healthy = true
            monitor.tick()
            assertEquals(2, publisher.sent.size)
            val editsBeforeExpiry = publisher.edits.size

            // 5 minutes later: periodic refresh still keeps the "Back up at" line
            now += 300_000
            monitor.tick()
            assertEquals(editsBeforeExpiry + 1, publisher.edits.size)
            assertTrue(
                publisher.edits
                    .last()
                    .second.customBody!!
                    .contains("Back up at")
            )

            // 1 minute later (total 6 min == recoverySeconds, 4 min before next refresh):
            // immediately edits the same message in place to strip the back-up banner.
            now += 60_000
            monitor.tick()
            assertEquals(2, publisher.sent.size)
            assertEquals(editsBeforeExpiry + 2, publisher.edits.size)
            val cleared = publisher.edits.last().second
            assertEquals("▪ <b>Last checked:</b> 14:10", cleared.customBody)
        }

    @Test
    fun `when recoverySeconds is zero recovery alerts cleanly without a back-up banner`() =
        runTest {
            val monitor = monitor(recoverySeconds = 0)
            monitor.tick()
            healthy = false
            now += 10_000
            monitor.checks(4)
            now += 168_000
            healthy = true
            monitor.tick()

            val online = publisher.sent.last()
            assertFalse(online.silent)
            assertEquals("▪ <b>Last checked:</b> 14:04", online.customBody)
        }

    @Test
    fun `an unchanged status is only refreshed every refresh interval`() =
        runTest {
            val monitor = monitor(refreshSeconds = 300)
            monitor.tick()
            monitor.checks(10) // 270 s later: not due yet
            assertTrue(publisher.edits.isEmpty())

            now += 30_000
            monitor.tick()
            assertEquals(1, publisher.edits.size)
        }

    @Test
    fun `a deleted message is replaced by a new pinned one`() =
        runTest {
            val monitor = monitor()
            monitor.tick()
            publisher.messageGone = true
            healthy = false
            monitor.checks(4)

            assertEquals(2, publisher.sent.size)
            assertEquals("🔴 Kerrlab Plex — Offline", publisher.sent.last().title)
            assertEquals(listOf("msg1", "msg2"), publisher.pinned.map { it.first })
        }

    @Test
    fun `a failed edit of a change is retried on the next check`() =
        runTest {
            val monitor = monitor()
            monitor.tick()
            healthy = false
            publisher.editFailures = 1
            monitor.checks(5) // goes offline at the 4th check, whose edit fails

            assertEquals(1, publisher.edits.size)
            assertEquals(
                "🔴 Kerrlab Plex — Offline",
                publisher.edits
                    .single()
                    .second.title
            )
        }

    @Test
    fun `a restarted instance keeps editing the same message`() =
        runTest {
            monitor(owner = "pod-a").tick()
            now += 30_000
            healthy = false
            // pod-a is gone; its lock lapses and pod-b picks up the stored message.
            val podB = monitor(owner = "pod-b")
            now += 70_000
            podB.checks(4)

            assertEquals(1, publisher.sent.size)
            assertEquals(
                "msg1",
                publisher.edits
                    .single()
                    .first.messageReferenceId
            )
        }

    @Test
    fun `only the instance holding the lock checks the server`() =
        runTest {
            val podA = monitor(owner = "pod-a")
            val podB = monitor(owner = "pod-b")
            podA.tick()
            podB.tick()

            assertEquals(1, publisher.sent.size)
            podA.stop()
            podB.tick()
            assertEquals(1, publisher.sent.size)
            assertTrue(publisher.edits.isEmpty(), "pod-b keeps the stored message instead of sending a new one")
        }

    @Test
    fun `the first verdict waits out the grace period when the server starts down`() =
        runTest {
            healthy = false
            val monitor = monitor()
            monitor.checks(3)
            assertTrue(publisher.sent.isEmpty())

            now += 30_000
            monitor.tick()
            assertEquals("🔴 Kerrlab Plex — Offline", publisher.sent.single().title)
        }

    @Test
    fun `failures before a long gap don't count toward the grace period`() =
        runTest {
            val monitor = monitor()
            monitor.tick()
            healthy = false
            monitor.checks(2)
            now += 600_000 // this instance was away for 10 minutes
            monitor.tick()

            // Only the periodic refresh of the online card.
            assertTrue(publisher.edits.all { it.second.title.contains("Online") })
        }

    @Test
    fun `a pin that isn't allowed still keeps the message up to date`() =
        runTest {
            publisher.pinFails = true
            val monitor = monitor()
            monitor.tick()
            healthy = false
            monitor.checks(4)

            assertEquals(1, publisher.sent.size)
            assertEquals(1, publisher.edits.size)
        }

    @Test
    fun `a message in a chat that is no longer configured is replaced`() =
        runTest {
            monitor().tick()
            publisher.chat = "new-chat"
            now += 30_000
            monitor().tick()

            assertEquals(2, publisher.sent.size)
            assertTrue(publisher.edits.isEmpty())
        }

    @Test
    fun `a grace period no longer than the poll interval still turns the status offline`() =
        runTest {
            val monitor = monitor(graceSeconds = 30)
            monitor.tick()
            healthy = false
            // Checks land a poll interval apart plus their latency.
            repeat(3) {
                now += 31_000
                monitor.tick()
            }
            assertEquals(
                "🔴 Kerrlab Plex — Offline",
                publisher.edits
                    .lastOrNull()
                    ?.second
                    ?.title
            )
        }

    @Test
    fun `turning pinning on pins the existing message at its next update`() =
        runTest {
            monitor(pinEnabled = false).tick()
            assertTrue(publisher.pinned.isEmpty())

            healthy = false
            val pinning = monitor(pinEnabled = true)
            pinning.checks(4)
            assertEquals(listOf("msg1" to true), publisher.pinned)

            now += 300_000
            pinning.tick()
            assertEquals(1, publisher.pinned.size, "pinned once, not on every update")
        }

    @Test
    fun `pinning can be turned off`() =
        runTest {
            monitor(pinEnabled = false).tick()
            assertEquals(1, publisher.sent.size)
            assertTrue(publisher.pinned.isEmpty())
        }

    @Test
    fun `the state survives in the store`() =
        runTest {
            val monitor = monitor()
            monitor.tick()
            healthy = false
            monitor.checks(4)

            val stored = store.get(MediaServerStatusMonitor.STATE_KEY)
            assertTrue(stored != null && stored.contains("\"status\":\"DOWN\"") && stored.contains("msg1"), stored)
        }

    @Test
    fun `start polls on its interval and stop hands the lock over`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val podA = monitor(owner = "pod-a")
            podA.start(scope)
            try {
                scope.advanceTimeBy(1)
                assertEquals(1, checks)
                scope.advanceTimeBy(30_000)
                assertEquals(2, checks)
            } finally {
                podA.stop()
            }
            assertEquals(LockRenewal.RENEWED, store.renewLock(MediaServerStatusMonitor.LOCK_KEY, "pod-b", 90, now))
        }

    @Test
    fun `a check that throws doesn't stop the polling`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            var calls = 0
            val flaky =
                MediaServerStatusMonitor(
                    healthCheck = {
                        calls++
                        if (calls == 1) error("boom") else Either.Right(Unit)
                    },
                    publisher = publisher,
                    stateStore = store,
                    config = MediaServerStatusConfig("Plex", "Plex", ownerId = "pod-a", zone = ZoneOffset.UTC),
                    clock = { now },
                    engine = { TemplateEngine() }
                )
            flaky.start(scope)
            try {
                scope.advanceTimeBy(30_001)
            } finally {
                flaky.stop()
            }
            assertEquals(2, calls)
            assertEquals(1, publisher.sent.size)
        }

    @Test
    fun `a failed send is tried again on the next check`() =
        runTest {
            publisher.sendFails = true
            val monitor = monitor()
            monitor.tick()
            assertTrue(publisher.sent.isEmpty())

            publisher.sendFails = false
            now += 30_000
            monitor.tick()
            assertEquals(1, publisher.sent.size)
        }

    @Test
    fun `unreadable stored state starts over`() =
        runTest {
            store.set(MediaServerStatusMonitor.STATE_KEY, "{not json")
            monitor().tick()
            assertEquals(1, publisher.sent.size)
        }

    @Test
    fun `publishers can't pin unless they say so`() =
        runTest {
            val result = RecordingPublisher().pinMessage(NotificationHandle("fake", "chat", "1"))
            assertTrue(result.isLeft())
        }

    @Test
    fun `an HTTP error shows its status`() {
        val monitor = monitor()
        val unhealthy = DomainError.MediaServerError.Unhealthy("http://plex:32400/identity", 503)
        assertEquals("HTTP 503", monitor.advance(MediaServerStatusState(), Either.Left(unhealthy), start).lastError)
    }

    @Test
    fun `advance moves through up, failing, down and back up`() {
        val monitor = monitor()
        val up = monitor.advance(MediaServerStatusState(), Either.Right(Unit), start)
        assertEquals(ServerStatus.UP, up.status)
        assertEquals(start, up.since)

        val failing = monitor.advance(up, Either.Left(unreachable), start + 30_000)
        assertEquals(ServerStatus.UP, failing.status)
        assertEquals(start + 30_000, failing.firstFailedAt)

        val down = monitor.advance(failing, Either.Left(unreachable), start + 120_000)
        assertEquals(ServerStatus.DOWN, down.status)
        assertEquals(start + 30_000, down.since)

        val back = monitor.advance(down, Either.Right(Unit), start + 300_000)
        assertEquals(ServerStatus.UP, back.status)
        assertEquals(270_000L, back.lastDowntimeMillis)
        assertNull(back.firstFailedAt)
        assertNull(back.lastError)
    }

    @Test
    fun `changing the status template chat_id or topic_id sends and pins a new status message`() =
        runTest {
            var currentEngine = TemplateEngine()
            val monitor = monitor(engine = { currentEngine })

            monitor.tick()
            assertEquals(1, publisher.sent.size)
            assertEquals("chat", publisher.sentHandles.single().channelOrChatId)
            assertNull(publisher.sentHandles.single().topicId)

            // Same destination skips unnecessary edits
            now += 30_000
            monitor.tick()
            assertEquals(1, publisher.sent.size)
            assertTrue(publisher.edits.isEmpty())

            // Overriding topic_id replaces the pinned message even within the same chat
            currentEngine =
                TemplateEngine(
                    TemplateConfig(
                        events = mapOf(MediaServerStatusCards.ONLINE_EVENT to EventTemplate(topicId = 42L))
                    )
                )
            now += 30_000
            monitor.tick()
            assertEquals(2, publisher.sent.size)
            assertEquals("chat", publisher.sentHandles[1].channelOrChatId)
            assertEquals(42L, publisher.sentHandles[1].topicId)
            assertEquals(listOf("msg1" to true, "msg2" to true), publisher.pinned)

            // Overriding chat_id without topic_id replaces again in the new chat with topicId = null
            currentEngine =
                TemplateEngine(
                    TemplateConfig(
                        events = mapOf(MediaServerStatusCards.ONLINE_EVENT to EventTemplate(chatId = "-1004451040571"))
                    )
                )
            now += 30_000
            monitor.tick()
            assertEquals(3, publisher.sent.size)
            assertEquals("-1004451040571", publisher.sentHandles[2].channelOrChatId)
            assertNull(publisher.sentHandles[2].topicId)
            assertEquals(listOf("msg1" to true, "msg2" to true, "msg3" to true), publisher.pinned)
        }

    /** Records sends, edits and pins; message ids count up from msg1. */
    private class StatusPublisher : NotificationPublisherPort {
        override val providerId = "fake"
        var chat = "chat"
        var topic: Long? = null
        override val defaultChannelOrChatId: String get() = chat
        override val defaultTopicId: Long? get() = topic
        val sent = CopyOnWriteArrayList<NotificationCard>()
        val sentHandles = CopyOnWriteArrayList<NotificationHandle>()
        val edits = CopyOnWriteArrayList<Pair<NotificationHandle, NotificationCard>>()
        val pinned = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val deleted = CopyOnWriteArrayList<NotificationHandle>()
        var messageGone = false
        var editFailures = 0
        var pinFails = false
        var sendFails = false

        override suspend fun sendCard(
            card: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> {
            if (sendFails) return Either.Left(DomainError.NotificationError.RateLimited(providerId, 30))
            val dest = resolveDestination(card.chatId, card.topicId)
            sent += card
            messageGone = false
            val handle = NotificationHandle(providerId, dest.chatId, "msg${sent.size}", topicId = dest.topicId)
            sentHandles += handle
            return Either.Right(handle)
        }

        override suspend fun editCard(
            handle: NotificationHandle,
            card: NotificationCard,
            callbackRef: String?
        ): Either<DomainError.NotificationError, Unit> {
            if (messageGone) return Either.Left(DomainError.NotificationError.MessageNotFound(providerId, "gone"))
            if (editFailures > 0) {
                editFailures--
                return Either.Left(DomainError.NotificationError.RateLimited(providerId, 5))
            }
            edits += handle to card
            return Either.Right(Unit)
        }

        override suspend fun pinMessage(
            handle: NotificationHandle,
            silent: Boolean
        ): Either<DomainError.NotificationError, Unit> {
            if (pinFails) {
                return Either.Left(
                    DomainError.NotificationError.DeliveryFailed(providerId, "not enough rights")
                )
            }
            pinned += handle.messageReferenceId to silent
            return Either.Right(Unit)
        }

        override suspend fun deleteMessage(handle: NotificationHandle): Either<DomainError.NotificationError, Unit> {
            deleted += handle
            return Either.Right(Unit)
        }

        override suspend fun startLiveProgress(initialCard: NotificationCard) = sendCard(initialCard)

        override suspend fun updateProgress(
            handle: NotificationHandle,
            update: ProgressUpdate
        ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)

        override suspend fun completeProgress(
            handle: NotificationHandle,
            finalCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)

        override suspend fun cancelProgress(
            handle: NotificationHandle,
            reasonCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)
    }
}
