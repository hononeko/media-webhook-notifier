package app.hononeko.notifier.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationHandle(
    val providerId: String,
    val channelOrChatId: String,
    val messageReferenceId: String,
    val isPhoto: Boolean = false
)
