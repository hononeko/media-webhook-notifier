package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaAvailableUseCase
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
            val announcedPayloads = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val trackUseCase =
                TrackDownloadUseCase { _, grab ->
                    trackedGrabs.add(grab)
                    Either.Right(Unit)
                }

            val announceUseCase =
                AnnounceMediaAvailableUseCase { payload ->
                    announcedPayloads.add(payload)
                    Either.Right(Unit)
                }

            val service =
                IngestWebhookService(
                    seasonDebouncer = null,
                    trackDownloadUseCase = trackUseCase,
                    announceMediaAvailableUseCase = announceUseCase
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
            assertEquals(0, announcedPayloads.size)
        }

    @Test
    fun `should dispatch ArrDownload and Plex events to AnnounceMediaAvailableUseCase`() =
        runTest {
            val trackedGrabs = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())
            val announcedPayloads = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val trackUseCase =
                TrackDownloadUseCase { _, grab ->
                    trackedGrabs.add(grab)
                    Either.Right(Unit)
                }

            val announceUseCase =
                AnnounceMediaAvailableUseCase { payload ->
                    announcedPayloads.add(payload)
                    Either.Right(Unit)
                }

            val service =
                IngestWebhookService(
                    seasonDebouncer = null,
                    trackDownloadUseCase = trackUseCase,
                    announceMediaAvailableUseCase = announceUseCase
                )

            val download =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Severance S02E01",
                    seriesOrMovieTitle = "Severance"
                )

            val plex =
                MediaPayload.PlexLibraryNew(
                    title = "Severance"
                )

            service.execute(download)
            service.execute(plex)

            assertEquals(0, trackedGrabs.size)
            assertEquals(2, announcedPayloads.size)
        }
}
