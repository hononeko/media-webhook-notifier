package app.hononeko.notifier.adapter.inbound.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class InboundRateLimiter(
    val limitPerMinute: Int = 120
) {
    private val clientBuckets = ConcurrentHashMap<String, WindowCounter>()

    data class WindowCounter(
        val windowStartMinute: Long,
        val counter: AtomicInteger
    )

    fun tryAcquire(call: ApplicationCall): Boolean {
        if (limitPerMinute <= 0) {
            return true
        }

        val clientKey = extractClientKey(call)
        val currentMinute = System.currentTimeMillis() / 60_000

        val bucket =
            clientBuckets.compute(clientKey) { _, existing ->
                if (existing == null || existing.windowStartMinute != currentMinute) {
                    WindowCounter(currentMinute, AtomicInteger(1))
                } else {
                    existing.counter.incrementAndGet()
                    existing
                }
            }

        // Periodically evict expired entries to prevent memory leak
        if (clientBuckets.size > 2000) {
            clientBuckets.entries.removeIf { it.value.windowStartMinute < currentMinute - 1 }
        }

        return (bucket?.counter?.get() ?: 1) <= limitPerMinute
    }

    private fun extractClientKey(call: ApplicationCall): String = call.request.origin.remoteAddress
}
