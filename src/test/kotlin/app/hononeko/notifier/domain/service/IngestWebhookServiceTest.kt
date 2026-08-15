package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaAvailableUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaImportedUseCase
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import arrow.core.Either
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestWebhookServiceTest {
    @Test
    fun `should dispatch ArrGrab to TrackDownloadUseCase`() =
        runTest {
            val trackedGrabs = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())
            val importedPayloads = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())
            val availablePayloads = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val trackUseCase =
                TrackDownloadUseCase { _, grab ->
                    trackedGrabs.add(grab)
                    Either.Right(Unit)
                }

            val importUseCase =
                AnnounceMediaImportedUseCase { payload ->
                    importedPayloads.add(payload)
                    Either.Right(Unit)
                }

            val availableUseCase =
                AnnounceMediaAvailableUseCase { payload ->
                    availablePayloads.add(payload)
                    Either.Right(Unit)
                }

            val service =
                IngestWebhookService(
                    seasonDebouncer = null,
                    trackDownloadUseCase = trackUseCase,
                    announceMediaImportedUseCase = importUseCase,
                    announceMediaAvailableUseCase = availableUseCase
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.RADARR,
                    downloadId = "hash123",
                    title = "Dune 2",
                    seriesOrMovieTitle = "Dune 2"
                )

            val result = service.execute(grab)
            assertTrue(result.isRight())
            assertEquals(1, trackedGrabs.size)
            assertEquals(0, importedPayloads.size)
            assertEquals(0, availablePayloads.size)
        }

    @Test
    fun `should dispatch ArrDownload to AnnounceMediaImportedUseCase and Plex to AnnounceMediaAvailableUseCase`() =
        runTest {
            val trackedGrabs = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())
            val importedPayloads = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())
            val availablePayloads = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val trackUseCase =
                TrackDownloadUseCase { _, grab ->
                    trackedGrabs.add(grab)
                    Either.Right(Unit)
                }

            val importUseCase =
                AnnounceMediaImportedUseCase { payload ->
                    importedPayloads.add(payload)
                    Either.Right(Unit)
                }

            val availableUseCase =
                AnnounceMediaAvailableUseCase { payload ->
                    availablePayloads.add(payload)
                    Either.Right(Unit)
                }

            val service =
                IngestWebhookService(
                    seasonDebouncer = null,
                    trackDownloadUseCase = trackUseCase,
                    announceMediaImportedUseCase = importUseCase,
                    announceMediaAvailableUseCase = availableUseCase
                )

            val download =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Severance S02E01",
                    seriesOrMovieTitle = "Severance",
                    isUpgrade = false
                )

            val upgrade =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Severance S02E01",
                    seriesOrMovieTitle = "Severance",
                    isUpgrade = true
                )

            val plex =
                MediaPayload.PlexLibraryNew(
                    title = "Severance"
                )

            service.execute(download)
            service.execute(upgrade)
            service.execute(plex)

            assertEquals(0, trackedGrabs.size)
            assertEquals(2, importedPayloads.size)
            assertEquals(1, availablePayloads.size)
        }

    @Test
    fun `should route ServarrHealth and ServarrManualInteraction to alert use cases`() =
        runTest {
            val healthPayloads = Collections.synchronizedList(mutableListOf<MediaPayload.ServarrHealth>())
            val manualPayloads = Collections.synchronizedList(mutableListOf<MediaPayload.ServarrManualInteraction>())

            val service =
                IngestWebhookService(
                    seasonDebouncer = null,
                    trackDownloadUseCase = { _, _ -> Either.Right(Unit) },
                    announceMediaImportedUseCase = { Either.Right(Unit) },
                    announceMediaAvailableUseCase = { Either.Right(Unit) },
                    announceSystemHealthUseCase = { payload ->
                        healthPayloads.add(payload)
                        Either.Right(Unit)
                    },
                    announceManualInteractionUseCase = { payload ->
                        manualPayloads.add(payload)
                        Either.Right(Unit)
                    }
                )

            val health =
                MediaPayload.ServarrHealth(
                    source = AppSource.SONARR,
                    level = "warning",
                    message = "Indexer offline"
                )

            val manual =
                MediaPayload.ServarrManualInteraction(
                    source = AppSource.RADARR,
                    title = "Dune 2",
                    seriesOrMovieTitle = "Dune 2"
                )

            val healthResult = service.execute(health)
            val manualResult = service.execute(manual)

            assertTrue(healthResult.isRight())
            assertTrue(manualResult.isRight())
            assertEquals(1, healthPayloads.size)
            assertEquals(1, manualPayloads.size)
        }

    @Test
    fun `should route SeerrEvent to AnnounceMediaRequestUseCase`() =
        runTest {
            val seerrPayloads = Collections.synchronizedList(mutableListOf<MediaPayload.SeerrEvent>())

            val service =
                IngestWebhookService(
                    seasonDebouncer = null,
                    trackDownloadUseCase = { _, _ -> Either.Right(Unit) },
                    announceMediaImportedUseCase = { Either.Right(Unit) },
                    announceMediaAvailableUseCase = { Either.Right(Unit) },
                    announceMediaRequestUseCase = { payload ->
                        seerrPayloads.add(payload)
                        Either.Right(Unit)
                    }
                )

            val seerrEvent =
                MediaPayload.SeerrEvent(
                    source = AppSource.SEERR,
                    eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_PENDING,
                    notificationType = "MEDIA_PENDING",
                    subject = "Dune 2",
                    requestedByUsername = "Admin"
                )

            val result = service.execute(seerrEvent)
            assertTrue(result.isRight())
            assertEquals(1, seerrPayloads.size)
            assertEquals("Admin", seerrPayloads.first().requestedByUsername)
        }
}
