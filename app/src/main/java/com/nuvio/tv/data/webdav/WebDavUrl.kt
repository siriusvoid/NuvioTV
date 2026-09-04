package com.nuvio.tv.data.webdav

import android.util.Base64
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * URL handling for WebDAV. `href` values in a multistatus body are percent-encoded
 * and may be absolute or origin-relative, so every path that comes back from the
 * server has to be decoded once and re-encoded when it is used again.
 */
internal object WebDavUrl {

    private const val HEX = "0123456789ABCDEF"

    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    fun normalizeRootPath(raw: String): String = raw.trim().trim('/')

    fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.trimEnd('/')
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    /** Decoded path component of an absolute or relative URL, without leading slash. */
    fun pathOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val raw = if (schemeEnd < 0) {
            url
        } else {
            val pathStart = url.indexOf('/', schemeEnd + 3)
            if (pathStart < 0) "" else url.substring(pathStart)
        }
        return raw.substringBefore('?').trim('/')
    }

    /** Absolute URL for a href taken from a multistatus response. */
    fun resolveHref(baseUrl: String, href: String): String {
        val trimmed = href.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed

            trimmed.startsWith("/") -> originOf(baseUrl) + trimmed
            else -> baseUrl.trimEnd('/') + "/" + trimmed
        }
    }

    /** Joins already-decoded path segments onto a base and encodes them once. */
    fun buildUrl(baseUrl: String, vararg segments: String): String {
        val encoded = segments
            .flatMap { it.split('/') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        val base = baseUrl.trimEnd('/')
        // Always end a collection URL with a slash. Without it servers answer a
        // redirect, and a redirected PROPFIND can arrive without its body.
        return if (encoded.isEmpty()) "$base/" else "$base/$encoded/"
    }

    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    /**
     * Percent-encodes one path segment against the RFC 3986 unreserved set.
     * `URLEncoder` is deliberately not used: it is form encoding, so it turns a
     * space into `+` and leaves `*` alone, and both break a WebDAV href.
     */
    fun encodeSegment(segment: String): String = buildString {
        segment.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    fun decode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' && index + 2 < value.length -> {
                    val parsed = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (parsed == null) {
                        bytes.add(char.code.toByte())
                        index++
                    } else {
                        bytes.add(parsed.toByte())
                        index += 3
                    }
                }

                else -> {
                    char.toString().encodeToByteArray().forEach { bytes.add(it) }
                    index++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    fun basicAuthHeader(username: String, password: String): String =
        "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
}

/**
 * Parses an HTTP-date ("Tue, 05 Oct 2012 12:00:00 GMT") to epoch seconds. A few
 * servers answer `getlastmodified` with an ISO-8601 timestamp instead, so that
 * shape is tried as well.
 *
 * Only used for ordering folders newest-first, so a null on an odd format simply
 * sends that folder to the back of the queue rather than failing a scan.
 */
internal fun parseHttpDateToEpochSeconds(value: String?): Long? {
    val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    runCatching {
        DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw, java.time.Instant::from).epochSecond
    }.getOrNull()?.let { return it }
    return runCatching {
        DateTimeFormatter.ISO_DATE_TIME.parse(raw, java.time.Instant::from).epochSecond
    }.getOrNull() ?: parseIsoDateToEpochSeconds(raw)
}

/** ISO-8601 date ("2012-10-05" or a full timestamp) to epoch seconds. */
internal fun parseIsoDateToEpochSeconds(value: String?): Long? {
    val datePart = value?.trim()?.takeIf { it.length >= 10 }?.take(10) ?: return null
    return runCatching {
        LocalDate.parse(datePart).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }.getOrNull()
}
