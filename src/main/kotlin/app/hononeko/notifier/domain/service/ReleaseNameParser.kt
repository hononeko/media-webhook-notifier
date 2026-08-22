package app.hononeko.notifier.domain.service

data class ParsedRelease(
    val title: String,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumbers: List<Int> = emptyList(),
    val episodeTitle: String? = null,
    val resolution: String? = null,
    val quality: String? = null,
    val releaseGroup: String? = null
)

object ReleaseNameParser {
    // Matches S01E01, S01E01-E03, S01E01-03, S01E01E02, S01, 1x01, Episode 01
    private val SEASON_EPISODE_RANGE_REGEX =
        Regex(
            """(?i)(?:[._\s\-\[\(]|^)S(\d+)(?:[._\s\-]?E(\d+)(?:(?:[._\s\-]?(?:E|\-))(\d+))?|(?:[._\s\-]?E(\d+))+)?(?=[._\s\-\]\)]|$)"""
        )

    private val ALT_EPISODE_REGEX =
        Regex("""(?i)(?:[._\s\-\[\(]|^)(\d+)x(\d+)(?=[._\s\-\]\)]|$)""")

    private val YEAR_REGEX =
        Regex("""(?:[._\s\-\[\(]|^)(19\d{2}|20\d{2})(?=[._\s\-\]\)]|$)""")

    private val RESOLUTION_REGEX =
        Regex("""(?i)(?:[._\s\-\[\(]|^)(2160p|1080p|1080i|720p|576p|480p|4k|uhd)(?=[._\s\-\]\)]|$)""")

    private val QUALITY_REGEX =
        Regex("""(?i)(?:[._\s\-\[\(]|^)(WEB-?DL|WEBRip|BluRay|HDTV|BRRip|DVDRip|DVD|HD-DVD|Remux)(?=[._\s\-\]\)]|$)""")

    private val RELEASE_GROUP_REGEX =
        Regex("""\-(?:\[([^\]]+)\]|([a-zA-Z0-9_]+))(?:\.[a-zA-Z0-9]{2,4})?$""")

    // Cutoff markers where episode title ends and release technical metadata begins
    private val CUTOFF_REGEX =
        Regex(
            """(?i)(?:[._\s\-\[\(]|^)(2160p|1080p|1080i|720p|576p|480p|4k|uhd|web-?dl|webrip|bluray|hdtv|brrip|dvdrip|remux|proper|repack|rerip|real|dirfix|nf|amzn|atvp|dsnp|hmax|max|appletv|disney|hulu|peacock|paramount|h\.?264|h\.?265|x264|x265|hevc|av1|vc-1|aac|ac3|eac3|ddp?5\.?1|dts(?:-hd)?|truehd|atmos|hdr(?:10(?:\+)?|plus)?|dv|dolby[._\s\-]?vision|sub(?:s|bed)?|multi|dual[._\s\-]audio|10bit|8bit|complete|extended|unrated|internal)(?=[._\s\-\]\)]|$)"""
        )

    private val FILE_EXTENSION_REGEX =
        Regex("""\.(?:mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|iso|rar|zip|tar|gz)$""", RegexOption.IGNORE_CASE)

    fun parse(rawReleaseName: String?): ParsedRelease {
        if (rawReleaseName.isNullOrBlank()) {
            return ParsedRelease(title = "")
        }

        val cleanedName = rawReleaseName.trim().replace(FILE_EXTENSION_REGEX, "")

        // 1. Extract Release Group (e.g. -FLUX or -[GROUP])
        val groupMatch = RELEASE_GROUP_REGEX.find(cleanedName)
        val releaseGroup =
            groupMatch
                ?.let {
                    it.groupValues[1].ifBlank { it.groupValues[2] }
                }?.takeIf { it.isNotBlank() }
        val nameWithoutGroup =
            if (groupMatch != null) {
                cleanedName.substring(0, groupMatch.range.first)
            } else {
                cleanedName
            }

        // 2. Extract Resolution
        val resolutionMatch = RESOLUTION_REGEX.find(nameWithoutGroup)
        val resolution =
            resolutionMatch?.groupValues?.get(1)?.lowercase()?.let {
                when (it) {
                    "4k", "uhd" -> "2160p"
                    else -> it
                }
            }

        // 3. Extract Quality
        val qualityMatch = QUALITY_REGEX.find(nameWithoutGroup)
        val quality = qualityMatch?.groupValues?.get(1)

        // 4. Try Season / Episode parsing
        var seasonNum: Int? = null
        val episodeNums = mutableListOf<Int>()
        var seMatchStart = -1
        var seMatchEnd = -1

        val seMatch = SEASON_EPISODE_RANGE_REGEX.find(nameWithoutGroup)
        if (seMatch != null) {
            seasonNum = seMatch.groupValues[1].toIntOrNull()
            val startEp = seMatch.groupValues[2].toIntOrNull()
            val endEp = seMatch.groupValues[3].toIntOrNull()
            if (startEp != null && endEp != null && endEp >= startEp) {
                episodeNums.addAll(startEp..endEp)
            } else if (startEp != null) {
                episodeNums.add(startEp)
            }
            seMatchStart = seMatch.range.first
            seMatchEnd = seMatch.range.last + 1
        } else {
            val altMatch = ALT_EPISODE_REGEX.find(nameWithoutGroup)
            if (altMatch != null) {
                seasonNum = altMatch.groupValues[1].toIntOrNull()
                altMatch.groupValues[2].toIntOrNull()?.let { episodeNums.add(it) }
                seMatchStart = altMatch.range.first
                seMatchEnd = altMatch.range.last + 1
            }
        }

        // 5. Extract Title (before SxxExx or before Year / Cutoff)
        val rawTitlePart =
            if (seMatchStart > 0) {
                nameWithoutGroup.substring(0, seMatchStart)
            } else {
                val firstCutoff =
                    listOfNotNull(
                        YEAR_REGEX.find(nameWithoutGroup)?.range?.first,
                        resolutionMatch?.range?.first,
                        qualityMatch?.range?.first,
                        CUTOFF_REGEX.find(nameWithoutGroup)?.range?.first
                    ).minOrNull()
                if (firstCutoff != null && firstCutoff > 0) {
                    nameWithoutGroup.substring(0, firstCutoff)
                } else {
                    nameWithoutGroup
                }
            }

        // Extract year from nameWithoutGroup
        val yearMatch = YEAR_REGEX.find(nameWithoutGroup)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        var title = cleanText(rawTitlePart)
        if (year != null) {
            // Remove year from title if present at end
            val titleWithoutYear = title.replace(Regex("""\b$year\b.*$"""), "").trim()
            if (titleWithoutYear.isNotBlank()) {
                title = titleWithoutYear
            }
        }

        // 6. Extract Episode Title (between SxxExx and Cutoff markers)
        var episodeTitle: String? = null
        if (seMatchEnd > 0 && seMatchEnd < nameWithoutGroup.length && episodeNums.size == 1) {
            val remainder = nameWithoutGroup.substring(seMatchEnd)
            val cutoffMatch = CUTOFF_REGEX.find(remainder)
            val rawEpTitle =
                if (cutoffMatch != null) {
                    remainder.substring(0, cutoffMatch.range.first)
                } else {
                    remainder
                }

            val cleanedEpTitle = cleanText(rawEpTitle)
            if (cleanedEpTitle.isNotBlank() &&
                !cleanedEpTitle.matches(Regex("""(?i)^(?:S\d+E\d+|Episode\s*\d+|E\d+|TBA|TBD)$""")) &&
                cleanedEpTitle.length > 1
            ) {
                episodeTitle = cleanedEpTitle
            }
        }

        return ParsedRelease(
            title = title.ifBlank { cleanText(nameWithoutGroup) },
            year = year,
            seasonNumber = seasonNum,
            episodeNumbers = episodeNums,
            episodeTitle = episodeTitle,
            resolution = resolution,
            quality = quality,
            releaseGroup = releaseGroup
        )
    }

    private fun cleanText(text: String): String =
        text
            .replace(Regex("""[._\s]+"""), " ")
            .replace(Regex("""^[\s\-:\[\]()]+"""), "")
            .replace(Regex("""[\s\-:\[\]()]+$"""), "")
            .trim()
}
