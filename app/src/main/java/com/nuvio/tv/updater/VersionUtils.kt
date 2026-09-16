package com.nuvio.tv.updater

internal data class SemanticVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val sharedSize = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until sharedSize) {
            comparePrereleaseIdentifier(
                prerelease[index],
                other.prerelease[index]
            ).takeIf { it != 0 }?.let { return it }
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)

        if (leftNumeric && rightNumeric) {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            compareValues(normalizedLeft.length, normalizedRight.length)
                .takeIf { it != 0 }
                ?.let { return it }
            return normalizedLeft.compareTo(normalizedRight)
        }
        if (leftNumeric) return -1
        if (rightNumeric) return 1
        return left.compareTo(right)
    }
}

internal data class ReleaseVersion(
    val version: SemanticVersion,
    val build: Long
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValues(version, other.version).takeIf { it != 0 }
            ?: compareValues(build, other.build)
}

internal object VersionUtils {
    private val versionPattern = Regex(
        "^(\\d+)\\.(\\d+)\\.(\\d+)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    )
    private val buildSuffixPattern = Regex("^(.+)-(\\d+)$")

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parse(raw: String?): SemanticVersion? {
        val match = versionPattern.matchEntire(normalize(raw)) ?: return null
        return SemanticVersion(
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].toLongOrNull() ?: return null,
            prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
        )
    }

    fun isPrerelease(raw: String?): Boolean = parse(raw)?.prerelease?.isNotEmpty() == true

    // Fork release tags end in the CI run number: 0.9.4-beta-42, 1.0.0-42.
    fun parseRelease(raw: String?): ReleaseVersion? {
        val normalized = normalize(raw)
        buildSuffixPattern.matchEntire(normalized)?.let { match ->
            val version = parse(match.groupValues[1])
            val build = match.groupValues[2].toLongOrNull()
            if (version != null && build != null) return ReleaseVersion(version, build)
        }
        return parse(normalized)?.let { ReleaseVersion(it, build = 0) }
    }

    fun isRemoteNewer(remote: String?, local: String?, localBuild: Long = 0): Boolean {
        val remoteVersion = parseRelease(remote) ?: return false
        val localVersion = parse(local)?.let { ReleaseVersion(it, localBuild) } ?: return false
        return remoteVersion > localVersion
    }
}
