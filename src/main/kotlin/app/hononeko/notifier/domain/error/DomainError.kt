package app.hononeko.notifier.domain.error

sealed interface DomainError {
    sealed interface WebhookError : DomainError {
        data class Unauthorized(
            val reason: String
        ) : WebhookError

        data class InvalidPayload(
            val details: String
        ) : WebhookError

        data class UnsupportedEventType(
            val event: String
        ) : WebhookError

        data object MissingTorrentHash : WebhookError
    }

    sealed interface TorrentClientError : DomainError {
        data class ConnectionFailed(
            val url: String,
            val cause: Throwable? = null
        ) : TorrentClientError

        data class TorrentNotFound(
            val hash: String
        ) : TorrentClientError

        data class AuthenticationFailed(
            val reason: String
        ) : TorrentClientError

        data class InvalidResponse(
            val details: String
        ) : TorrentClientError
    }

    sealed interface NotificationError : DomainError {
        val provider: String

        data class RateLimited(
            override val provider: String,
            val retryAfterSeconds: Int
        ) : NotificationError

        data class DeliveryFailed(
            override val provider: String,
            val message: String,
            val cause: Throwable? = null
        ) : NotificationError

        data class ConnectionTimeout(
            override val provider: String,
            val timeoutSeconds: Long
        ) : NotificationError

        data class ImageFetchFailed(
            override val provider: String,
            val url: String,
            val cause: Throwable? = null
        ) : NotificationError
    }
}
