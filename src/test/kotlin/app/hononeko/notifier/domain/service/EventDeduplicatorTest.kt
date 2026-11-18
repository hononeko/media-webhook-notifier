package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventDeduplicatorTest {
    @Test
    fun `deduplicates identical grab retries across replicas while keeping multi-episode grabs distinct`() =
        runTest {
            val store = InMemoryStateStore()
            var now = 1_000_000L
            val podA = EventDeduplicator(store, ttlSeconds = 3600L, clock = { now })
            val podB = EventDeduplicator(store, ttlSeconds = 3600L, clock = { now })

            val grabEp1 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "HASH_ABC",
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1),
                    instanceName = "Sonarr-TV"
                )
            val grabEp2SameHash = grabEp1.copy(title = "Severance - S02E02", episodeNumbers = listOf(2))

            assertTrue(podA.tryAcquire(grabEp1))
            assertFalse(podB.tryAcquire(grabEp1.copy(downloadId = "hash_abc")), "retry on pod B must be dropped")
            assertTrue(podB.tryAcquire(grabEp2SameHash), "different episode sharing downloadId must pass")

            podA.release(grabEp1)
            assertTrue(podB.tryAcquire(grabEp1), "released key allows retry")
        }

    @Test
    fun `deduplicates imports with downloadId and allows imports without downloadId`() =
        runTest {
            val store = InMemoryStateStore()
            val dedup = EventDeduplicator(store)

            val importWithId =
                MediaPayload.ArrDownload(
                    source = AppSource.RADARR,
                    downloadId = "dl-123",
                    title = "Dune: Part Two",
                    seriesOrMovieTitle = "Dune: Part Two",
                    isUpgrade = false
                )
            val upgradeWithSameId = importWithId.copy(isUpgrade = true)
            val importWithoutId = importWithId.copy(downloadId = null)

            assertTrue(dedup.tryAcquire(importWithId))
            assertFalse(dedup.tryAcquire(importWithId))
            assertTrue(dedup.tryAcquire(upgradeWithSameId))

            assertNull(dedup.computeKey(importWithoutId))
            assertTrue(dedup.tryAcquire(importWithoutId))
            assertTrue(dedup.tryAcquire(importWithoutId))
        }
}
