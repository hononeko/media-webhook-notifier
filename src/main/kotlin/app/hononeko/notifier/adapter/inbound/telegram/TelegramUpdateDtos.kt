package app.hononeko.notifier.adapter.inbound.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Minimal slices of the Bot API `Update` object: only what callback handling reads. Unknown keys are ignored.

@Serializable
internal data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    @SerialName("callback_query")
    val callbackQuery: TelegramCallbackQuery? = null
)

@Serializable
internal data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser,
    val message: TelegramMessage? = null,
    val data: String? = null
)

@Serializable
internal data class TelegramUser(
    val id: Long,
    @SerialName("first_name")
    val firstName: String = "",
    val username: String? = null
)

@Serializable
internal data class TelegramMessage(
    @SerialName("message_id")
    val messageId: Long,
    val chat: TelegramChat
)

@Serializable
internal data class TelegramChat(
    val id: Long,
    val username: String? = null
)
