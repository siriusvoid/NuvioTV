package com.nuvio.tv.data.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A mapping must survive into the next process, and a damaged file must degrade to a fresh lookup. */
class TmdbIdMappingStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): TmdbIdMappingStore {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        return TmdbIdMappingStore(context)
    }

    @Test
    fun `mappings written by one instance are readable by the next`() = runTest {
        newStore().save(
            TmdbIdMappingStore.Snapshot(
                imdbToTmdb = mapOf("tt22248376:tv" to "209867"),
                tmdbToImdb = mapOf("209867:tv" to "tt22248376")
            )
        )

        // A separate instance, as a later process would be.
        val loaded = newStore().load()

        assertEquals("209867", loaded.imdbToTmdb["tt22248376:tv"])
        assertEquals("tt22248376", loaded.tmdbToImdb["209867:tv"])
    }

    @Test
    fun `an absent file loads as empty rather than failing`() = runTest {
        val loaded = newStore().load()

        assertTrue(loaded.imdbToTmdb.isEmpty())
        assertTrue(loaded.tmdbToImdb.isEmpty())
    }

    /** A half-written file from a mid-save kill must read as a cold cache, not throw. */
    @Test
    fun `a damaged file loads as empty rather than failing`() = runTest {
        val dir = File(tempFolder.root, "tmdb_ids").apply { mkdirs() }
        File(dir, "id_mappings_v2.json").writeText("{\"imdbToTmdb\": {\"tt1\": ")

        val loaded = newStore().load()

        assertTrue(loaded.imdbToTmdb.isEmpty())
        assertTrue(loaded.tmdbToImdb.isEmpty())
    }

    @Test
    fun `a snapshot past the ceiling is trimmed rather than refused`() = runTest {
        val oversized = (1..4_100).associate { "tt$it:tv" to it.toString() }

        newStore().save(TmdbIdMappingStore.Snapshot(imdbToTmdb = oversized))
        val loaded = newStore().load()

        assertEquals(4_000, loaded.imdbToTmdb.size)
    }
}
