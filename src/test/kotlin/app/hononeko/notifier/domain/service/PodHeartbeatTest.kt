package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodHeartbeatTest {
    @Test
    fun `beat publishes liveness key and expires after ttl`() =
        runTest {
            var now = 1_000_000L
            val store = InMemoryStateStore()
            val podA = PodHeartbeat(store, "pod-a", ttlSeconds = 9L, intervalSeconds = 3L, clock = { now })
            val podB = PodHeartbeat(store, "pod-b", ttlSeconds = 9L, intervalSeconds = 3L, clock = { now })

            podA.beat()
            assertTrue(podA.isAlive("pod-a"))
            assertTrue(podB.isAlive("pod-a"))

            now += 10_000L
            assertFalse(podB.isAlive("pod-a"))
        }

    @Test
    fun `start refreshes heartbeat periodically and stop deletes it immediately`() =
        runTest {
            val testScope = TestScope(StandardTestDispatcher(testScheduler))
            var now = 1_000_000L
            val store = InMemoryStateStore()
            val podA = PodHeartbeat(store, "pod-a", ttlSeconds = 9L, intervalSeconds = 3L, clock = { now })
            val podB = PodHeartbeat(store, "pod-b", ttlSeconds = 9L, intervalSeconds = 3L, clock = { now })

            podA.start(testScope)
            testScope.advanceTimeBy(1)
            assertTrue(podB.isAlive("pod-a"))

            now += 6_000L
            testScope.advanceTimeBy(3_000L)
            assertTrue(podB.isAlive("pod-a"), "heartbeat should have been renewed")

            podA.stop()
            assertFalse(podB.isAlive("pod-a"), "stop should remove liveness key immediately")
        }

    @Test
    fun `isAlive fails safe when state store is unhealthy`() =
        runTest {
            val flaky = mockk<StateStorePort>()
            coEvery { flaky.exists(any(), any()) } returns false
            coEvery { flaky.healthCheck() } returns false

            val podB = PodHeartbeat(flaky, "pod-b")
            assertTrue(podB.isAlive("pod-a"))
        }
}
