package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackRefRegistryTest {
    private fun target(title: String) = CallbackTarget(NotificationCard(title = title))

    private var now = 1_000_000L
    private val clock: () -> Long = { now }
    private val store = InMemoryStateStore()
    private val registry = CallbackRefRegistry(store, clock = clock)

    @Test
    fun `register then resolve returns the stored target`() =
        runTest {
            val ref = registry.register(target("abc|def"), ttlSeconds = 60)!!

            assertTrue(Regex("^[A-Za-z0-9]{10}$").matches(ref), "unexpected ref format: $ref")
            assertEquals(target("abc|def"), registry.resolve(ref))
        }

    @Test
    fun `resolve returns null after ttl expires`() =
        runTest {
            val ref = registry.register(target("x"), ttlSeconds = 60)!!

            now += 61_000

            assertNull(registry.resolve(ref))
        }

    @Test
    fun `register retries on collision`() =
        runTest {
            // Two generators with the same seed produce the same first ref, so the second must retry.
            val first = CallbackRefRegistry(store, Random(7), clock).register(target("a"), 60)!!
            val second = CallbackRefRegistry(store, Random(7), clock).register(target("b"), 60)!!

            assertNotEquals(first, second)
            assertEquals(target("a"), registry.resolve(first))
            assertEquals(target("b"), registry.resolve(second))
        }

    @Test
    fun `resolve rejects refs outside the alphabet without touching the store`() =
        runTest {
            val spy = spyk(InMemoryStateStore())
            val spiedRegistry = CallbackRefRegistry(spy, clock = clock)

            assertNull(spiedRegistry.resolve("../../etc"))
            assertNull(spiedRegistry.resolve(""))
            coVerify(exactly = 0) { spy.get(any(), any()) }
        }

    @Test
    fun `release makes resolve return null`() =
        runTest {
            val ref = registry.register(target("a"), ttlSeconds = 60)!!

            registry.release(ref)

            assertNull(registry.resolve(ref))
        }

    @Test
    fun `release ignores malformed refs without touching the store`() =
        runTest {
            val spy = spyk(InMemoryStateStore())
            val spiedRegistry = CallbackRefRegistry(spy, clock = clock)

            spiedRegistry.release("../x")

            coVerify(exactly = 0) { spy.delete(any()) }
        }

    @Test
    fun `resolve returns null for corrupt stored json`() =
        runTest {
            store.set("cb:AAAAAAAAAA", "not-json", null, now)

            assertNull(registry.resolve("AAAAAAAAAA"))
        }

    @Test
    fun `claim is exclusive until unclaimed or expired`() =
        runTest {
            val ref = registry.register(target("x"), ttlSeconds = 60)!!

            assertTrue(registry.claim(ref, ttlSeconds = 30))
            assertFalse(registry.claim(ref, ttlSeconds = 30))

            registry.unclaim(ref)
            assertTrue(registry.claim(ref, ttlSeconds = 30))

            now += 31_000
            assertTrue(registry.claim(ref, ttlSeconds = 30))
        }

    @Test
    fun `claim refuses malformed refs`() =
        runTest {
            assertFalse(registry.claim("bad ref"))
        }
}
