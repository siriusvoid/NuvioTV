package com.nuvio.tv.core.tmdb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cost of a false positive is showing the addon's title instead of TMDB's, which is what the
 * user wanted anyway. The cost of a false negative is "Эпизод 1" on screen. Both are covered here
 * because the rule is a guess about TMDB's placeholder shape, not a documented contract.
 */
class GeneratedEpisodeTitleTest {

    @Test
    fun `localized placeholders for this episode are treated as generated`() {
        assertTrue(isGeneratedTmdbEpisodeTitle("Эпизод 1", 1))
        assertTrue(isGeneratedTmdbEpisodeTitle("Episode 12", 12))
        assertTrue(isGeneratedTmdbEpisodeTitle("Épisode 3", 3))
        assertTrue(isGeneratedTmdbEpisodeTitle("Folge 7", 7))
        assertTrue(isGeneratedTmdbEpisodeTitle("  Эпизод 5  ", 5))
    }

    /** A placeholder naming a different episode is not this episode's placeholder. */
    @Test
    fun `a number that is not this episode is left alone`() {
        assertFalse(isGeneratedTmdbEpisodeTitle("Эпизод 2", 1))
        assertFalse(isGeneratedTmdbEpisodeTitle("Episode 12", 2))
    }

    @Test
    fun `real titles are left alone`() {
        assertFalse(isGeneratedTmdbEpisodeTitle("Путешествие продолжается", 1))
        assertFalse(isGeneratedTmdbEpisodeTitle("The One Where They Meet", 4))
        assertFalse(isGeneratedTmdbEpisodeTitle("Journey's End", 9))
        // Long enough that it is a title, not a placeholder word.
        assertFalse(isGeneratedTmdbEpisodeTitle("Уходящая в закат глава 1", 1))
    }

    @Test
    fun `missing inputs are not generated`() {
        assertFalse(isGeneratedTmdbEpisodeTitle(null, 1))
        assertFalse(isGeneratedTmdbEpisodeTitle("", 1))
        assertFalse(isGeneratedTmdbEpisodeTitle("Эпизод 1", null))
    }
}
