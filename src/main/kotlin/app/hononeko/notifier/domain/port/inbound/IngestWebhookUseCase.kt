package app.hononeko.notifier.domain.port.inbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import arrow.core.Either

interface IngestWebhookUseCase {
    suspend fun execute(payload: MediaPayload): Either<DomainError, Unit>
}
