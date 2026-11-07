package org.blaykie.nativeapp.zotero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result descriptions intentionally never include server data or credentials. */
sealed class ZoteroResult<out T> {
    class Success<T>(val value: T) : ZoteroResult<T>()
    object EmptyLibrary : ZoteroResult<Nothing>()
    object InvalidKey : ZoteroResult<Nothing>()
    object NetworkFailure : ZoteroResult<Nothing>()
    object Cancelled : ZoteroResult<Nothing>()
    object MalformedJson : ZoteroResult<Nothing>()
    class RateLimited(val retryAfterSeconds: Long) : ZoteroResult<Nothing>()
    class HttpError(val status: Int) : ZoteroResult<Nothing>()
    final override fun toString(): String = "ZoteroResult.${javaClass.simpleName}"
}

data class KeyPermissions(val personalLibrary: Boolean, val personalFiles: Boolean, val personalNotes: Boolean, val personalWrite: Boolean, val groupAccess: Boolean) {
    val broaderThanPersonalReadOnly: Boolean get() = personalFiles || personalWrite || groupAccess
}
data class VerifiedKey(val userId: Long, val permissions: KeyPermissions)
data class ZoteroItem(val key: String, val data: JsonObject) {
    fun citationItem(): CitationItem {
        fun string(name: String): String = (data[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun creators(kind: String): List<CitationAuthor> = (data["creators"] as? JsonArray).orEmpty().mapNotNull { element ->
            val c = element as? JsonObject ?: return@mapNotNull null
            val role = (c["creatorType"] as? JsonPrimitive)?.contentOrNull
            if (kind == "author" && role != null && role != "author") return@mapNotNull null
            if (kind != "author" && role != kind) return@mapNotNull null
            val last = (c["lastName"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { (c["name"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            if (last.isEmpty()) return@mapNotNull null
            val group = (c["lastName"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()
            CitationAuthor((c["firstName"] as? JsonPrimitive)?.contentOrNull.orEmpty(), last, group)
        }
        val type = string("itemType")
        val originalAuthors = creators("author")
        val editors = creators("editor")
        val editorLead = originalAuthors.isEmpty() && type == "book" && editors.isNotEmpty()
        val authors = if (editorLead) editors else originalAuthors
        return CitationItem(key, string("title"), authors, string("date"), string("publicationTitle"), string("volume"), string("issue"), string("pages"), string("DOI"), type, string("publisher"), string("bookTitle"), string("edition"), editors, string("url"), editorLead)
    }
}

/** Synchronous API; invoke from a background thread. */
class ZoteroClient private constructor(private val calls: Call.Factory, private val sleep: (Long) -> Unit) {
    constructor() : this(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build(),
        { Thread.sleep(it) },
    )
    companion object {
        private val BASE = "https://api.zotero.org/".toHttpUrl()
        private const val MAX_ITEMS = 200
        private const val PAGE_SIZE = 100
        private const val MAX_RETRIES = 3
        private const val MAX_WAIT_SECONDS = 30L
        // Tests may replace the transport, not the production URL.
        internal fun forTesting(calls: Call.Factory, sleep: (Long) -> Unit = {}): ZoteroClient = ZoteroClient(calls, sleep)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private var pendingWaitSeconds = 0L
    private val cancellationLock = Any()
    @Volatile private var cancelled = false
    private var activeCall: Call? = null
    private var waitingThread: Thread? = null

    fun cancel() {
        synchronized(cancellationLock) {
            cancelled = true
            activeCall?.cancel()
            waitingThread?.interrupt()
        }
    }

    private fun retryDelay(vararg headers: String?): Long = headers.mapNotNull { value ->
        val text = value?.trim() ?: return@mapNotNull null
        text.toLongOrNull() ?: try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
            val time = format.parse(text)?.time ?: return@mapNotNull null
            ((time - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
        } catch (_: java.text.ParseException) { null }
    }.maxOrNull()?.coerceIn(0, MAX_WAIT_SECONDS) ?: 0L

    private fun request(path: List<String>, params: Map<String, String>, apiKey: String): ZoteroResult<String> {
        val url = BASE.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = try {
            Request.Builder().url(url).header("Zotero-API-Key", apiKey).header("Zotero-API-Version", "3").build()
        } catch (_: IllegalArgumentException) { return ZoteroResult.InvalidKey }
        for (attempt in 0..MAX_RETRIES) {
            if (cancelled) return ZoteroResult.Cancelled
            if (pendingWaitSeconds > 0) {
                synchronized(cancellationLock) {
                    if (cancelled) return ZoteroResult.Cancelled
                    waitingThread = Thread.currentThread()
                }
                try { sleep(pendingWaitSeconds * 1000) } catch (_: InterruptedException) {
                    if (cancelled) { Thread.interrupted(); return ZoteroResult.Cancelled }
                    Thread.currentThread().interrupt(); return ZoteroResult.NetworkFailure
                } finally { synchronized(cancellationLock) { waitingThread = null } }
                pendingWaitSeconds = 0
            }
            val call = synchronized(cancellationLock) {
                if (cancelled) null else calls.newCall(request).also { activeCall = it }
            } ?: return ZoteroResult.Cancelled
            try {
                val response = try { call.execute() } catch (_: IOException) {
                    return if (cancelled) ZoteroResult.Cancelled else ZoteroResult.NetworkFailure
                }
                val delay = retryDelay(response.header("Backoff"), response.header("Retry-After"))
                if (response.code == 429 || (response.code == 503 && delay > 0)) {
                    response.close()
                    if (cancelled) return ZoteroResult.Cancelled
                    if (attempt == MAX_RETRIES) return ZoteroResult.RateLimited(delay)
                    pendingWaitSeconds = maxOf(delay, 1)
                    continue
                }
                response.use {
                    if (cancelled) return ZoteroResult.Cancelled
                    if (it.code == 401 || it.code == 403) return ZoteroResult.InvalidKey
                    if (!it.isSuccessful) return ZoteroResult.HttpError(it.code)
                    pendingWaitSeconds = delay
                    return try { ZoteroResult.Success(it.body?.string() ?: "") } catch (_: IOException) {
                        if (cancelled) ZoteroResult.Cancelled else ZoteroResult.NetworkFailure
                    }
                }
            } finally {
                synchronized(cancellationLock) { if (activeCall === call) activeCall = null }
            }
        }
        return ZoteroResult.NetworkFailure
    }
    private inline fun <T> parse(result: ZoteroResult<String>, decode: (String) -> T): ZoteroResult<T> = when (result) {
        is ZoteroResult.Success -> try { ZoteroResult.Success(decode(result.value)) } catch (_: Exception) { ZoteroResult.MalformedJson }
        is ZoteroResult.EmptyLibrary -> result
        is ZoteroResult.InvalidKey -> result
        is ZoteroResult.NetworkFailure -> result
        is ZoteroResult.Cancelled -> result
        is ZoteroResult.MalformedJson -> result
        is ZoteroResult.RateLimited -> result
        is ZoteroResult.HttpError -> result
    }
    fun verifyKey(apiKey: String): ZoteroResult<VerifiedKey> = parse(request(listOf("keys", "current"), emptyMap(), apiKey)) { raw ->
        val obj = json.parseToJsonElement(raw) as JsonObject
        val id = (obj["userID"] as JsonPrimitive).content.toLong()
        val access = obj["access"] as JsonObject
        val user = access["user"] as? JsonObject ?: JsonObject(emptyMap())
        fun flag(name: String): Boolean = (user[name] as? JsonPrimitive)?.booleanOrNull ?: false
        val groups = access["groups"] as? JsonObject
        VerifiedKey(id, KeyPermissions(flag("library"), flag("files"), flag("notes"), flag("write"), !groups.isNullOrEmpty()))
    }
    private fun parseItems(raw: String): List<ZoteroItem> = (json.parseToJsonElement(raw) as JsonArray).map { value ->
        val obj = value as JsonObject
        ZoteroItem((obj["key"] as JsonPrimitive).content, obj["data"] as JsonObject)
    }
    fun search(userId: Long, apiKey: String, query: String, limit: Int = MAX_ITEMS): ZoteroResult<List<ZoteroItem>> {
        val maximum = limit.coerceIn(1, MAX_ITEMS)
        val pageSize = minOf(PAGE_SIZE, maximum)
        val collected = mutableListOf<ZoteroItem>()
        // At most 200 server items are examined, even if every item is filtered.
        var start = 0
        while (start < MAX_ITEMS && collected.size < maximum) {
            val params = mapOf("q" to query, "limit" to pageSize.toString(), "start" to start.toString(), "format" to "json")
            when (val page = parse(request(listOf("users", userId.toString(), "items", "top"), params, apiKey), ::parseItems)) {
                is ZoteroResult.Success -> {
                    collected += page.value.filter { (it.data["itemType"] as? JsonPrimitive)?.contentOrNull !in setOf("attachment", "note", "annotation") }
                    if (page.value.size < pageSize) break
                }
                is ZoteroResult.EmptyLibrary -> return page
                is ZoteroResult.InvalidKey -> return page
                is ZoteroResult.NetworkFailure -> return page
                is ZoteroResult.Cancelled -> return page
                is ZoteroResult.MalformedJson -> return page
                is ZoteroResult.RateLimited -> return page
                is ZoteroResult.HttpError -> return page
            }
            start += pageSize
        }
        return if (collected.isEmpty()) ZoteroResult.EmptyLibrary else ZoteroResult.Success(collected.take(maximum))
    }
    fun fetchItems(userId: Long, apiKey: String, keys: List<String>): ZoteroResult<List<ZoteroItem>> {
        if (keys.isEmpty()) return ZoteroResult.Success(emptyList())
        val items = mutableListOf<ZoteroItem>()
        for (group in keys.take(MAX_ITEMS).chunked(50)) {
            val params = mapOf("itemKey" to group.joinToString(","), "limit" to "100", "format" to "json")
            when (val page = parse(request(listOf("users", userId.toString(), "items"), params, apiKey), ::parseItems)) {
                is ZoteroResult.Success -> items += page.value
                is ZoteroResult.EmptyLibrary -> return page
                is ZoteroResult.InvalidKey -> return page
                is ZoteroResult.NetworkFailure -> return page
                is ZoteroResult.Cancelled -> return page
                is ZoteroResult.MalformedJson -> return page
                is ZoteroResult.RateLimited -> return page
                is ZoteroResult.HttpError -> return page
            }
        }
        return ZoteroResult.Success(items)
    }
}
