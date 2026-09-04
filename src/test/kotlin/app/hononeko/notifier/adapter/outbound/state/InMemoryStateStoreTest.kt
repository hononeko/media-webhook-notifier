package app.hononeko.notifier.adapter.outbound.state

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryStateStoreTest {
    @Test
    fun `should acquire unacquired key and fail to acquire already acquired key`() =
        runTest {
            val store = InMemoryStateStore()
            val acquired1 = store.tryAcquire("test:key", ttlSeconds = 60, value = "val1")
            assertTrue(acquired1)

            val acquired2 = store.tryAcquire("test:key", ttlSeconds = 60, value = "val2")
            assertFalse(acquired2)

            assertEquals("val1", store.get("test:key"))
        }

    @Test
    fun `should expire acquired key after TTL`() =
        runTest {
            val store = InMemoryStateStore()
            val t0 = 100_000L

            assertTrue(store.tryAcquire("item", ttlSeconds = 10, value = "active", nowMillis = t0))
            assertTrue(store.exists("item", nowMillis = t0 + 5000L))
            assertEquals("active", store.get("item", nowMillis = t0 + 5000L))

            // At t0 + 10_000ms or greater, key should expire
            assertFalse(store.exists("item", nowMillis = t0 + 10_001L))
            assertNull(store.get("item", nowMillis = t0 + 10_001L))

            // Can re-acquire once expired
            assertTrue(store.tryAcquire("item", ttlSeconds = 10, value = "renewed", nowMillis = t0 + 10_001L))
            assertEquals("renewed", store.get("item", nowMillis = t0 + 10_001L))
        }

    @Test
    fun `should set and get values with and without TTL`() =
        runTest {
            val store = InMemoryStateStore()
            val t0 = 50_000L

            // Without TTL
            store.set("permanent", value = "perm-val", ttlSeconds = null, nowMillis = t0)
            assertEquals("perm-val", store.get("permanent", nowMillis = t0 + 1_000_000L))
            assertTrue(store.exists("permanent", nowMillis = t0 + 1_000_000L))

            // With TTL
            store.set("temp", value = "temp-val", ttlSeconds = 30, nowMillis = t0)
            assertEquals("temp-val", store.get("temp", nowMillis = t0 + 10_000L))
            assertNull(store.get("temp", nowMillis = t0 + 35_000L))
        }

    @Test
    fun `should delete key and report accurate size`() =
        runTest {
            val store = InMemoryStateStore()
            store.set("k1", "v1")
            store.set("k2", "v2")

            assertEquals(2, store.size())
            assertTrue(store.delete("k1"))
            assertFalse(store.delete("non-existent"))
            assertEquals(1, store.size())
            assertNull(store.get("k1"))
            assertEquals("v2", store.get("k2"))
        }

    @Test
    fun `should clear all keys`() =
        runTest {
            val store = InMemoryStateStore()
            store.set("k1", "v1")
            store.set("k2", "v2")
            store.clear()

            assertEquals(0, store.size())
            assertFalse(store.exists("k1"))
            assertFalse(store.exists("k2"))
        }

    @Test
    fun `should report healthy`() =
        runTest {
            val store = InMemoryStateStore()
            assertTrue(store.healthCheck())
        }

    @Test
    fun `should prune expired and LRU entries when maxCapacity is reached`() =
        runTest {
            val store = InMemoryStateStore(maxCapacity = 4)
            val t0 = 10_000L

            // Insert 4 items (k1 with short TTL, others permanent)
            store.set("k1", "v1", ttlSeconds = 5, nowMillis = t0)
            store.set("k2", "v2", nowMillis = t0)
            store.set("k3", "v3", nowMillis = t0)
            store.set("k4", "v4", nowMillis = t0)

            assertEquals(4, store.size(nowMillis = t0))

            // At t0 + 6000L, k1 is expired. Inserting k5 should prune expired k1
            store.set("k5", "v5", nowMillis = t0 + 6000L)
            assertTrue(store.size(nowMillis = t0 + 6000L) <= 4)
            assertFalse(store.exists("k1", nowMillis = t0 + 6000L))
            assertTrue(store.exists("k5", nowMillis = t0 + 6000L))

            // Inserting more unexpired items forces LRU eviction
            store.set("k6", "v6", nowMillis = t0 + 7000L)
            store.set("k7", "v7", nowMillis = t0 + 8000L)
            assertTrue(store.size(nowMillis = t0 + 8000L) <= 4)
        }
}
