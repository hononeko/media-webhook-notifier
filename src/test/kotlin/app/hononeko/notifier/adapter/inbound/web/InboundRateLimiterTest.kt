package app.hononeko.notifier.adapter.inbound.web

import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class InboundRateLimiterTest {
    @Test
    fun `should permit requests within rate limit and reject exceeded requests`() =
        testApplication {
            val limiter = InboundRateLimiter(limitPerMinute = 2)

            application {
                routing {
                    post("/test") {
                        if (limiter.tryAcquire(call)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Rate Limited", status = HttpStatusCode.TooManyRequests)
                        }
                    }
                }
            }

            val res1 = client.post("/test")
            assertEquals(HttpStatusCode.OK, res1.status)

            val res2 = client.post("/test")
            assertEquals(HttpStatusCode.OK, res2.status)

            val res3 = client.post("/test")
            assertEquals(HttpStatusCode.TooManyRequests, res3.status)
        }

    @Test
    fun `should allow unlimited requests when limitPerMinute is zero or negative`() =
        testApplication {
            val limiter = InboundRateLimiter(limitPerMinute = 0)

            application {
                routing {
                    post("/test") {
                        if (limiter.tryAcquire(call)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Rate Limited", status = HttpStatusCode.TooManyRequests)
                        }
                    }
                }
            }

            for (i in 1..10) {
                val res = client.post("/test")
                assertEquals(HttpStatusCode.OK, res.status)
            }
        }
}
