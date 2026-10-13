package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.port.outbound.LockRenewal
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingLeasesTest {
    private val now = 1_000_000L
    private val store = InMemoryStateStore()
    private val podA = TrackingLeases(store, "pod-a", ttlSeconds = 30, clock = { now })
    private val podB = TrackingLeases(store, "pod-b", ttlSeconds = 30, clock = { now })

    @Test
    fun `keysFor normalizes, de-duplicates and sorts hashes`() {
        assertEquals(listOf("lock:track:a", "lock:track:b"), podA.keysFor("B|a| a|"))
    }

    @Test
    fun `acquire is all or nothing`() =
        runTest {
            assertTrue(podB.acquire("b"))

            assertFalse(podA.acquire("a|b"))
            assertTrue(store.acquireLock("lock:track:a", "x", 30, now), "a should have been rolled back")
        }

    @Test
    fun `renew is LOST when any hash belongs to another owner`() =
        runTest {
            podA.acquire("a|b")
            store.releaseLock("lock:track:b", "pod-a")
            store.acquireLock("lock:track:b", "pod-b", 30, now)

            assertEquals(LockRenewal.LOST, podA.renew("a|b"))
        }

    @Test
    fun `renew is STORE_UNAVAILABLE when the store can't answer and nothing is lost`() =
        runTest {
            val flaky = mockk<StateStorePort>()
            coEvery { flaky.renewLock("lock:track:a", any(), any(), any()) } returns LockRenewal.STORE_UNAVAILABLE
            coEvery { flaky.renewLock("lock:track:b", any(), any(), any()) } returns LockRenewal.RENEWED

            assertEquals(LockRenewal.STORE_UNAVAILABLE, TrackingLeases(flaky, "pod-a", 30, { now }).renew("a|b"))
        }

    @Test
    fun `release frees every hash`() =
        runTest {
            podA.acquire("a|b")

            podA.release("a|b")

            assertTrue(podB.acquire("a|b"))
        }
}
