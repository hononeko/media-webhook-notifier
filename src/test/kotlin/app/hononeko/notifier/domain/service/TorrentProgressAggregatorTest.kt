package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentProgressAggregatorTest {
    private fun torrent(
        name: String,
        ratio: Double,
        totalSize: Long,
        state: TorrentState = TorrentState.DOWNLOADING,
        tags: List<String> = emptyList()
    ) = TorrentProgress(
        hash = name.lowercase(),
        name = name,
        progressPercent = ratio * 100.0,
        progressRatio = ratio,
        downloadSpeedBytesPerSec = 100,
        uploadSpeedBytesPerSec = 10,
        etaSeconds = (totalSize * (1 - ratio)).toLong(),
        totalSizeBytes = totalSize,
        downloadedBytes = (totalSize * ratio).toLong(),
        seedsCount = name.length,
        state = state,
        tags = tags
    )

    @Test
    fun `should combine sizes, speeds and tags and sort child items by episode`() {
        val aggregate =
            TorrentProgressAggregator.aggregate(
                hash = "e3|e1",
                torrents =
                    listOf(
                        torrent("Show.S01E03", ratio = 0.5, totalSize = 1000, tags = listOf("mwn_msg:1")),
                        torrent("Show.S01E01", ratio = 1.0, totalSize = 3000, tags = listOf("mwn_msg:1", "tv"))
                    )
            )

        assertEquals("e3|e1", aggregate.hash)
        assertEquals("Show.S01E01", aggregate.name)
        assertEquals(4000L, aggregate.totalSizeBytes)
        assertEquals(3500L, aggregate.downloadedBytes)
        assertEquals(87.5, aggregate.progressPercent, 0.001)
        assertEquals(200L, aggregate.downloadSpeedBytesPerSec)
        assertEquals(20L, aggregate.uploadSpeedBytesPerSec)
        assertEquals(500L, aggregate.etaSeconds)
        assertEquals(listOf("Show.S01E01", "Show.S01E03"), aggregate.items.map { it.name })
        assertEquals(listOf("mwn_msg:1", "tv"), aggregate.tags)
    }

    @Test
    fun `should fall back to average ratio when sizes are unknown`() {
        val aggregate =
            TorrentProgressAggregator.aggregate(
                hash = "a|b",
                torrents = listOf(torrent("A", ratio = 0.2, totalSize = 0), torrent("B", ratio = 0.6, totalSize = 0))
            )

        assertEquals(40.0, aggregate.progressPercent, 0.001)
    }

    @Test
    fun `should resolve aggregate state from child states`() {
        fun stateOf(vararg states: TorrentState): TorrentState =
            TorrentProgressAggregator
                .aggregate(
                    hash = "multi",
                    torrents = states.mapIndexed { i, s -> torrent("T$i", ratio = 0.5, totalSize = 10, state = s) }
                ).state

        assertEquals(TorrentState.COMPLETED, stateOf(TorrentState.COMPLETED, TorrentState.COMPLETED))
        assertEquals(TorrentState.DOWNLOADING, stateOf(TorrentState.COMPLETED, TorrentState.DOWNLOADING))
        assertEquals(TorrentState.ALLOCATING_METADATA, stateOf(TorrentState.ALLOCATING_METADATA, TorrentState.QUEUED))
        assertEquals(TorrentState.CHECKING, stateOf(TorrentState.CHECKING, TorrentState.PAUSED))
        assertEquals(TorrentState.STALLED, stateOf(TorrentState.STALLED, TorrentState.STALLED))
        assertEquals(TorrentState.PAUSED, stateOf(TorrentState.PAUSED, TorrentState.PAUSED))
        assertEquals(TorrentState.QUEUED, stateOf(TorrentState.QUEUED, TorrentState.QUEUED))
        assertEquals(TorrentState.DOWNLOADING, stateOf(TorrentState.STALLED, TorrentState.PAUSED))
    }
}
