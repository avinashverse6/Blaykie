package org.blaykie.nativeapp.zotero

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Outcome of a public schema lookup; never carries user data or credentials. */
sealed class SchemaResult<out T> {
    data class Success<T>(val value: T, val basic: Boolean = false) : SchemaResult<T>()
    data class Failed(val stage: String, val status: Int? = null, val exception: String? = null) : SchemaResult<Nothing>()
    final override fun toString(): String = "SchemaResult.${javaClass.simpleName}"
}

/**
 * Reads the public Zotero item schema (item types, fields, creator types and the
 * empty-item template) from api.zotero.org. These endpoints need no key, and none
 * is sent. Everything is cached in memory for the session only. When the schema
 * API cannot be reached, [ZoteroSchemaFallback] supplies the 11 common types.
 *
 * Every request is a real cancellable OkHttp call (never a blocking `execute()` on a
 * coroutine), each with its own [stepTimeoutMs], and the three per-type requests run
 * in parallel under an overall [overallTimeoutMs]. So a hung endpoint can never leave
 * the caller on a spinner forever: the load returns a [SchemaResult.Failed] tagged with
 * the failing stage, and nothing is cached unless a request succeeded.
 */
class ZoteroSchemaClient private constructor(
    private val calls: Call.Factory,
    private val baseUrl: String,
    private val stepTimeoutMs: Long = 12_000,
    private val overallTimeoutMs: Long = 20_000,
) {
    constructor() : this(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
        "https://api.zotero.org/",
    )

    companion object {
        internal fun forTesting(calls: Call.Factory, baseUrl: String,
                                stepTimeoutMs: Long = 12_000, overallTimeoutMs: Long = 20_000): ZoteroSchemaClient =
            ZoteroSchemaClient(calls, baseUrl, stepTimeoutMs, overallTimeoutMs)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The stages of the per-type schema load, in the order they are reported. */
    private val schemaStages = listOf("fields", "creators", "template")

    @Volatile
    private var cachedTypes: SchemaResult.Success<List<ItemTypeInfo>>? = null

    /** Only successful schema loads land here: a failed or stuck load is never cached. */
    private val schemaCache = ConcurrentHashMap<String, TypeSchema>()

    /**
     * One GET, as a cancellable suspend call. On any problem it returns a
     * [SchemaResult.Failed] tagged with [stage] so the caller knows exactly which
     * request failed; a timeout is reported as "timeout", a transport problem as
     * "network", and an unreadable body as "malformed". Cancelling the coroutine
     * cancels the underlying OkHttp call, so a recomposition or a Retry never leaves
     * an orphaned request behind. Nothing is cached here.
     */
    private suspend fun get(stage: String, path: String, params: Map<String, String>): SchemaResult<JsonElement> {
        val builder = baseUrl.toHttpUrl().newBuilder()
        // Split on '/' explicitly: addPathSegment("items/new") would percent-encode the
        // slash into "items%2Fnew", which the API (and any path-based test router) rejects.
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder().url(builder.build())
            .header("Accept", "application/json")
            .header("User-Agent", blaykieUserAgent())
            .build()
        val call = calls.newCall(request)
        call.timeout().timeout(stepTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            withTimeoutOrNull(stepTimeoutMs) { execute(call, stage) }
                ?: SchemaResult.Failed(stage, null, "timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SchemaResult.Failed(stage, null, e.javaClass.simpleName)
        }
    }

    /** The single request, resumed on an OkHttp thread and cancelled with the coroutine. */
    private suspend fun execute(call: Call, stage: String): SchemaResult<JsonElement> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(SchemaResult.Failed(stage, null, classify(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result: SchemaResult<JsonElement> = response.use {
                        when {
                            !it.isSuccessful -> SchemaResult.Failed(stage, it.code, null)
                            else -> try {
                                SchemaResult.Success<JsonElement>(json.parseToJsonElement(it.body?.string() ?: ""))
                            } catch (_: Exception) {
                                SchemaResult.Failed(stage, null, "malformed")
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private fun classify(e: IOException): String = when (e) {
        // SocketTimeoutException extends InterruptedIOException, so this covers both.
        is InterruptedIOException -> "timeout"
        else -> "network"
    }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /**
     * All regular item types (attachment, note and annotation hidden). Falls back to the
     * built-in list on any failure, so the chooser always has something to show.
     */
    suspend fun itemTypes(): SchemaResult<List<ItemTypeInfo>> {
        cachedTypes?.let { return it }
        val result = when (val response = get("types", "itemTypes", mapOf("locale" to "en-US"))) {
            is SchemaResult.Success -> {
                val types = (response.value as? JsonArray).orEmpty().mapNotNull { element ->
                    val obj = element.obj() ?: return@mapNotNull null
                    val type = obj.text("itemType")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (type in hiddenItemTypes) null
                    else ItemTypeInfo(type, obj.text("localized")?.ifBlank { null } ?: humaniseSchemaName(type))
                }
                if (types.isEmpty()) SchemaResult.Success(ZoteroSchemaFallback.types, basic = true)
                else SchemaResult.Success(types.distinctBy { it.type })
            }
            // The type list only drives the chooser; on any failure we fall back to the
            // built-in list rather than surface an error here.
            else -> SchemaResult.Success(ZoteroSchemaFallback.types, basic = true)
        }
        cachedTypes = result
        return result
    }

    /**
     * The full form schema for one type. The empty-item template decides which field names
     * a POST may use; the fields endpoint supplies localized labels and base mappings; the
     * creator-types endpoint supplies the roles. The three requests run in parallel, each
     * with its own timeout, under an overall limit.
     *
     * The display label is taken from [labelHint] (the already-loaded in-memory type list),
     * so no fourth request is made. On any failure a [SchemaResult.Failed] carrying the
     * failing stage is returned and nothing is cached, so the caller can offer Retry and
     * "Use basic form" instead of a spinner.
     */
    suspend fun typeSchema(type: String, labelHint: String? = null): SchemaResult<TypeSchema> {
        schemaCache[type]?.let { return SchemaResult.Success(it, it.basic) }

        // Which steps actually answered, so an overall timeout can name the step that hung.
        val finished: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        suspend fun step(stage: String, path: String, params: Map<String, String>): SchemaResult<JsonElement> =
            get(stage, path, params).also { finished.add(stage) }

        val results = try {
            coroutineScope {
                val fieldsDef = async { step("fields", "itemTypeFields", mapOf("itemType" to type, "locale" to "en-US")) }
                val creatorsDef = async { step("creators", "itemTypeCreatorTypes", mapOf("itemType" to type, "locale" to "en-US")) }
                val templateDef = async { step("template", "items/new", mapOf("itemType" to type)) }
                withTimeout(overallTimeoutMs) { Triple(fieldsDef.await(), creatorsDef.await(), templateDef.await()) }
            }
        } catch (e: TimeoutCancellationException) {
            // Only swallow our own overall timeout; a real cancellation must propagate.
            coroutineContext.ensureActive()
            null
        }
        if (results == null) {
            val stage = schemaStages.firstOrNull { it !in finished } ?: "schema"
            return SchemaResult.Failed(stage, null, "timeout")
        }

        val (fieldsResult, creatorsResult, templateResult) = results
        val failed = listOf(fieldsResult, creatorsResult, templateResult).firstOrNull { it !is SchemaResult.Success }
        if (failed is SchemaResult.Failed) return failed

        // GET /itemTypeFields returns [{field, localized, baseField?}]; localized is optional.
        val apiFields = (fieldsResult as SchemaResult.Success).value.let { root ->
            (root as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element.obj() ?: return@mapNotNull null
                val name = obj.text("field")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SchemaField(name, obj.text("localized")?.ifBlank { null } ?: humaniseSchemaName(name),
                    obj.text("baseField")?.ifBlank { null } ?: knownBaseFields[name])
            }
        }
        // GET /items/new returns one object (older servers wrap it in a one-element array).
        val templateKeys = (templateResult as SchemaResult.Success).value.let { root ->
            val obj = (root as? JsonArray)?.firstOrNull()?.obj() ?: root.obj()
            obj?.keys?.toList()
        }.orEmpty()
        val fieldByName = apiFields.associateBy { it.name }
        val canonicalNames = when {
            templateKeys.isNotEmpty() -> templateKeys
            apiFields.isNotEmpty() -> apiFields.map { it.name }
            else -> emptyList()
        }.filter { it !in hiddenSchemaFields && it != "creators" }
        if (canonicalNames.isEmpty()) return SchemaResult.Failed("template", null, "malformed")

        val fields = canonicalNames.map { name ->
            fieldByName[name] ?: SchemaField(name, humaniseSchemaName(name), knownBaseFields[name])
        }
        // GET /itemTypeCreatorTypes returns [{creatorType, localized, primary?}].
        val apiCreators = (creatorsResult as SchemaResult.Success).value.let { root ->
            (root as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element.obj() ?: return@mapNotNull null
                val creatorType = obj.text("creatorType")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CreatorTypeInfo(creatorType,
                    obj.text("localized")?.ifBlank { null } ?: humaniseSchemaName(creatorType),
                    (obj["primary"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }
        }
        val creatorTypes = when {
            apiCreators.any { it.primary } -> apiCreators
            apiCreators.isNotEmpty() -> apiCreators.mapIndexed { index, info -> info.copy(primary = index == 0) }
            else -> listOf(CreatorTypeInfo("author", "Author", primary = true))
        }
        val schema = TypeSchema(type, labelHint ?: humaniseSchemaName(type), fields, creatorTypes)
        schemaCache[type] = schema
        return SchemaResult.Success(schema)
    }
}
