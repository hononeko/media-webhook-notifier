package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.domain.model.NotificationHandle
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Remembers whether a message was posted as a photo or as text, so edits use the matching endpoint. Keyed by chat and
 * message: message ids are only unique within a chat, and cards also go to admins' private chats. A handle without a
 * chat is in [defaultChatId]. Bounded with LRU eviction to prevent memory leaks during continuous 24/7 uptime.
 */
internal class PhotoMessageRegistry(
    private val defaultChatId: String
) {
    companion object {
        private const val MAX_REGISTRY_SIZE = 2000
    }

    private val entries =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                    size > MAX_REGISTRY_SIZE
            }
        )

    private fun key(handle: NotificationHandle): String =
        "${handle.channelOrChatId.ifBlank { defaultChatId }}:${handle.messageReferenceId}"

    operator fun get(handle: NotificationHandle): Boolean? = entries[key(handle)]

    operator fun set(
        handle: NotificationHandle,
        isPhoto: Boolean
    ) {
        entries[key(handle)] = isPhoto
    }

    fun remove(handle: NotificationHandle) {
        entries.remove(key(handle))
    }
}
