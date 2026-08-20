package com.nuvio.tv.data.locallibrary.match

import com.nuvio.tv.domain.model.ContentType

/** Season and episode both set means a TV episode; otherwise a movie. */
data class ParsedFilename(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val contentType: ContentType = if (season != null && episode != null) ContentType.SERIES else ContentType.MOVIE,
    /** Title with no cleanup applied — useful for debugging or display. */
    val originalTitle: String = title
)

/** Extracts title, year and S/E from Plex-style and fansub filenames. */
object FilenameParser {

    private val episodePatterns = listOf(
        // S01E02, S1E2
        Regex("""[Ss](\d{1,2})[._\-\s]?[Ee](\d{1,3})"""),
        // 1x02, 01x02
        Regex("""(?<![A-Za-z\d])(\d{1,2})[xX](\d{1,3})(?![A-Za-z\d])"""),
        // Season 01 Episode 02
        Regex("""(?i)season[._\-\s]*(\d{1,2})[._\-\s]+(?:episode|ep)[._\-\s]*(\d{1,3})""")
    )

    /**
     * Bracketed episode marker, detected before brackets are stripped:
     * "[E02]", "[Ep 02]", "[Episode 2]". Assumes season 1.
     */
    private val bracketEpisodePattern =
        Regex("""[\[(]\s*(?:E|Ep|Episode)[._\-\s]?(\d{1,3})\s*[\])]""", RegexOption.IGNORE_CASE)

    /** Absolute episode number at the end ("Frieren - 01", "One Piece - 1075"); matched after tags are stripped. */
    private val animeEpisodePattern = Regex(
        """(?:\s*[-–]\s*|\s#\s*|\s(?:ep|episode)\s*)(\d{1,4})(?:v\d+)?(?:\s*(?:end|final|fin))?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** A standalone season marker preceding the episode, e.g. "S2", "Season 2". */
    private val seasonHintPattern = Regex("""(?i)\bs(?:eason)?\s*(\d{1,2})\b""")

    /** Episode-shaped tokens that must never be mistaken for a release-group tag. */
    private val episodeTokenPattern =
        Regex("""(?i)^(?:s\d{1,2}e\d{1,3}|e\d{1,3}|ep\d{1,3}|\d{1,2}x\d{1,3})$""")

    private val yearPattern = Regex("""(?<![\dA-Za-z])((?:19|20)\d{2})(?![\dA-Za-z])""")

    private val bracketGroup = Regex("""\[[^\[\]]*\]""")
    private val parenGroup = Regex("""\([^()]*\)""")

    /** Tokens that mark the end of the title — everything from here on is junk. */
    private val junkTokens = listOf(
        "2160p", "1080p", "720p", "480p", "360p",
        "4k", "uhd", "hdr10", "hdr", "dv", "dolby", "hi10p", "10bit", "8bit", "bd", "bdrip",
        "bluray", "blu-ray", "brrip", "webrip", "web-dl", "webdl", "web",
        "hdtv", "hdrip", "dvdrip", "dvdscr", "screener", "remux",
        "x264", "x265", "h264", "h265", "hevc", "av1",
        "ac3", "aac", "dts", "dts-hd", "atmos", "truehd", "flac", "opus", "ddp5", "5.1", "7.1",
        "internal", "proper", "repack", "extended", "uncut", "uncensored", "remastered",
        "batch", "dual", "multi", "vostfr", "subbed", "dubbed", "raw",
        "amzn", "nf", "hulu", "dsnp", "atvp",
        "yify", "yts", "rarbg", "ettv", "eztv", "tgx"
    )

    fun parse(fileName: String): ParsedFilename {
        val noExt = fileName.substringBeforeLast('.', fileName)

        // Read the year before brackets are stripped, so "(2010)" still counts.
        val year = yearPattern.find(noExt)?.groupValues?.get(1)?.toIntOrNull()

        // A bracketed [E02] marker must be read before brackets are removed.
        val bracketEpisode = bracketEpisodePattern.find(noExt)?.groupValues?.get(1)?.toIntOrNull()

        // Group tags, CRCs and quality tags sit in brackets and are never part of the title.
        val deBracketed = noExt.replace(bracketGroup, " ").replace(parenGroup, " ")

        // Trailing "-GROUP" scene tag (no brackets), only if it looks like a release group.
        val noGroup = deBracketed.replace(Regex("""[-\s]+[A-Za-z0-9]+$"""), { match ->
            val tail = match.value.trimStart('-', ' ').trim()
            // A group tag has letters and isn't an episode token, so "Show S01E01" isn't read as a movie.
            if (tail.length <= 12 && tail.all { it.isLetterOrDigit() } &&
                tail.any { it.isLetter() } &&
                !episodeTokenPattern.matches(tail) &&
                (tail.uppercase() == tail || tail.lowercase() == tail) &&
                tail.lowercase() !in setOf("part", "vol", "chapter", "the", "and")
            ) "" else match.value
        })

        val normalized = noGroup.replace('_', ' ').replace('.', ' ')
            .replace(Regex("""\s+"""), " ").trim()

        var season: Int? = null
        var episode: Int? = null
        var titleCut = -1

        // 1) Standard S/E markers.
        for (regex in episodePatterns) {
            val m = regex.find(normalized) ?: continue
            titleCut = m.range.first
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            break
        }

        // 2) Bracketed [E02] (read pre-strip).
        if (episode == null && bracketEpisode != null) {
            season = 1
            episode = bracketEpisode
        }

        // 3) Anime absolute numbering fallback.
        if (episode == null) {
            val anime = animeEpisodePattern.find(normalized)
            val epNum = anime?.groupValues?.get(1)?.toIntOrNull()
            // Reject a number that is actually the release year.
            if (anime != null && epNum != null && !(epNum in 1900..2099 && epNum == year)) {
                episode = epNum
                val before = normalized.substring(0, anime.range.first)
                val seasonHint = seasonHintPattern.findAll(before).lastOrNull()
                season = seasonHint?.groupValues?.get(1)?.toIntOrNull() ?: 1
                titleCut = seasonHint?.range?.first ?: anime.range.first
            }
        }

        // Title is whatever comes before the earliest of: episode marker, year,
        // or first junk token.
        val titleEndCandidates = mutableListOf<Int>()
        if (titleCut >= 0) titleEndCandidates += titleCut
        yearPattern.find(normalized)?.let { titleEndCandidates += it.range.first }
        val lowerNorm = normalized.lowercase()
        for (token in junkTokens) {
            val idx = lowerNorm.indexOf(" $token")
            if (idx >= 0) titleEndCandidates += idx
        }
        val titleEnd = titleEndCandidates.filter { it > 0 }.minOrNull() ?: normalized.length
        var title = normalized.substring(0, titleEnd).trim()

        title = title.trimEnd('-', '–', ' ', '(', '[', ',', ':', '.')
        title = title.replace(Regex("""\s+"""), " ").trim()
        // Drop a dangling season marker that survived (e.g. "Frieren S2")
        title = title.replace(Regex("""(?i)\s+s(?:eason)?\s*\d{1,2}$"""), "").trim()
        // Strip trailing year in parens that survived (e.g. "Inception (2010)")
        title = title.replace(Regex("""\s*[\[(]\s*(?:19|20)\d{2}\s*[\])]\s*$"""), "").trim()

        val type = if (season != null && episode != null) ContentType.SERIES else ContentType.MOVIE

        val fallback = normalized.ifBlank {
            noExt.replace(Regex("""[\[\]()]"""), " ").replace(Regex("""\s+"""), " ").trim()
        }
        return ParsedFilename(
            title = title.ifBlank { fallback },
            year = year,
            season = season,
            episode = episode,
            contentType = type,
            originalTitle = noExt
        )
    }
}
