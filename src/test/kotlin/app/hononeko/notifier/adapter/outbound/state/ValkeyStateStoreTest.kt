package app.hononeko.notifier.adapter.outbound.state

import app.hononeko.notifier.config.StateConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import redis.clients.jedis.UnifiedJedis
import redis.clients.jedis.exceptions.JedisConnectionException
import redis.clients.jedis.params.ScanParams
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.resps.ScanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValkeyStateStoreTest {
    @Test
    fun `should operate exclusively in fallback mode when url is blank`() =
        runTest {
            val config = StateConfig(url = "")
            val store = ValkeyStateStore(config = config)

            assertFalse(store.healthCheck())
            assertTrue(store.tryAcquire("key1", ttlSeconds = 60, value = "val1"))
            assertFalse(store.tryAcquire("key1", ttlSeconds = 60, value = "val1"))
            assertTrue(store.exists("key1"))
            assertEquals("val1", store.get("key1"))
            assertTrue(store.delete("key1"))
            assertFalse(store.exists("key1"))
        }

    @Test
    fun `should successfully initialize with valid redis or valkey url and close cleanly`() =
        runTest {
            val store = ValkeyStateStore(config = StateConfig(url = "valkey://127.0.0.1:6379", timeoutMillis = 1000))
            store.close()

            val storeNoPort = ValkeyStateStore(config = StateConfig(url = "127.0.0.1", timeoutMillis = 1000))
            storeNoPort.close()

            val storeValkeys = ValkeyStateStore(config = StateConfig(url = "valkeys://127.0.0.1", timeoutMillis = 1000))
            storeValkeys.close()
        }

    @Test
    fun `should delegate tryAcquire to Jedis and apply keyPrefix`() =
        runTest {
            val mockJedis = mockk<UnifiedJedis>(relaxed = true)
            val config = StateConfig(url = "redis://localhost:6379", keyPrefix = "mwn:tg:")
            val store = ValkeyStateStore(config = config, injectedJedis = mockJedis)

            every { mockJedis.set(eq("mwn:tg:item1"), eq("data"), any<SetParams>()) } returns "OK"
            val success = store.tryAcquire("item1", ttlSeconds = 120, value = "data")
            assertTrue(success)

            every { mockJedis.set(eq("mwn:tg:item1"), eq("data"), any<SetParams>()) } returns null
            val failure = store.tryAcquire("item1", ttlSeconds = 120, value = "data")
            assertFalse(failure)

            verify(exactly = 2) {
                mockJedis.set(eq("mwn:tg:item1"), eq("data"), any<SetParams>())
            }
        }

    @Test
    fun `should delegate exists, get, set, delete, and clear to Jedis with key prefix isolation`() =
        runTest {
            val mockJedis = mockk<UnifiedJedis>(relaxed = true)
            val config = StateConfig(url = "redis://localhost:6379", keyPrefix = "mwn:discord:")
            val store = ValkeyStateStore(config = config, injectedJedis = mockJedis)

            // exists
            every { mockJedis.exists("mwn:discord:k1") } returns true
            assertTrue(store.exists("k1"))

            // get
            every { mockJedis.get("mwn:discord:k1") } returns "disc-val"
            assertEquals("disc-val", store.get("k1"))

            // set with TTL
            every { mockJedis.set(eq("mwn:discord:k2"), eq("val2"), any<SetParams>()) } returns "OK"
            store.set("k2", "val2", ttlSeconds = 45)
            verify { mockJedis.set(eq("mwn:discord:k2"), eq("val2"), any<SetParams>()) }

            // set without TTL
            every { mockJedis.set("mwn:discord:k3", "val3") } returns "OK"
            store.set("k3", "val3", ttlSeconds = null)
            verify { mockJedis.set("mwn:discord:k3", "val3") }

            // delete
            every { mockJedis.del("mwn:discord:k1") } returns 1L
            assertTrue(store.delete("k1"))

            // clear
            val scanResult = ScanResult(ScanParams.SCAN_POINTER_START, listOf("mwn:discord:k2", "mwn:discord:k3"))
            every { mockJedis.scan(eq(ScanParams.SCAN_POINTER_START), any<ScanParams>()) } returns scanResult
            every { mockJedis.del("mwn:discord:k2", "mwn:discord:k3") } returns 2L
            store.clear()
            verify { mockJedis.scan(eq(ScanParams.SCAN_POINTER_START), any<ScanParams>()) }
            verify { mockJedis.del("mwn:discord:k2", "mwn:discord:k3") }
        }

    @Test
    fun `should report health status based on PING response`() =
        runTest {
            val mockJedis = mockk<UnifiedJedis>()
            val config = StateConfig(url = "redis://localhost:6379")
            val store = ValkeyStateStore(config = config, injectedJedis = mockJedis)

            every { mockJedis.ping() } returns "PONG"
            assertTrue(store.healthCheck())

            every { mockJedis.ping() } throws JedisConnectionException("Connection refused")
            assertFalse(store.healthCheck())
        }

    @Test
    fun `should fall back to in-memory store when Jedis throws connection exception`() =
        runTest {
            val mockJedis = mockk<UnifiedJedis>()
            val fallback = InMemoryStateStore()
            val config = StateConfig(url = "redis://localhost:6379", keyPrefix = "mwn:")
            val store = ValkeyStateStore(config = config, fallbackStore = fallback, injectedJedis = mockJedis)

            // When Jedis fails, tryAcquire falls back to fallbackStore
            every { mockJedis.set(any<String>(), any<String>(), any<SetParams>()) } throws
                JedisConnectionException("Socket timeout")
            val acquired = store.tryAcquire("fallback-item", ttlSeconds = 60, value = "stored-in-fallback")
            assertTrue(acquired)

            // Exists checks both Jedis and fallback
            every { mockJedis.exists(any<String>()) } throws JedisConnectionException("Socket timeout")
            assertTrue(store.exists("fallback-item"))

            // Get checks fallback
            every { mockJedis.get(any<String>()) } throws JedisConnectionException("Socket timeout")
            assertEquals("stored-in-fallback", store.get("fallback-item"))
        }

    @Test
    fun `should close underlying Jedis client safely on close`() {
        val mockJedis = mockk<UnifiedJedis>(relaxed = true)
        val config = StateConfig(url = "redis://localhost:6379")
        val store = ValkeyStateStore(config = config, injectedJedis = mockJedis)

        store.close()
        verify(exactly = 1) { mockJedis.close() }
    }
}
