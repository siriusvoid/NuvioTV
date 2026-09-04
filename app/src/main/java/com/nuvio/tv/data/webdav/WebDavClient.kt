package com.nuvio.tv.data.webdav

import android.util.Log
import com.nuvio.tv.data.locallibrary.subtitle.SubtitleFilenameParser
import com.nuvio.tv.domain.model.webdav.WebDavConnectionResult

/** What a direct check said about a folder the listing did not mention. */
internal enum class WebDavExistence { PRESENT, GONE, UNKNOWN }

/**
 * The WebDAV verbs this feature needs, over [WebDavHttp].
 */
internal class WebDavClient(
    private val http: WebDavHttp,
    private val baseUrl: String,
    username: String,
    password: String
) {
    private val authHeader: String? =
        if (username.isBlank() && password.isBlank()) {
            null
        } else {
            WebDavUrl.basicAuthHeader(username, password)
        }

    /** Headers a player needs to fetch a file from this server. */
    fun playbackHeaders(): Map<String, String> =
        authHeader?.let { mapOf("Authorization" to it) }.orEmpty()

    /**
     * Lists one directory. [path] is decoded and relative to the source base URL.
     * The directory's own entry is dropped so callers only see children.
     */
    suspend fun listDirectory(path: String): Result<List<DavEntry>> {
        val url = WebDavUrl.buildUrl(baseUrl, path)
        val response = runCatching {
            http.request(
                method = "PROPFIND",
                url = url,
                headers = propfindHeaders(),
                body = PROPFIND_BODY,
                followRedirects = true,
                maxResponseBodyBytes = MAX_LISTING_BYTES
            )
        }.getOrElse { error ->
            Log.w(TAG, "PROPFIND failed for $url", error)
            return Result.failure(error)
        }

        // Logged so a failure can be diagnosed from the device log. The credential
        // value is never logged, only whether one was attached.
        Log.i(
            TAG,
            "PROPFIND $url auth=${if (authHeader != null) "basic" else "none"} " +
                "-> ${response.status} (${response.body.length} bytes)"
        )

        if (response.status !in 200..299) {
            Log.w(TAG, "PROPFIND $url failed: ${response.body.take(300)}")
            return Result.failure(
                IllegalStateException(describeStatus(response.status, response.body))
            )
        }

        val entries = runCatching { WebDavXml.parseMultistatus(response.body) }
            .getOrElse { error ->
                Log.w(TAG, "Could not parse multistatus for $url", error)
                return Result.failure(error)
            }

        val requestPath = WebDavUrl.decode(WebDavUrl.pathOf(url)).trim('/')
        val children = entries.filter { entry ->
            val entryPath = WebDavUrl.decode(WebDavUrl.pathOf(entry.href))
            entryPath.isNotEmpty() && entryPath.trim('/') != requestPath
        }
        return Result.success(children)
    }

    suspend fun testConnection(path: String): WebDavConnectionResult =
        listDirectory(path).fold(
            onSuccess = { WebDavConnectionResult.Success(it.size) },
            onFailure = { error ->
                WebDavConnectionResult.Failure(error.message ?: "Could not reach the server")
            }
        )

    /**
     * Whether one folder still holds anything on the server.
     *
     * Real-Debrid does not 404 a deleted torrent — it answers 207 with a synthetic
     * directory entry — so status cannot tell deleted from live. Contents can: a
     * deleted folder lists no children. Hence Depth 1 and a child count.
     *
     * Anything inconclusive is [WebDavExistence.UNKNOWN] and keeps the folder.
     * Reading a timeout or a 5xx as deletion would turn an outage into a wiped
     * library.
     */
    suspend fun folderContents(path: String): WebDavExistence {
        val url = WebDavUrl.buildUrl(baseUrl, path)
        val response = runCatching {
            http.request(
                method = "PROPFIND",
                url = url,
                headers = propfindHeaders(),
                body = PROPFIND_BODY,
                // A dead path redirecting to something live would read as present.
                followRedirects = false,
                maxResponseBodyBytes = MAX_EXISTENCE_BYTES
            )
        }.getOrElse { return WebDavExistence.UNKNOWN }

        if (response.status == 404) return WebDavExistence.GONE
        if (response.status !in 200..299) return WebDavExistence.UNKNOWN

        val self = WebDavUrl.decode(path).trim('/')
        val children = runCatching {
            WebDavXml.parseMultistatus(response.body)
                .count { it.decodedPathRelativeTo("").trim('/') != self }
        }.getOrElse { return WebDavExistence.UNKNOWN }

        return if (children == 0) WebDavExistence.GONE else WebDavExistence.PRESENT
    }

    private fun propfindHeaders(): Map<String, String> = buildMap {
        authHeader?.let { put("Authorization", it) }
        put("Depth", "1")
        put("Content-Type", "application/xml; charset=utf-8")
        put("Accept", "application/xml, text/xml")
    }

    /**
     * Failure text carries the status code and whatever the server said, so a
     * failed connection is diagnosable from the screen instead of by guesswork.
     */
    private fun describeStatus(status: Int, body: String): String {
        val explanation = when (status) {
            401 -> "no credentials reached the server"
            403 -> "the credentials were not accepted"
            404 -> "that path does not exist"
            405 -> "the server does not allow PROPFIND here"
            429 -> "the server is rate limiting, try again shortly"
            in 500..599 -> "the server had an internal error"
            else -> "the request was rejected"
        }
        val serverMessage = serverMessageFrom(body)
        return buildString {
            append("HTTP ")
            append(status)
            append(" — ")
            append(explanation)
            if (serverMessage != null) {
                append(". Server said: ")
                append(serverMessage)
            }
            append(".")
        }
    }

    /** Pulls the human-readable part out of a DAV error body, when there is one. */
    private fun serverMessageFrom(body: String): String? {
        if (body.isBlank()) return null
        val message = MESSAGE_ELEMENT.find(body)?.groupValues?.get(1)
            ?: EXCEPTION_ELEMENT.find(body)?.groupValues?.get(1)
        return message?.trim()?.takeIf { it.isNotBlank() }?.take(120)
    }

    private companion object {
        const val TAG = "WebDavClient"
        const val MAX_LISTING_BYTES = 16L * 1024L * 1024L

        // A live folder answers with all its files, so this has to fit a real listing.
        const val MAX_EXISTENCE_BYTES = 1024L * 1024L

        val MESSAGE_ELEMENT = Regex("<[^>]*message[^>]*>([\\s\\S]*?)</[^>]*message[^>]*>")
        val EXCEPTION_ELEMENT = Regex("<[^>]*exception[^>]*>([\\s\\S]*?)</[^>]*exception[^>]*>")

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <propfind xmlns="DAV:">
              <prop>
                <resourcetype/>
                <getcontentlength/>
                <getcontenttype/>
                <getlastmodified/>
                <displayname/>
              </prop>
            </propfind>
        """.trimIndent()
    }
}

/** Extensions that are worth indexing as playable video. */
internal val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "m4v", "mov", "wmv", "flv", "webm",
    "ts", "m2ts", "mpg", "mpeg", "ogm", "rmvb", "divx"
)

internal fun String.fileExtension(): String =
    substringAfterLast('.', missingDelimiterValue = "").lowercase()

internal fun String.isVideoFile(): Boolean = fileExtension() in VIDEO_EXTENSIONS

/**
 * Indexed sidecars are exactly the ones the player can render, since they are
 * offered as external subtitle tracks on the stream rather than merely counted.
 */
internal fun String.isSubtitleFile(): Boolean =
    fileExtension() in SubtitleFilenameParser.SUBTITLE_EXTENSIONS

/** Release extras that are not the content itself. */
internal fun String.looksLikeSample(): Boolean {
    val lower = lowercase()
    return lower.contains("sample") || lower.contains("trailer") || lower.contains("preview")
}

/** Decoded final path segment of a href, which is the entry's name. */
internal fun DavEntry.decodedName(): String {
    displayName?.takeIf { it.isNotBlank() }?.let { return it }
    val path = WebDavUrl.pathOf(href).trim('/')
    return WebDavUrl.decode(path.substringAfterLast('/'))
}

/** Decoded path of a href relative to a source root, without leading or trailing slash. */
internal fun DavEntry.decodedPathRelativeTo(rootPath: String): String {
    val full = WebDavUrl.decode(WebDavUrl.pathOf(href)).trim('/')
    val root = rootPath.trim('/')
    return when {
        root.isEmpty() -> full
        full.startsWith("$root/") -> full.removePrefix("$root/")
        full == root -> ""
        else -> full
    }
}
