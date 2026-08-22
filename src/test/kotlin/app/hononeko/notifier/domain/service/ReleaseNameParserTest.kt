package app.hononeko.notifier.domain.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseNameParserTest {
    @Test
    fun `should parse standard scene TV episode release names`() {
        val parsed =
            ReleaseNameParser.parse("Severance.S02E01.Hello.World.1080p.WEB-DL.DDP5.1.Atmos.H.264-FLUX")
        assertEquals("Severance", parsed.title)
        assertEquals(2, parsed.seasonNumber)
        assertEquals(listOf(1), parsed.episodeNumbers)
        assertEquals("Hello World", parsed.episodeTitle)
        assertEquals("1080p", parsed.resolution)
        assertEquals("WEB-DL", parsed.quality)
        assertEquals("FLUX", parsed.releaseGroup)
    }

    @Test
    fun `should parse 4K HDR release with year in title and mkv extension`() {
        val parsed =
            ReleaseNameParser.parse("Silo.2023.S01E01.Freedoms.Day.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.H.265-FLUX.mkv")
        assertEquals("Silo", parsed.title)
        assertEquals(2023, parsed.year)
        assertEquals(1, parsed.seasonNumber)
        assertEquals(listOf(1), parsed.episodeNumbers)
        assertEquals("Freedoms Day", parsed.episodeTitle)
        assertEquals("2160p", parsed.resolution)
        assertEquals("WEB-DL", parsed.quality)
        assertEquals("FLUX", parsed.releaseGroup)
    }

    @Test
    fun `should handle multi-episode releases`() {
        val parsed =
            ReleaseNameParser.parse("Severance.S02E01-E03.1080p.WEB-DL.DDP5.1.Atmos.H.264-FLUX")
        assertEquals("Severance", parsed.title)
        assertEquals(2, parsed.seasonNumber)
        assertEquals(listOf(1, 2, 3), parsed.episodeNumbers)
        assertNull(parsed.episodeTitle)
        assertEquals("1080p", parsed.resolution)
    }

    @Test
    fun `should handle release without episode title`() {
        val parsed =
            ReleaseNameParser.parse("Severance.S02E01.1080p.WEB-DL-FLUX")
        assertEquals("Severance", parsed.title)
        assertEquals(2, parsed.seasonNumber)
        assertEquals(listOf(1), parsed.episodeNumbers)
        assertNull(parsed.episodeTitle)
        assertEquals("1080p", parsed.resolution)
        assertEquals("WEB-DL", parsed.quality)
        assertEquals("FLUX", parsed.releaseGroup)
    }

    @Test
    fun `should parse alternate NxN anime notation`() {
        val parsed =
            ReleaseNameParser.parse("Frieren.01x05.1080p.WEB-DL-SubsPlease")
        assertEquals("Frieren", parsed.title)
        assertEquals(1, parsed.seasonNumber)
        assertEquals(listOf(5), parsed.episodeNumbers)
        assertEquals("1080p", parsed.resolution)
    }

    @Test
    fun `should parse movie releases with year and audio codecs`() {
        val parsed =
            ReleaseNameParser.parse("Dune.Part.Two.2024.2160p.UHD.BluRay.TrueHD.Atmos.x265-FraMeSToR")
        assertEquals("Dune Part Two", parsed.title)
        assertEquals(2024, parsed.year)
        assertNull(parsed.seasonNumber)
        assertEquals(emptyList<Int>(), parsed.episodeNumbers)
        assertNull(parsed.episodeTitle)
        assertEquals("2160p", parsed.resolution)
        assertEquals("BluRay", parsed.quality)
        assertEquals("FraMeSToR", parsed.releaseGroup)
    }

    @Test
    fun `should handle hyphenated and bracketed release formats`() {
        val parsed =
            ReleaseNameParser.parse("Severance - S02E01 - Hello World [1080p]")
        assertEquals("Severance", parsed.title)
        assertEquals(2, parsed.seasonNumber)
        assertEquals(listOf(1), parsed.episodeNumbers)
        assertEquals("Hello World", parsed.episodeTitle)
        assertEquals("1080p", parsed.resolution)
    }

    @Test
    fun `should handle blank, null, or edge case strings gracefully`() {
        val emptyParsed = ReleaseNameParser.parse("")
        assertEquals("", emptyParsed.title)

        val nullParsed = ReleaseNameParser.parse(null)
        assertEquals("", nullParsed.title)

        val plainParsed = ReleaseNameParser.parse("JustAString")
        assertEquals("JustAString", plainParsed.title)
    }
}
