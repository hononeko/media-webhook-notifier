package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadLetterPersistenceTest {
    private val allPayloadTypes: List<MediaPayload> =
        listOf(
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "abc|def",
                title = "Severance.S02E01",
                seriesOrMovieTitle = "Severance",
                episodeNumbers = listOf(1, 2)
            ),
            MediaPayload.ArrDownload(
                source = AppSource.RADARR,
                eventType = EventType.UPGRADE,
                title = "Dune",
                seriesOrMovieTitle = "Dune",
                isUpgrade = true
            ),
            MediaPayload.PlexLibraryNew(title = "Frieren", ratingKey = "123", rating = 9.1),
            MediaPayload.JellyfinItemAdded(itemId = "item-1", title = "Arcane", seasonNumber = 2),
            MediaPayload.ServarrHealth(
                source = AppSource.SONARR,
                level = "error",
                message = "Down",
                type = "IndexerCheck"
            ),
            MediaPayload.ServarrManualInteraction(
                source = AppSource.SONARR,
                title = "Show.S01E01",
                seriesOrMovieTitle = "Show",
                reason = "Unknown series"
            ),
            MediaPayload.SeerrEvent(
                eventType = EventType.REQUEST_PENDING,
                notificationType = "MEDIA_PENDING",
                subject = "Arcane",
                extra = mapOf("Requested Seasons" to "1, 2")
            )
        )

    @Test
    fun `should round trip every payload type through the dead letter codec`() {
        allPayloadTypes.forEach { payload ->
            val encoded = DeadLetterCodec.json.encodeToString(MediaPayload.serializer(), payload)
            assertEquals(payload, DeadLetterCodec.json.decodeFromString(MediaPayload.serializer(), encoded))
        }

        val health = DeadLetterCodec.json.encodeToString(MediaPayload.serializer(), allPayloadTypes[4])
        assertTrue(health.contains("\"payloadType\":\"servarr_health\""))
        assertTrue(health.contains("\"type\":\"IndexerCheck\""))
    }

    @Test
    fun `should not persist raw plex artwork bytes`() {
        val payload = MediaPayload.PlexLibraryNew(title = "Frieren", artworkBytes = ByteArray(1024) { 7 })
        val encoded = DeadLetterCodec.json.encodeToString(MediaPayload.serializer(), payload)
        val decoded = DeadLetterCodec.json.decodeFromString(MediaPayload.serializer(), encoded)

        assertTrue(!encoded.contains("artworkBytes"))
        assertNull((decoded as MediaPayload.PlexLibraryNew).artworkBytes)
    }

    @Test
    fun `should restore dead letters written by a previous instance`() =
        runTest {
            val store = InMemoryStateStore()
            val previous = DeadLetterRingBuffer(capacity = 10)
            val written = allPayloadTypes.map { previous.record(it, "Failed ${it.eventType}", stackTrace = "trace") }
            previous.replay(written.first().id) { true }
            DeadLetterPersistence(previous, store).flush()

            val restarted = DeadLetterRingBuffer(capacity = 10)
            DeadLetterPersistence(restarted, store).restore()

            val restored = restarted.getEntries()
            assertEquals(previous.getEntries(), restored)
            assertEquals(DeadLetterStatus.RESOLVED, restored.first().status)
            assertNotNull(restored.first().resolvedAt)
        }

    @Test
    fun `should ignore unreadable snapshots`() =
        runTest {
            val store = InMemoryStateStore()
            store.set(DeadLetterPersistence.SNAPSHOT_KEY, "{ not json")
            val buffer = DeadLetterRingBuffer()

            DeadLetterPersistence(buffer, store).restore()

            assertEquals(0, buffer.size())
        }

    @Test
    fun `should mirror buffer mutations into the state store in the background`() =
        runBlocking<Unit> {
            val store = InMemoryStateStore()
            val buffer = DeadLetterRingBuffer()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                DeadLetterPersistence(buffer, store).start(scope)
                val entry = buffer.record(allPayloadTypes.first(), "Ingest failed")

                withTimeout(5_000) {
                    while (store.get(DeadLetterPersistence.SNAPSHOT_KEY)?.contains(entry.id) != true) {
                        delay(10)
                    }
                }

                buffer.clear()
                withTimeout(5_000) {
                    while (store.get(DeadLetterPersistence.SNAPSHOT_KEY)?.contains(entry.id) != false) {
                        delay(10)
                    }
                }
            } finally {
                scope.cancel()
            }
        }
}
