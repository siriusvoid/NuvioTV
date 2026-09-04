package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.LocalLibraryGateway
import com.nuvio.tv.domain.repository.WebDavGateway
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The Local Library and the WebDAV library are synthesised on every read and prepended to the
 * installed list; they are not installed URLs. Storing one anyway produced a second, unresolvable
 * row for the same library, because canonicalizeUrl rewrites "nuvio-webdav://" as "nuvio-webdav:" -
 * a spelling no gateway recognises. Storing it twice crashed the addon manager outright, whose
 * LazyColumn keys its items by baseUrl:
 *
 *     IllegalArgumentException: Key "nuvio-webdav:" was already used.
 *
 * The reorder path wrote them: the addon manager reorders the list it renders, synthetic rows
 * included, and hands every baseUrl back as the new order.
 *
 * Every test runs on an unconfined test dispatcher so the repository's flows settle at their
 * launch points, rather than racing real IO threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonSyntheticUrlTest {

    private val addonUrl = "https://addon.example"

    /** How canonicalizeUrl spells the synthetic URL once the trailing slashes are trimmed. */
    private val storedWebDavUrl = "nuvio-webdav:"

    @Test
    fun `a stored synthetic URL is not listed beside the library it names`() = runTest {
        val harness = newRepository(
            storedUrls = listOf(storedWebDavUrl, addonUrl),
            webDavAddon = syntheticWebDavAddon()
        )

        val addons = harness.repository.getInstalledAddons()
            .first { list -> list.any { it.baseUrl == addonUrl } }

        assertEquals(
            listOf(WebDavGateway.SYNTHETIC_BASE_URL, addonUrl),
            addons.map { it.baseUrl }
        )
    }

    /**
     * The crash itself: two stored spellings of one library resolved to two rows sharing a baseUrl.
     * Asserting on the keys rather than on the size is what ties this to the LazyColumn contract.
     */
    @Test
    fun `a repeated synthetic URL does not produce rows sharing a key`() = runTest {
        val harness = newRepository(
            storedUrls = listOf(storedWebDavUrl, storedWebDavUrl, addonUrl),
            webDavAddon = syntheticWebDavAddon()
        )

        val keys = harness.repository.getInstalledAddons()
            .first { list -> list.any { it.baseUrl == addonUrl } }
            .map { it.baseUrl }

        assertEquals(keys.distinct(), keys)
    }

    @Test
    fun `reordering does not store the synthetic rows it was handed`() = runTest {
        val harness = newRepository(storedUrls = listOf(addonUrl))

        // The list the addon manager renders, in the order getInstalledAddons() emits it.
        harness.repository.setAddonOrder(
            listOf(
                LocalLibraryGateway.SYNTHETIC_BASE_URL,
                WebDavGateway.SYNTHETIC_BASE_URL,
                addonUrl
            )
        )

        coVerify(exactly = 1) { harness.preferences.setAddonOrder(listOf(addonUrl)) }
    }

    private fun syntheticWebDavAddon(): Addon = Addon(
        id = WebDavGateway.ADDON_ID,
        name = WebDavGateway.ADDON_NAME,
        version = "1.0.1",
        description = null,
        logo = null,
        baseUrl = WebDavGateway.SYNTHETIC_BASE_URL,
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList()
    )

    private data class Harness(
        val repository: AddonRepositoryImpl,
        val preferences: AddonPreferences
    )

    private fun TestScope.newRepository(
        storedUrls: List<String>,
        webDavAddon: Addon? = null
    ): Harness {
        // Unreachable, so a real URL resolves to a placeholder row rather than needing a manifest.
        val api = mockk<AddonApi>()
        coEvery { api.getManifest(any()) } throws IOException("offline")

        val preferences = mockk<AddonPreferences>()
        every { preferences.installedAddonUrls } returns flowOf(storedUrls)
        every { preferences.userSetNames } returns flowOf(emptyMap())
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())
        coEvery { preferences.setAddonOrder(any()) } returns true

        val localLibraryGateway = mockk<LocalLibraryGateway>()
        every { localLibraryGateway.synthesizeAddon() } returns flowOf(null)
        every { localLibraryGateway.isLocalLibrary(any(), any()) } returns false
        every { localLibraryGateway.isLocalId(any()) } returns false

        val webDavGateway = mockk<WebDavGateway>()
        every { webDavGateway.synthesizeAddon() } returns flowOf(webDavAddon)
        every { webDavGateway.isWebDavAddon(any(), any()) } returns false

        return Harness(
            repository = AddonRepositoryImpl(
                api = api,
                preferences = preferences,
                addonSyncService = mockk<AddonSyncService>(relaxed = true),
                authManager = mockk<AuthManager>(relaxed = true),
                localLibraryGateway = localLibraryGateway,
                webDavGateway = webDavGateway,
                context = mockk<Context>(relaxed = true),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                clock = System::currentTimeMillis
            ),
            preferences = preferences
        )
    }
}
