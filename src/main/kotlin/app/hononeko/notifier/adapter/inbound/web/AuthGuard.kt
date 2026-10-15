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
        // A present Authorization header wins even if only "Bearer " is left after the prefix.
        val authHeader = call.request.header("Authorization")
        if (!authHeader.isNullOrBlank()) {
            val token =
                if (authHeader.startsWith(
                        BEARER_PREFIX,
                        ignoreCase = true
                    )
                ) {
                    authHeader.substring(BEARER_PREFIX.length)
                } else {
                    authHeader
                }
            return token.trim()
        }
        return listOf(
            call.request.header("X-Api-Key"),
            call.request.queryParameters["token"],
            call.request.queryParameters["apikey"],
            call.parameters["token"]
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    fun extractCallerName(call: ApplicationCall): String? {
        val instanceQuery = call.request.queryParameters["instance"]
        if (!instanceQuery.isNullOrBlank()) {
            return instanceQuery.trim()
        }

        val instanceHeader = call.request.header("X-Instance-Name")
        if (!instanceHeader.isNullOrBlank()) {
            return instanceHeader.trim()
        }

        return null
    }
}
