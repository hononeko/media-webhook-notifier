package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemHealthServiceTest {
    private class FakeNotificationPublisher(
        private val shouldFail: Boolean = false
    ) : NotificationPublisherPort {
        override val providerId: String = "fake"
        val sentCards = Collections.synchronizedList(mutableListOf<NotificationCard>())
        val directSends = Collections.synchronizedList(mutableListOf<Pair<NotificationCard, List<String>>>())
        var unreachable: Set<String> = emptySet()

        override suspend fun sendDirect(
            card: NotificationCard,
            recipients: List<String>
        ): Map<String, Either<DomainError.NotificationError, NotificationHandle>> {
            directSends.add(card to recipients)
            return recipients.associateWith {
                if (it in unreachable) {
                    Either.Left(DomainError.NotificationError.DeliveryFailed("fake", "Forbidden: bot can't initiate"))
                } else {
                    Either.Right(NotificationHandle("fake", it, "dm-$it"))
                }
            }
        }

        override suspend fun sendCard(
            card: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> =
            if (shouldFail) {
                Either.Left(DomainError.NotificationError.DeliveryFailed("fake", "Delivery error"))
            } else {
                sentCards.add(card)
                Either.Right(NotificationHandle("fake", "chat1", "msg1"))
            }

        override suspend fun startLiveProgress(
            initialCard: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> =
            Either.Right(NotificationHandle("fake", "chat1", "msg1"))

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

    @Test
    fun `should announce system health warnings successfully`() =
        runTest {
            val publisher = FakeNotificationPublisher()
            val service = SystemHealthService(publisher)

            val payload =
                MediaPayload.ServarrHealth(
                    source = AppSource.SONARR,
                    eventType = EventType.HEALTH_ISSUE,
                    level = "warning",
                    message = "Disk space below 10%",
                    type = "DiskSpace",
                    instanceName = "Sonarr-TV"
                )

            val result = service.announce(payload)
            assertTrue(result.isRight())
            assertEquals(1, publisher.sentCards.size)
            assertEquals("⚠️ Health Warning: Sonarr-TV", publisher.sentCards.first().title)
        }

    @Test
    fun `should return error when notification publisher fails`() =
        runTest {
            val publisher = FakeNotificationPublisher(shouldFail = true)
            val service = SystemHealthService(publisher)

            val payload =
                MediaPayload.ServarrHealth(
                    source = AppSource.RADARR,
                    eventType = EventType.HEALTH_ISSUE,
                    level = "error",
                    message = "qBittorrent connection refused"
                )

            val result = service.announce(payload)
            assertTrue(result.isLeft())
        }

    private val admins = listOf("1", "2")

    private fun health(
        level: String = "warning",
        type: EventType = EventType.HEALTH_ISSUE
    ) = MediaPayload.ServarrHealth(source = AppSource.SONARR, eventType = type, level = level, message = "Indexer down")

    @Test
    fun `health issue goes only to admins, with Acknowledge`() =
        runTest {
            val publisher = FakeNotificationPublisher()

            val result = SystemHealthService(publisher, admins).announce(health())

            assertTrue(result.isRight())
            assertEquals(emptyList(), publisher.sentCards, "the channel must not get health issues")
            val (card, recipients) = publisher.directSends.single()
            assertEquals(admins, recipients)
            assertEquals(listOf(CardFormatterService.ACKNOWLEDGE), card.interactions)
        }

    @Test
    fun `health restored goes only to admins, without buttons`() =
        runTest {
            val publisher = FakeNotificationPublisher()

            SystemHealthService(publisher, admins).announce(health(level = "ok", type = EventType.HEALTH_RESTORED))

            assertEquals(emptyList(), publisher.sentCards, "the channel must not get health cards")
            val (card, recipients) = publisher.directSends.single()
            assertEquals(admins, recipients)
            assertEquals(emptyList(), card.interactions)
        }

    @Test
    fun `health restored falls back to the channel when no admin can be reached`() =
        runTest {
            val publisher = FakeNotificationPublisher().apply { unreachable = admins.toSet() }

            SystemHealthService(publisher, admins).announce(health(level = "ok", type = EventType.HEALTH_RESTORED))

            assertEquals(emptyList(), publisher.sentCards.single().interactions)
        }

    @Test
    fun `without admins health restored goes to the channel as before`() =
        runTest {
            val publisher = FakeNotificationPublisher()

            SystemHealthService(publisher).announce(health(level = "ok", type = EventType.HEALTH_RESTORED))

            assertEquals(emptyList(), publisher.directSends)
            assertEquals(1, publisher.sentCards.size)
        }

    @Test
    fun `no reachable admin falls back to an informational channel card without buttons`() =
        runTest {
            val publisher = FakeNotificationPublisher().apply { unreachable = admins.toSet() }

            val result = SystemHealthService(publisher, admins).announce(health(level = "error"))

            assertTrue(result.isRight())
            assertEquals(emptyList(), publisher.sentCards.single().interactions)
        }

    @Test
    fun `without admins health issues go to the channel as before`() =
        runTest {
            val publisher = FakeNotificationPublisher()

            SystemHealthService(publisher).announce(health())

            assertEquals(emptyList(), publisher.directSends)
            assertEquals(listOf(CardFormatterService.ACKNOWLEDGE), publisher.sentCards.single().interactions)
        }

    private fun snoozes() =
        HealthSnoozes(
            app.hononeko.notifier.adapter.outbound.state
                .InMemoryStateStore()
        )

    @Test
    fun `admins' issue cards get Snooze next to Acknowledge, keyed on the check`() =
        runTest {
            val publisher = FakeNotificationPublisher()

            SystemHealthService(publisher, admins, snoozes()).announce(health())

            val check = HealthSnoozes.checkOf(health())
            assertEquals(
                listOf(CardFormatterService.ACKNOWLEDGE, SnoozeHandler.button(check)),
                publisher.directSends
                    .single()
                    .first.interactions
            )
        }

    @Test
    fun `restored cards, channel fallback and no-admin cards never carry Snooze`() =
        runTest {
            val restored = FakeNotificationPublisher()
            SystemHealthService(
                restored,
                admins,
                snoozes()
            ).announce(health(level = "ok", type = EventType.HEALTH_RESTORED))
            assertEquals(
                emptyList(),
                restored.directSends
                    .single()
                    .first.interactions
            )

            val unreachable = FakeNotificationPublisher().apply { unreachable = admins.toSet() }
            SystemHealthService(unreachable, admins, snoozes()).announce(health())
            assertEquals(emptyList(), unreachable.sentCards.single().interactions)

            val noAdmins = FakeNotificationPublisher()
            SystemHealthService(noAdmins, emptyList(), snoozes()).announce(health())
            assertEquals(listOf(CardFormatterService.ACKNOWLEDGE), noAdmins.sentCards.single().interactions)
        }

    @Test
    fun `a snoozed check drops issue and restored cards, other checks still arrive`() =
        runTest {
            val publisher = FakeNotificationPublisher()
            val snoozes = snoozes()
            snoozes.snooze(HealthSnoozes.checkOf(health()), "@alex")
            val service = SystemHealthService(publisher, admins, snoozes)

            assertTrue(service.announce(health()).isRight())
            assertTrue(service.announce(health(level = "ok", type = EventType.HEALTH_RESTORED)).isRight())
            assertEquals(emptyList(), publisher.directSends)
            assertEquals(emptyList(), publisher.sentCards)

            service.announce(health().copy(type = "DownloadClientCheck"))
            assertEquals(1, publisher.directSends.size)
        }
}
