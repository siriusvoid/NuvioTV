package com.nuvio.tv.data.subtitles

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportedSubtitleMatcherTest {

    @Test
    fun `places a fansub season pack on the first season`() {
        val files = (1..10).map { episode ->
            ImportedSubtitleMatcher.parse("[HorribleSubs] Polar Bear Cafe - ${pad(episode)} [720p].ass")
        }

        val placed = place(files, meta(seasons = mapOf(1 to 12)))

        assertEquals(List(10) { 1 }, placed.map { it.season })
        assertEquals((1..10).toList(), placed.map { it.episode })
        assertEquals("s1e1", placed.first().videoId)
    }

    @Test
    fun `keeps a special in season zero`() {
        val files = listOf(
            ImportedSubtitleMatcher.parse("[ShinkaDan] Kokoro Library - 01 [DVDrip].ass"),
            ImportedSubtitleMatcher.parse("[ShinkaDan] Kokoro Library - SP [DVDrip].ass")
        )

        val placed = place(files, meta(seasons = mapOf(0 to 1, 1 to 13)))

        assertEquals(1, placed[0].season)
        assertEquals(1, placed[0].episode)
        assertEquals(0, placed[1].season)
        assertEquals(1, placed[1].episode)
    }

    @Test
    fun `reads a scene name's own season and episode`() {
        val files = listOf(ImportedSubtitleMatcher.parse("Laid-Back.Camp.S02E03.1080p.srt"))

        val placed = place(files, meta(seasons = mapOf(1 to 12, 2 to 13)))

        assertEquals(2, placed.single().season)
        assertEquals(3, placed.single().episode)
    }

    @Test
    fun `flattens an absolute number onto the season that holds it`() {
        val files = listOf(ImportedSubtitleMatcher.parse("[Group] Long Runner - 15 [1080p].ass"))

        val placed = place(files, meta(seasons = mapOf(1 to 12, 2 to 12)))

        assertEquals(2, placed.single().season)
        assertEquals(3, placed.single().episode)
    }

    @Test
    fun `a folder naming its season places the pack there`() {
        val files = (1..12).map { episode ->
            ImportedSubtitleMatcher.parse("[Group] Second Cour - ${pad(episode)} [1080p].ass")
        }

        val placed = place(
            files = files,
            meta = meta(seasons = mapOf(1 to 12, 2 to 12)),
            seasonHint = ImportedSubtitleMatcher.seasonHint("[Group] Second Cour 2nd Season")
        )

        assertEquals(List(12) { 2 }, placed.map { it.season })
        assertEquals((1..12).toList(), placed.map { it.episode })
    }

    @Test
    fun `a file with no episode number stays unmatched`() {
        val files = listOf(ImportedSubtitleMatcher.parse("readme.srt"))

        val placed = place(files, meta(seasons = mapOf(1 to 12)))

        assertNull(placed.single().videoId)
        assertNull(placed.single().season)
    }

    @Test
    fun `a movie puts every file on the movie's own id`() {
        val files = listOf(ImportedSubtitleMatcher.parse("Perfect Blue.srt"))

        val placed = ImportedSubtitleMatcher.place(
            files = files,
            meta = meta(seasons = emptyMap()),
            seasonHint = null,
            isMovie = true,
            metaId = "tt0156887"
        )

        assertEquals("tt0156887", placed.single().videoId)
        assertNull(placed.single().season)
    }

    @Test
    fun `the release title is the one most file names agree on`() {
        val names = listOf(
            "[Group] Polar Bear Cafe - 01 [720p].ass",
            "[Group] Polar Bear Cafe - 02 [720p].ass",
            "[Other] Something Else - 01 [720p].ass"
        )

        assertEquals("Polar Bear Cafe", ImportedSubtitleMatcher.releaseTitle(names))
    }

    private fun place(
        files: List<ImportedSubtitleFile>,
        meta: Meta,
        seasonHint: Int? = null
    ): List<ImportedSubtitleFile> = ImportedSubtitleMatcher.place(
        files = files,
        meta = meta,
        seasonHint = seasonHint,
        isMovie = false,
        metaId = meta.id
    )

    /** A show whose seasons hold the given number of episodes, ids shaped `s1e1`. */
    private fun meta(seasons: Map<Int, Int>) = Meta(
        id = "tt0000001",
        type = ContentType.SERIES,
        name = "Test Show",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = seasons.flatMap { (season, count) ->
            (1..count).map { episode ->
                Video(
                    id = "s${season}e$episode",
                    title = "Episode $episode",
                    released = null,
                    thumbnail = null,
                    season = season,
                    episode = episode,
                    overview = null
                )
            }
        },
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private fun pad(episode: Int): String = episode.toString().padStart(2, '0')
}
