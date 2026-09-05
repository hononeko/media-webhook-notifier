package app.hononeko.notifier.adapter.outbound.state

import app.hononeko.notifier.config.StateConfig
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.service.MediaAvailableDeduplicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
class ValkeyStateStoreIntegrationTest {
    companion object {
        private class KGenericContainer(
            image: String
        ) : GenericContainer<KGenericContainer>(DockerImageName.parse(image))

        private val valkeyContainer =
            KGenericContainer("valkey/valkey:8-alpine")
                .withExposedPorts(6379)

        private lateinit var connectionUrl: String

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            valkeyContainer.start()
            connectionUrl = "valkey://${valkeyContainer.host}:${valkeyContainer.getMappedPort(6379)}"
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            valkeyContainer.stop()
        }
    }

    @Test
    fun `should connect successfully and report healthy status`() =
        runTest {
            val store = ValkeyStateStore(config = StateConfig(url = connectionUrl))
            try {
                assertTrue(store.healthCheck(), "Valkey container should respond healthy to PING")
            } finally {
                store.close()
            }
        }

    @Test
    fun `should execute atomic tryAcquire semantics against real Valkey instance`() =
        runTest {
            val store = ValkeyStateStore(config = StateConfig(url = connectionUrl, keyPrefix = "mwn:int:acquire:"))
            try {
                val key = "dedup:episode:101"

                // First acquisition must succeed
                val firstAcquire = store.tryAcquire(key, ttlSeconds = 10, value = "node-1")
                assertTrue(firstAcquire)

                // Value is retained
                assertEquals("node-1", store.get(key))

                // Second acquisition while held must fail
                val secondAcquire = store.tryAcquire(key, ttlSeconds = 10, value = "node-2")
                assertFalse(secondAcquire)

                // After deletion, re-acquisition succeeds
                assertTrue(store.delete(key))
                val thirdAcquire = store.tryAcquire(key, ttlSeconds = 10, value = "node-3")
                assertTrue(thirdAcquire)
            } finally {
                store.close()
            }
        }

    @Test
    fun `should handle high concurrent tryAcquire allowing only one winner`() =
        runTest {
            val store = ValkeyStateStore(config = StateConfig(url = connectionUrl, keyPrefix = "mwn:int:race:"))
            try {
                val key = "race:mutex"
                val concurrency = 20

                val results =
                    (1..concurrency)
                        .map { id ->
                            async(Dispatchers.IO) {
                                store.tryAcquire(key, ttlSeconds = 30, value = "worker-$id")
                            }
                        }.awaitAll()

                val successfulAcquisitions = results.count { it }
                val failedAcquisitions = results.count { !it }

                assertEquals(1, successfulAcquisitions, "Exactly one worker must acquire the key")
                assertEquals(concurrency - 1, failedAcquisitions, "All other workers must fail acquisition")
                assertTrue(store.exists(key))
            } finally {
                store.close()
            }
        }

    @Test
    fun `should support CRUD operations with and without TTL`() =
        runTest {
            val store = ValkeyStateStore(config = StateConfig(url = connectionUrl, keyPrefix = "mwn:int:crud:"))
            try {
                val persistentKey = "persist:meta"
                val expiringKey = "temp:session"

                // Set without TTL
                store.set(persistentKey, "long-lived")
                assertTrue(store.exists(persistentKey))
                assertEquals("long-lived", store.get(persistentKey))

                // Set with short TTL
                store.set(expiringKey, "short-lived", ttlSeconds = 1)
                assertTrue(store.exists(expiringKey))
                assertEquals("short-lived", store.get(expiringKey))

                // Wait for real-time expiration in Valkey container
                Thread.sleep(1200)
                assertFalse(store.exists(expiringKey))
                assertNull(store.get(expiringKey))

                // Delete
                assertTrue(store.delete(persistentKey))
                assertFalse(store.exists(persistentKey))
                assertFalse(store.delete(persistentKey))
            } finally {
                store.close()
            }
        }

    @Test
    fun `should isolate key namespaces and clear only prefixed keys`() =
        runTest {
            val storeA = ValkeyStateStore(config = StateConfig(url = connectionUrl, keyPrefix = "mwn:appA:"))
            val storeB = ValkeyStateStore(config = StateConfig(url = connectionUrl, keyPrefix = "mwn:appB:"))
            try {
                storeA.set("shared-item", "data-a")
                storeB.set("shared-item", "data-b")

                assertEquals("data-a", storeA.get("shared-item"))
                assertEquals("data-b", storeB.get("shared-item"))

                // Clearing storeA must not touch storeB keys
                storeA.clear()
                assertFalse(storeA.exists("shared-item"))
                assertTrue(storeB.exists("shared-item"))
                assertEquals("data-b", storeB.get("shared-item"))
            } finally {
                storeA.close()
                storeB.close()
            }
        }

    @Test
    fun `should integrate with MediaAvailableDeduplicator against Valkey instance`() =
        runTest {
            val store = ValkeyStateStore(config = StateConfig(url = connectionUrl, keyPrefix = "mwn:int:dedup:"))
            try {
                val deduplicator = MediaAvailableDeduplicator(stateStore = store, ttlMillis = 10_000L)

                val plexItem =
                    MediaPayload.PlexLibraryNew(
                        title = "Frieren: Beyond Journey's End",
                        year = 2023,
                        ratingKey = "plex-item-999",
                        serverMachineIdentifier = "server-uuid-1",
                        instanceName = "Kerrlab Plex"
                    )

                // First occurrence is not duplicate
                val isFirstAcquired = deduplicator.tryAcquire(plexItem)
                assertTrue(isFirstAcquired, "First arrival of item must be acquired")

                // Subsequent check must detect duplicate
                val isSecondDuplicate = deduplicator.isDuplicate(plexItem)
                assertTrue(isSecondDuplicate, "Second arrival of same item within TTL must be flagged as duplicate")

                val isSecondAcquired = deduplicator.tryAcquire(plexItem)
                assertFalse(isSecondAcquired, "Second arrival must fail tryAcquire")
            } finally {
                store.close()
            }
        }
}
