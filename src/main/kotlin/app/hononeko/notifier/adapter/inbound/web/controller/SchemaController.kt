package app.hononeko.notifier.adapter.inbound.web.controller

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable

class SchemaController {
    @Serializable
    data class HealthStatusDto(
        val status: String = "UP",
        val service: String = "media-webhook-notifier",
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun handleHealth(call: ApplicationCall) {
        call.respond(HttpStatusCode.OK, HealthStatusDto())
    }

    suspend fun handleSonarrSchema(call: ApplicationCall) {
        call.respondText(SONARR_SCHEMA_JSON, ContentType.Application.Json)
    }

    suspend fun handleRadarrSchema(call: ApplicationCall) {
        call.respondText(RADARR_SCHEMA_JSON, ContentType.Application.Json)
    }

    suspend fun handlePlexSchema(call: ApplicationCall) {
        call.respondText(PLEX_SCHEMA_JSON, ContentType.Application.Json)
    }

    suspend fun handleJellyfinSchema(call: ApplicationCall) {
        call.respondText(JELLYFIN_SCHEMA_JSON, ContentType.Application.Json)
    }

    companion object {
        private val SONARR_SCHEMA_JSON =
            """
            {
              "type": "object",
              "properties": {
                "eventType": { "type": "string", "enum": ["Grab", "Download", "Test"] },
                "series": { "type": "object" },
                "episodes": { "type": "array" },
                "release": { "type": "object" },
                "downloadId": { "type": "string" },
                "isUpgrade": { "type": "boolean" }
              },
              "required": ["eventType"]
            }
            """.trimIndent()

        private val RADARR_SCHEMA_JSON =
            """
            {
              "type": "object",
              "properties": {
                "eventType": { "type": "string", "enum": ["Grab", "Download", "Test"] },
                "movie": { "type": "object" },
                "release": { "type": "object" },
                "downloadId": { "type": "string" },
                "isUpgrade": { "type": "boolean" }
              },
              "required": ["eventType"]
            }
            """.trimIndent()

        private val PLEX_SCHEMA_JSON =
            """
            {
              "type": "object",
              "properties": {
                "event": { "type": "string", "enum": ["library.new"] },
                "Server": { "type": "object" },
                "Metadata": { "type": "object" }
              },
              "required": ["event", "Metadata"]
            }
            """.trimIndent()

        private val JELLYFIN_SCHEMA_JSON =
            """
            {
              "type": "object",
              "properties": {
                "NotificationType": { "type": "string", "enum": ["ItemAdded"] },
                "ItemId": { "type": "string" },
                "Name": { "type": "string" }
              },
              "required": ["NotificationType", "ItemId"]
            }
            """.trimIndent()
    }
}
