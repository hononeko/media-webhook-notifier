package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either

/** Acts on requests and issues in the media request manager (Seerr / Overseerr / Jellyseerr). */
interface MediaRequestManagerPort {
    suspend fun approveRequest(requestId: Long): Either<DomainError.RequestManagerError, Unit>

    suspend fun declineRequest(requestId: Long): Either<DomainError.RequestManagerError, Unit>

    suspend fun resolveIssue(issueId: Long): Either<DomainError.RequestManagerError, Unit>
}
