package org.blaykie.nativeapp.crossref

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.blaykie.nativeapp.zotero.blaykieUserAgent
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Outcome of a Crossref lookup; never carries anything but the requested DOI's metadata. */
sealed class CrossrefResult {
    data class Success(val work: JsonObject) : CrossrefResult()
    object NotFound : CrossrefResult()
    data class HttpError(val status: Int) : CrossrefResult()
    object NetworkFailure : CrossrefResult()
    object Malformed : CrossrefResult()
    final override fun toString(): String = "CrossrefResult.${javaClass.simpleName}"
}

/** Canonical form for comparisons: lowercase, no doi:/URL prefix, URL-decoded. */
fun canonicalDoi(input: String): String? {
    var value = input.trim()
    if (value.isEmpty()) return null
    try { value = URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { }
    value = value.trim().lowercase()
        .removePrefix("https://doi.org/").removePrefix("http://doi.org/")
        .removePrefix("https://dx.doi.org/").removePrefix("http://dx.doi.org/")
        .removePrefix("doi:")
        .trim()
    return value.takeIf { it.startsWith("10.") && '/' in it && it.none { it.isWhitespace() } }
}

/**
 * Looks up one DOI at Crossref. Only the DOI is sent: no key, no cookies, no Zotero data.
 * Used from a background thread.
 */
class CrossrefClient private constructor(private val calls: Call.Factory, private val baseUrl: String) {
    constructor() : this(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
        "https://api.crossref.org/works/",
    )
    companion object {
        internal fun forTesting(calls: Call.Factory, baseUrl: String): CrossrefClient = CrossrefClient(calls, baseUrl)
    }
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(doi: String): CrossrefResult {
        val canonical = canonicalDoi(doi) ?: return CrossrefResult.Malformed
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment(canonical).build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", blaykieUserAgent())
            .build()
        val response = try { calls.newCall(request).execute() } catch (_: IOException) { return CrossrefResult.NetworkFailure }
        return response.use {
            when {
                it.code == 404 -> CrossrefResult.NotFound
                !it.isSuccessful -> CrossrefResult.HttpError(it.code)
                else -> try {
                    val root = json.parseToJsonElement(it.body?.string() ?: "").jsonObject
                    CrossrefResult.Success(root["message"]!!.jsonObject)
                } catch (_: Exception) { CrossrefResult.Malformed }
            }
        }
    }
}
