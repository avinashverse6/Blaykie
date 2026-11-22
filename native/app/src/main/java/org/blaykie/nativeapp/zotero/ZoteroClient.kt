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

data class ZoteroGroup(val id: Long, val name: String)

/** A readable Zotero library: the personal library or one group library. */
sealed class ZoteroLibrary {
    object Personal : ZoteroLibrary()
    data class Group(val group: ZoteroGroup) : ZoteroLibrary()

    /** Compact session-journal encoding; group names are re-read from the API, never stored. */
    val journalValue: String get() = when (this) {
        is Personal -> "user"
        is Group -> "group:${group.id}"
    }
    internal fun basePath(userId: Long): List<String> = when (this) {
        is Personal -> listOf("users", userId.toString())
        is Group -> listOf("groups", group.id.toString())
    }
    internal fun itemsPath(userId: Long, top: Boolean): List<String> =
        basePath(userId) + listOfNotNull("items", if (top) "top" else null)
    companion object {
        fun fromJournal(value: String?): ZoteroLibrary =
            value?.removePrefix("group:")?.toLongOrNull()?.takeIf { value.startsWith("group:") && it > 0 }
                ?.let { Group(ZoteroGroup(it, "")) } ?: Personal
    }
}

data class KeyPermissions(val personalLibrary: Boolean, val personalFiles: Boolean, val personalNotes: Boolean, val personalWrite: Boolean, val groupAccess: Boolean) {
    val broaderThanPersonalReadOnly: Boolean get() = personalFiles || personalWrite || groupAccess
}
data class VerifiedKey(val userId: Long, val permissions: KeyPermissions)
data class ZoteroItem(val key: String, val data: JsonObject, val numChildren: Int? = null) {
    fun citationItem(): CitationItem {
        fun string(name: String): String = (data[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun creators(kind: String): List<CitationAuthor> = (data["creators"] as? JsonArray).orEmpty().mapNotNull { element ->
            val c = element as? JsonObject ?: return@mapNotNull null
            val role = (c["creatorType"] as? JsonPrimitive)?.contentOrNull
            if (kind == "author" && role != null && role != "author") return@mapNotNull null
            if (kind != "author" && role != kind) return@mapNotNull null
            val last = (c["lastName"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().ifEmpty { (c["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim() }
            if (last.isEmpty()) return@mapNotNull null
            val group = (c["lastName"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().isEmpty()
            CitationAuthor(if (group) "" else (c["firstName"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(), last, group)
        }
        val type = string("itemType")
        val originalAuthors = creators("author")
        val editors = creators("editor")
        val editorLead = originalAuthors.isEmpty() && type == "book" && editors.isNotEmpty()
        val authors = if (editorLead) editors else originalAuthors
        return CitationItem(key, string("title"), authors, string("date"), string("publicationTitle"), string("volume"), string("issue"), string("pages"), string("DOI"), type, string("publisher"), string("bookTitle"), string("edition"), editors, string("url"), editorLead,
            extra = string("extra"), websiteType = string("websiteType"), websiteTitle = string("websiteTitle"), blogTitle = string("blogTitle"),
            reportNumber = string("reportNumber"), reportType = string("reportType"), institution = string("institution"), thesisType = string("thesisType"),
            university = string("university"), archive = string("archive"), proceedingsTitle = string("proceedingsTitle"),
            conferenceName = string("conferenceName"), place = string("place"), repository = string("repository"))
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

    internal data class PagedBody(val body: String, val totalResults: Int?)
    private fun request(path: List<String>, params: Map<String, String>, apiKey: String): ZoteroResult<String> =
        when (val paged = requestPaged(path, params, apiKey)) {
            is ZoteroResult.Success -> ZoteroResult.Success(paged.value.body)
            is ZoteroResult.EmptyLibrary -> paged
            is ZoteroResult.InvalidKey -> paged
            is ZoteroResult.NetworkFailure -> paged
            is ZoteroResult.Cancelled -> paged
            is ZoteroResult.MalformedJson -> paged
            is ZoteroResult.RateLimited -> paged
            is ZoteroResult.HttpError -> paged
        }
    private fun requestPaged(path: List<String>, params: Map<String, String>, apiKey: String): ZoteroResult<PagedBody> {
        val url = BASE.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = try {
            Request.Builder().url(url).header("Zotero-API-Key", apiKey).header("Zotero-API-Version", "3")
                .header("User-Agent", blaykieUserAgent()).build()
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
                    return try { ZoteroResult.Success(PagedBody(it.body?.string() ?: "", it.header("Total-Results")?.toIntOrNull())) } catch (_: IOException) {
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
    private inline fun <T> parsePaged(result: ZoteroResult<PagedBody>, decode: (PagedBody) -> T): ZoteroResult<T> = when (result) {
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
        val children = ((obj["meta"] as? JsonObject)?.get("numChildren") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ZoteroItem((obj["key"] as JsonPrimitive).content, obj["data"] as JsonObject, children)
    }
    /** Groups this key can read. Callers treat any failure quietly as "no groups". */
    fun groups(userId: Long, apiKey: String): ZoteroResult<List<ZoteroGroup>> =
        parse(request(listOf("users", userId.toString(), "groups"), emptyMap(), apiKey)) { raw ->
            (json.parseToJsonElement(raw) as JsonArray).mapNotNull { value ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val data = obj["data"] as? JsonObject
                val id = listOf(data?.get("id"), obj["id"]).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.firstOrNull()
                val name = ((data?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: (obj["name"] as? JsonPrimitive)?.contentOrNull)?.trim().orEmpty()
                if (id == null || id <= 0) null else ZoteroGroup(id, name.ifEmpty { "Group $id" })
            }
        }
    fun search(userId: Long, apiKey: String, query: String, limit: Int = MAX_ITEMS): ZoteroResult<List<ZoteroItem>> =
        search(ZoteroLibrary.Personal, userId, apiKey, query, limit)
    fun search(library: ZoteroLibrary, userId: Long, apiKey: String, query: String, limit: Int = MAX_ITEMS): ZoteroResult<List<ZoteroItem>> {
        val maximum = limit.coerceIn(1, MAX_ITEMS)
        val pageSize = minOf(PAGE_SIZE, maximum)
        val collected = mutableListOf<ZoteroItem>()
        // At most 200 server items are examined, even if every item is filtered.
        var start = 0
        while (start < MAX_ITEMS && collected.size < maximum) {
            val params = mapOf("q" to query, "limit" to pageSize.toString(), "start" to start.toString(), "format" to "json")
            when (val page = parse(request(library.itemsPath(userId, top = true), params, apiKey), ::parseItems)) {
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
    /** All collections of a library, following limit/start paging. Read-only. */
    fun collections(library: ZoteroLibrary, userId: Long, apiKey: String): ZoteroResult<List<ZoteroCollection>> {
        val collected = mutableListOf<ZoteroCollection>()
        var start = 0
        while (true) {
            val params = mapOf("limit" to "100", "start" to start.toString(), "format" to "json")
            when (val page = parsePaged(requestPaged(library.basePath(userId) + "collections", params, apiKey)) { raw ->
                (json.parseToJsonElement(raw.body) as JsonArray).mapNotNull { value ->
                    val obj = value as? JsonObject ?: return@mapNotNull null
                    val data = obj["data"] as? JsonObject ?: return@mapNotNull null
                    val key = (data["key"] as? JsonPrimitive)?.contentOrNull ?: (obj["key"] as? JsonPrimitive)?.contentOrNull
                    val name = (data["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (key.isNullOrBlank()) null else ZoteroCollection(key, name.ifEmpty { "Untitled collection" },
                        (data["parentCollection"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "false" },
                        ((obj["meta"] as? JsonObject)?.get("numItems") as? JsonPrimitive)?.contentOrNull?.toIntOrNull())
                } to raw.totalResults
            }) {
                is ZoteroResult.Success -> {
                    collected += page.value.first
                    val total = page.value.second
                    if (page.value.first.size < 100 || (total != null && collected.size >= total)) break
                    start += 100
                }
                is ZoteroResult.EmptyLibrary -> return page
                is ZoteroResult.InvalidKey -> return page
                is ZoteroResult.NetworkFailure -> return page
                is ZoteroResult.Cancelled -> return page
                is ZoteroResult.MalformedJson -> return page
                is ZoteroResult.RateLimited -> return page
                is ZoteroResult.HttpError -> return page
            }
        }
        return ZoteroResult.Success(collected)
    }

    /** One page of a library's tags. Read-only. */
    fun tagsPage(library: ZoteroLibrary, userId: Long, apiKey: String, query: String, start: Int, limit: Int = 50): ZoteroResult<TagPage> =
        parsePaged(requestPaged(library.basePath(userId) + "tags",
            buildMap {
                put("limit", limit.coerceIn(1, 100).toString()); put("start", start.coerceAtLeast(0).toString()); put("format", "json")
                if (query.isNotBlank()) put("q", query.trim())
            }, apiKey)) { raw ->
            val tags = (json.parseToJsonElement(raw.body) as JsonArray).mapNotNull { value ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val name = (obj["tag"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (name.isEmpty()) null else ZoteroTag(name,
                    ((obj["meta"] as? JsonObject)?.get("numItems") as? JsonPrimitive)?.contentOrNull?.toIntOrNull())
            }
            TagPage(tags, raw.totalResults)
        }

    /** One page of browsable items (attachments, notes and annotations filtered out). Read-only. */
    fun browsePage(library: ZoteroLibrary, userId: Long, apiKey: String, query: BrowseQuery): ZoteroResult<ItemsPage> =
        parsePaged(requestPaged(browsePath(library, userId, query.collectionKey), browseParams(query), apiKey)) { raw ->
            val all = parseItems(raw.body)
            ItemsPage(all.filter { (it.data["itemType"] as? JsonPrimitive)?.contentOrNull !in setOf("attachment", "note", "annotation") },
                raw.totalResults, all.size)
        }

    fun fetchItems(userId: Long, apiKey: String, keys: List<String>): ZoteroResult<List<ZoteroItem>> =
        fetchItems(ZoteroLibrary.Personal, userId, apiKey, keys)
    fun fetchItems(library: ZoteroLibrary, userId: Long, apiKey: String, keys: List<String>): ZoteroResult<List<ZoteroItem>> {
        if (keys.isEmpty()) return ZoteroResult.Success(emptyList())
        val items = mutableListOf<ZoteroItem>()
        for (group in keys.take(MAX_ITEMS).chunked(50)) {
            val params = mapOf("itemKey" to group.joinToString(","), "limit" to "100", "format" to "json")
            when (val page = parse(request(library.itemsPath(userId, top = false), params, apiKey), ::parseItems)) {
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
