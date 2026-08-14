package app.hononeko.notifier.adapter.inbound.web.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

class HealthController {
    @Serializable
    data class HealthStatusDto(
        val status: String = "UP",
        val service: String = "media-webhook-notifier",
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun handleHealth(call: ApplicationCall) {
        call.respond(HttpStatusCode.OK, HealthStatusDto())
    }
}
