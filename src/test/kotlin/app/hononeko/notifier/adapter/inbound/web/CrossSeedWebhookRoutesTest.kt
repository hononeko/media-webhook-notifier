package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.provider.CrossSeedWebhookProviderTest
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.config.ServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/** `POST /api/v1/webhook/cross-seed`, behind `CROSS_SEED_ENABLED` (MWN-40). */
class CrossSeedWebhookRoutesTest {
    private val token = "secret123"

    private fun withApp(
        crossSeedEnabled: Boolean = true,
        eventRail: EventRail = EventRail(capacity = 50),
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureWebhookRouting(
                eventRail,
                ServerConfig(authToken = token),
                providerRegistry =
                    WebhookProviderRegistry(
                        WebhookProviderRegistry.defaultProviders(crossSeedEnabled = crossSeedEnabled)
                    )
            )
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.postCrossSeed(
        body: String,
        path: String = "/api/v1/webhook/cross-seed",
        authHeader: String? = "Bearer $token"
    ): HttpResponse =
        client.post(path) {
            authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `the route and its schema are 404 while CROSS_SEED_ENABLED is off`() =
        withApp(crossSeedEnabled = false) {
            val response = postCrossSeed(CrossSeedWebhookProviderTest.TEST_PAYLOAD)

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Unsupported webhook provider 'cross-seed'"))
            assertEquals(HttpStatusCode.NotFound, client.get("/schema/cross-seed").status)
        }

    @Test
    fun `a test notification is acknowledged with 200`() =
        withApp {
            val response = postCrossSeed(CrossSeedWebhookProviderTest.TEST_PAYLOAD)

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Test webhook received successfully"))
        }

    @Test
    fun `injected, saved and failed results are queued with 202`() =
        withApp {
            for (result in listOf("INJECTED", "SAVED", "FAILURE")) {
                val response = postCrossSeed(CrossSeedWebhookProviderTest.resultsPayload(result))

                assertEquals(HttpStatusCode.Accepted, response.status, result)
                assertTrue(response.bodyAsText().contains(result), result)
            }
        }

    @Test
    fun `cross-seed's X-Api-Key header, a query token and a path token all authenticate`() =
        withApp {
            val payload = CrossSeedWebhookProviderTest.resultsPayload("INJECTED")
            val apiKey =
                client.post("/api/v1/webhook/cross-seed") {
                    header("X-Api-Key", token)
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            assertEquals(HttpStatusCode.Accepted, apiKey.status)
            assertEquals(
                HttpStatusCode.Accepted,
                postCrossSeed(payload, "/api/v1/webhook/cross-seed?token=$token", authHeader = null).status
            )
            assertEquals(
                HttpStatusCode.Accepted,
                postCrossSeed(payload, "/api/v1/webhook/$token/crossseed", authHeader = null).status
            )
        }

    @Test
    fun `a missing or wrong token is 401`() =
        withApp {
            val payload = CrossSeedWebhookProviderTest.resultsPayload("INJECTED")

            assertEquals(HttpStatusCode.Unauthorized, postCrossSeed(payload, authHeader = null).status)
            assertEquals(HttpStatusCode.Unauthorized, postCrossSeed(payload, authHeader = "Bearer wrong").status)
        }

    @Test
    fun `an invalid payload is 400 and an unannounced result is 200 ignored`() =
        withApp {
            assertEquals(HttpStatusCode.BadRequest, postCrossSeed("not json").status)
            assertEquals(HttpStatusCode.BadRequest, postCrossSeed("""{"title": "cross-seed"}""").status)

            val ignored = postCrossSeed(CrossSeedWebhookProviderTest.resultsPayload("ALREADY_EXISTS"))
            assertEquals(HttpStatusCode.OK, ignored.status)
            assertTrue(ignored.bodyAsText().contains("ignored"))
        }

    @Test
    fun `a full event rail is 503 and the schema is served`() =
        withApp(eventRail = EventRail(capacity = 0)) {
            val response = postCrossSeed(CrossSeedWebhookProviderTest.resultsPayload("INJECTED"))
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

            val schema = client.get("/schema/cross-seed")
            assertEquals(HttpStatusCode.OK, schema.status)
            assertTrue(schema.bodyAsText().contains("cross-seed Webhook Payload"))
        }
}
