package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import arrow.core.Either

/**
 * The download-client tags that let a restarted notifier find a download's live card again: the message, whether it
 * went out as a photo, and the chat. [attach] writes them when tracking starts; [cleanup] removes them when it ends.
 */
internal class TrackingTags(
    private val downloadClient: DownloadClientPort,
    private val tagPrefix: String
) {
    suspend fun attach(
        hash: String,
        handle: NotificationHandle,
        isPhoto: Boolean
    ) {
        val tags =
            buildList {
                add("${tagPrefix}msg:${handle.messageReferenceId}")
                add("${tagPrefix}photo:${if (isPhoto) 1 else 0}")
                if (handle.channelOrChatId.isNotBlank()) {
                    add("${tagPrefix}chat:${handle.channelOrChatId}")
                }
            }
        downloadClient.addTags(hash, tags)
    }

    suspend fun cleanup(
        hash: String,
        handle: NotificationHandle
    ) {
        val currentProgress = downloadClient.getProgress(hash)
        val currentTags =
            when (currentProgress) {
                is Either.Right -> currentProgress.value?.tags ?: emptyList()
                is Either.Left -> emptyList()
            }

        val mwnTagsOnTorrent = currentTags.filter { it.startsWith(tagPrefix) }
        val tagsToRemove =
            buildSet {
                addAll(mwnTagsOnTorrent)
                if (handle.messageReferenceId.isNotBlank()) {
                    add("${tagPrefix}msg:${handle.messageReferenceId}")
                }
                add("${tagPrefix}photo:0")
                add("${tagPrefix}photo:1")
                if (handle.channelOrChatId.isNotBlank()) {
                    add("${tagPrefix}chat:${handle.channelOrChatId}")
                }
            }.toList()

        downloadClient.removeTags(hash, tagsToRemove)

        val tagsToDelete =
            buildSet {
                if (handle.messageReferenceId.isNotBlank()) {
                    add("${tagPrefix}msg:${handle.messageReferenceId}")
                }
                addAll(mwnTagsOnTorrent.filter { it.startsWith("${tagPrefix}msg:") })
            }.toList()

        if (tagsToDelete.isNotEmpty()) {
            downloadClient.deleteTags(tagsToDelete)
        }
    }
}
