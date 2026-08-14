package app.hononeko.notifier.adapter.inbound.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import java.security.MessageDigest

object AuthGuard {
    private const val BEARER_PREFIX = "Bearer "

    fun isAuthorized(
        call: ApplicationCall,
        expectedToken: String?
    ): Boolean {
        if (expectedToken.isNullOrBlank()) {
            return true
        }

        val expectedTokens =
            expectedToken
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        if (expectedTokens.isEmpty()) {
            return true
        }

        val candidateToken = extractCandidateToken(call) ?: return false
        val candidateBytes = candidateToken.toByteArray(Charsets.UTF_8)

        return expectedTokens.any { expected ->
            val expectedBytes = expected.toByteArray(Charsets.UTF_8)
            MessageDigest.isEqual(candidateBytes, expectedBytes)
        }
    }

    fun extractCandidateToken(call: ApplicationCall): String? {
        val authHeader = call.request.header("Authorization")
        if (!authHeader.isNullOrBlank()) {
            return if (authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
                authHeader.substring(BEARER_PREFIX.length).trim()
            } else {
                authHeader.trim()
            }
        }

        val apiKeyHeader = call.request.header("X-Api-Key")
        if (!apiKeyHeader.isNullOrBlank()) {
            return apiKeyHeader.trim()
        }

        val tokenQuery = call.request.queryParameters["token"]
        if (!tokenQuery.isNullOrBlank()) {
            return tokenQuery.trim()
        }

        val apiKeyQuery = call.request.queryParameters["apikey"]
        if (!apiKeyQuery.isNullOrBlank()) {
            return apiKeyQuery.trim()
        }

        val pathToken = call.parameters["token"]
        if (!pathToken.isNullOrBlank()) {
            return pathToken.trim()
        }

        return null
    }

    fun extractCallerName(call: ApplicationCall): String? {
        val callerQuery = call.request.queryParameters["caller"]
        if (!callerQuery.isNullOrBlank()) {
            return callerQuery.trim()
        }

        val instanceQuery = call.request.queryParameters["instance"]
        if (!instanceQuery.isNullOrBlank()) {
            return instanceQuery.trim()
        }

        val callerHeader = call.request.header("X-Caller-Name")
        if (!callerHeader.isNullOrBlank()) {
            return callerHeader.trim()
        }

        val instanceHeader = call.request.header("X-Instance-Name")
        if (!instanceHeader.isNullOrBlank()) {
            return instanceHeader.trim()
        }

        return null
    }
}
