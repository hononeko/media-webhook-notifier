package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaAvailableDeduplicatorTest {
    @Test
    fun `should deduplicate Plex items by ratingKey`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val plexItem1 =
            MediaPayload.PlexLibraryNew(
                title = "American Psycho",
                year = 2000,
                ratingKey = "12345",
                serverMachineIdentifier = "server-uuid-1",
                instanceName = "Kerrlab Plex"
            )

        val plexItem2 =
            MediaPayload.PlexLibraryNew(
                title = "American Psycho",
                year = 2000,
                ratingKey = "12345",
                serverMachineIdentifier = "server-uuid-1",
                instanceName = "Kerrlab Plex"
            )

        assertTrue(deduplicator.tryAcquire(plexItem1))
        assertTrue(deduplicator.isDuplicate(plexItem2))
        assertFalse(deduplicator.tryAcquire(plexItem2))
        assertEquals(1, deduplicator.size())
    }

    @Test
    fun `should deduplicate Plex items by title and year when ratingKey is missing`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val plexItem1 =
            MediaPayload.PlexLibraryNew(
                title = "Black Widow",
                year = 2021,
                ratingKey = null,
                instanceName = "Kerrlab Plex"
            )

        val plexItem2 =
            MediaPayload.PlexLibraryNew(
                title = "Black Widow",
                year = 2021,
                ratingKey = null,
                instanceName = "Kerrlab Plex"
            )

        assertTrue(deduplicator.tryAcquire(plexItem1))
        assertTrue(deduplicator.isDuplicate(plexItem2))
        assertFalse(deduplicator.tryAcquire(plexItem2))
    }

    @Test
    fun `should deduplicate Jellyfin items by itemId`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val jellyfinItem1 =
            MediaPayload.JellyfinItemAdded(
                itemId = "jelly-item-999",
                title = "Severance",
                serverId = "srv-1"
            )

        val jellyfinItem2 =
            MediaPayload.JellyfinItemAdded(
                itemId = "jelly-item-999",
                title = "Severance",
                serverId = "srv-1"
            )

        assertTrue(deduplicator.tryAcquire(jellyfinItem1))
        assertTrue(deduplicator.isDuplicate(jellyfinItem2))
        assertFalse(deduplicator.tryAcquire(jellyfinItem2))
    }

    @Test
    fun `should deduplicate Jellyfin items by title and season when itemId is blank`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val jellyfinItem1 =
            MediaPayload.JellyfinItemAdded(
                itemId = "",
                title = "Episode 1",
                seriesName = "Severance",
                seasonNumber = 1,
                episodeNumber = 1,
                year = 2022,
                instanceName = "Home"
            )

        val jellyfinItem2 =
            MediaPayload.JellyfinItemAdded(
                itemId = "",
                title = "Episode 1",
                seriesName = "Severance",
                seasonNumber = 1,
                episodeNumber = 1,
                year = 2022,
                instanceName = "Home"
            )

        assertTrue(deduplicator.tryAcquire(jellyfinItem1))
        assertTrue(deduplicator.isDuplicate(jellyfinItem2))
        assertFalse(deduplicator.tryAcquire(jellyfinItem2))
    }

    @Test
    fun `should return null key and allow acquisition for non-media available payloads`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val grab =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash123",
                title = "Severance",
                seriesOrMovieTitle = "Severance"
            )

        assertNull(deduplicator.computeKey(grab))
        assertFalse(deduplicator.isDuplicate(grab))
        assertTrue(deduplicator.tryAcquire(grab))
        deduplicator.release(grab)
    }

    @Test
    fun `should allow re-acquisition after release on failure`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val item =
            MediaPayload.PlexLibraryNew(
                title = "Interstellar",
                year = 2014,
                ratingKey = "555"
            )

        assertTrue(deduplicator.tryAcquire(item))
        assertFalse(deduplicator.tryAcquire(item))

        deduplicator.release(item)
        assertTrue(deduplicator.tryAcquire(item))
    }

    @Test
    fun `should clear all cache entries`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val item =
            MediaPayload.PlexLibraryNew(
                title = "Interstellar",
                year = 2014,
                ratingKey = "555"
            )

        assertTrue(deduplicator.tryAcquire(item))
        assertEquals(1, deduplicator.size())

        deduplicator.clear()
        assertEquals(0, deduplicator.size())
        assertTrue(deduplicator.tryAcquire(item))
    }

    @Test
    fun `should expire items after TTL window`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 5000L)

        val item =
            MediaPayload.PlexLibraryNew(
                title = "X2: X-Men United",
                year = 2003,
                ratingKey = "777"
            )

        val t0 = 100_000L
        assertTrue(deduplicator.tryAcquire(item, now = t0))

        // Before TTL
        assertFalse(deduplicator.tryAcquire(item, now = t0 + 2000L))
        assertTrue(deduplicator.isDuplicate(item, now = t0 + 2000L))

        // After TTL
        assertFalse(deduplicator.isDuplicate(item, now = t0 + 6000L))
        assertTrue(deduplicator.tryAcquire(item, now = t0 + 6000L))
    }

    @Test
    fun `should prune expired and oldest items when capacity exceeded`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 5000L, maxCapacity = 3)

        // Add 3 items with old timestamps
        for (i in 1..3) {
            val item =
                MediaPayload.PlexLibraryNew(
                    title = "Movie $i",
                    year = 2000 + i,
                    ratingKey = "key-$i"
                )
            deduplicator.tryAcquire(item, now = 1000L)
        }
        assertEquals(3, deduplicator.size())

        // Add 4th item at now = 7000L (expired items pruned first)
        val item4 =
            MediaPayload.PlexLibraryNew(
                title = "Movie 4",
                year = 2004,
                ratingKey = "key-4"
            )
        assertTrue(deduplicator.tryAcquire(item4, now = 7000L))
        assertTrue(deduplicator.size() <= 3)

        // Add more items within TTL to test LRU pruning of unexpired items
        for (i in 5..8) {
            val item =
                MediaPayload.PlexLibraryNew(
                    title = "Movie $i",
                    year = 2000 + i,
                    ratingKey = "key-$i"
                )
            deduplicator.tryAcquire(item, now = 7000L + i * 100L)
        }
        assertTrue(deduplicator.size() <= 3)
    }

    @Test
    fun `should distinguish different episodes of same series`() {
        val deduplicator = MediaAvailableDeduplicator(ttlMillis = 10_000L)

        val ep1 =
            MediaPayload.PlexLibraryNew(
                title = "Episode 1",
                grandParentTitle = "Ghost in the Shell",
                seasonNumber = 1,
                episodeNumber = 1,
                ratingKey = "ep-1"
            )

        val ep2 =
            MediaPayload.PlexLibraryNew(
                title = "Episode 2",
                grandParentTitle = "Ghost in the Shell",
                seasonNumber = 1,
                episodeNumber = 2,
                ratingKey = "ep-2"
            )

        assertTrue(deduplicator.tryAcquire(ep1))
        assertTrue(deduplicator.tryAcquire(ep2))
        assertEquals(2, deduplicator.size())
    }
}
