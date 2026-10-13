package app.hononeko.notifier.adapter.inbound.web.provider

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/** Posts [body] to [provider] through a real Ktor call and returns what it produced. */
internal fun processServarr(
    provider: AbstractServarrWebhookProvider,
    body: String
): WebhookProcessResult {
    var result: WebhookProcessResult? = null
    testApplication {
        routing {
            post("/webhook") {
                result = provider.process(call, null)
                call.respondText("OK")
            }
        }
        client.post("/webhook") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    return requireNotNull(result)
}

internal fun servarrFixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/servarr/$name")) { "missing fixture $name" }.readText()
