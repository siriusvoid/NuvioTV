package com.nuvio.tv.data.webdav

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.locallibrary.subtitle.SubtitleFilenameParser
import com.nuvio.tv.domain.model.webdav.WebDavConnectionResult

/** What a direct check said about a folder the listing did not mention. */
internal enum class WebDavExistence { PRESENT, GONE, UNKNOWN }

/**
 * The WebDAV verbs this feature needs, over [WebDavHttp].
 */
internal class WebDavClient(
    private val context: Context,
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

    /** Lists one directory's children; [path] is decoded and relative to the source base URL. */
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

        // Logs whether a credential was attached, never its value.
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
                WebDavConnectionResult.Failure(
                    error.message ?: context.getString(R.string.webdav_could_not_reach_server)
                )
            }
        )

    /** Real-Debrid answers 207 for deleted folders, so emptiness is the signal; anything inconclusive keeps it. */
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

    /** Status code and server message, so a failed connection is diagnosable on screen. */
    private fun describeStatus(status: Int, body: String): String {
        val explanation = context.getString(
            when (status) {
                401 -> R.string.webdav_http_401
                403 -> R.string.webdav_http_403
                404 -> R.string.webdav_http_404
                405 -> R.string.webdav_http_405
                429 -> R.string.webdav_http_429
                in 500..599 -> R.string.webdav_http_5xx
                else -> R.string.webdav_http_other
            }
        )
        val serverMessage = serverMessageFrom(body)
        return if (serverMessage != null) {
            context.getString(R.string.webdav_http_error_with_message, status, explanation, serverMessage)
        } else {
            context.getString(R.string.webdav_http_error, status, explanation)
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

/** Only sidecars the player can render, since they're offered as subtitle tracks. */
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
