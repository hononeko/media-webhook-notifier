package app.hononeko.notifier.adapter.inbound.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

object AuthGuard {
    private const val BEARER_PREFIX = "Bearer "

    fun isAuthorized(
        call: ApplicationCall,
        expectedToken: String?
    ): Boolean {
        if (expectedToken.isNullOrBlank()) {
            return true
        }

        val authHeader = call.request.header("Authorization")
        if (authHeader != null) {
            val token =
                if (authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
                    authHeader.substring(BEARER_PREFIX.length).trim()
                } else {
                    authHeader.trim()
                }
            if (token == expectedToken) {
                return true
            }
        }

        val apiKeyHeader = call.request.header("X-Api-Key")
        if (apiKeyHeader != null && apiKeyHeader.trim() == expectedToken) {
            return true
        }

        val tokenQuery = call.request.queryParameters["token"]
        if (tokenQuery != null && tokenQuery.trim() == expectedToken) {
            return true
        }

        val apiKeyQuery = call.request.queryParameters["apikey"]
        if (apiKeyQuery != null && apiKeyQuery.trim() == expectedToken) {
            return true
        }

        return false
    }
}
