package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto

internal object ReleaseSelector {
    fun eligibleReleases(
        releases: List<GitHubReleaseDto>,
        channel: UpdateChannel
    ): List<GitHubReleaseDto> = releases
        .asSequence()
        .filterNot(GitHubReleaseDto::draft)
        .mapNotNull { release ->
            val version = releaseVersion(release) ?: return@mapNotNull null
            ReleaseCandidate(release = release, version = version)
        }
        // Only GitHub's pre-release flag hides a release from stable.
        .filter { candidate -> channel == UpdateChannel.BETA || !candidate.release.prerelease }
        .sortedByDescending(ReleaseCandidate::version)
        .map(ReleaseCandidate::release)
        .toList()

    private fun releaseVersion(release: GitHubReleaseDto): ReleaseVersion? =
        VersionUtils.parseRelease(release.tagName) ?: VersionUtils.parseRelease(release.name)

    private data class ReleaseCandidate(
        val release: GitHubReleaseDto,
        val version: ReleaseVersion
    )
}
