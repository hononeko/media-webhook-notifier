package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import org.slf4j.Logger

/**
 * Sends [card] privately to each of [recipients] (the admins). When none of them received it, typically because no
 * admin has started a chat with the bot yet, posts [fallback] to the channel so the event isn't lost.
 */
internal suspend fun NotificationPublisherPort.sendPrivatelyOrToChannel(
    card: NotificationCard,
    recipients: List<String>,
    fallback: NotificationCard,
    logger: Logger
): Either<DomainError.NotificationError, Unit> {
    val results = sendDirect(card, recipients)
    results.forEach { (recipient, result) ->
        result.onLeft { error ->
            logger.warn(
                "Could not send '{}' to admin {}; they may need to start a chat with the bot: {}",
                card.title,
                recipient,
                error
            )
        }
    }
    if (results.values.any { it.isRight() }) return Either.Right(Unit)

    logger.warn("No admin could be reached; posting '{}' to the channel instead", card.title)
    return sendCard(fallback).map { }
}
