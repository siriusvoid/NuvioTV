package com.nuvio.tv.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

internal data class RawHttpResponse(
    val status: Int,
    val body: String
)

/** Raw OkHttp for PROPFIND and XML, which Retrofit can't do; shares the app's client settings. */
@Singleton
internal class WebDavHttp @Inject constructor(
    private val client: OkHttpClient
) {
    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
        followRedirects: Boolean = true,
        maxResponseBodyBytes: Long = DEFAULT_MAX_BODY_BYTES
    ): RawHttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody(XML_MEDIA_TYPE))
        headers.forEach { (name, value) -> builder.header(name, value) }

        val executor = if (followRedirects) client else noRedirectClient
        executor.newCall(builder.build()).execute().use { response ->
            // Read with a size ceiling, and a failed read throws rather than looking like an empty folder.
            RawHttpResponse(
                status = response.code,
                body = response.peekBody(maxResponseBodyBytes).string()
            )
        }
    }

    private companion object {
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaTypeOrNull()
        const val DEFAULT_MAX_BODY_BYTES = 2L * 1024L * 1024L
    }
}
