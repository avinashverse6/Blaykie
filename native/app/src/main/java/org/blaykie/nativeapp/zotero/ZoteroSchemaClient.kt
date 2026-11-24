package org.blaykie.nativeapp.zotero

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

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
 * Used from a background thread.
 *
 * The three per-type requests run in parallel, each with its own timeout, and the
 * whole load has an overall limit, so a hung endpoint can never leave the caller on
 * a spinner forever. Every failure is reported with the failing stage so the UI can
 * show a precise message and a Retry / "Use basic form" escape hatch.
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
        internal fun forTesting(calls: Call.Factory, baseUrl: String, stepTimeoutMs: Long = 12_000, overallTimeoutMs: Long = 20_000): ZoteroSchemaClient =
            ZoteroSchemaClient(calls, baseUrl, stepTimeoutMs, overallTimeoutMs)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var cachedTypes: SchemaResult.Success<List<ItemTypeInfo>>? = null
    private val schemaCache = mutableMapOf<String, TypeSchema>()

    /**
     * One GET. On any problem it returns a [SchemaResult.Failed] tagged with [stage]
     * so the caller knows exactly which request failed; timeouts are reported as
     * "timeout" rather than a generic network error. Nothing is cached here — only
     * successful schema lookups are cached by the caller.
     */
    private fun get(stage: String, path: String, params: Map<String, String>): SchemaResult<JsonElement> {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(path)
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", blaykieUserAgent())
            .build()
        val call = calls.newCall(request)
        call.timeout().timeout(stepTimeoutMs, TimeUnit.MILLISECONDS)
        val response = try { call.execute() } catch (e: IOException) {
            val timeout = e is SocketTimeoutException || e is InterruptedIOException
            return SchemaResult.Failed(stage, null, if (timeout) "timeout" else "network")
        }
        return response.use {
            when {
                !it.isSuccessful -> SchemaResult.Failed(stage, it.code, null)
                else -> try {
                    SchemaResult.Success(json.parseToJsonElement(it.body?.string() ?: ""))
                } catch (_: Exception) { SchemaResult.Failed(stage, null, "malformed") }
            }
        }
    }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** All regular item types (attachment, note and annotation hidden). Falls back to the basic list. */
    fun itemTypes(): SchemaResult<List<ItemTypeInfo>> {
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
            // itemTypes is only used to populate the in-memory type list; on any failure we fall
            // back to the built-in list rather than surface an error here.
            else -> SchemaResult.Success(ZoteroSchemaFallback.types, basic = true)
        }
        cachedTypes = result
        return result
    }

    /**
     * The full form schema for one type. The empty-item template decides which field
     * names a POST may use; the fields endpoint supplies localized labels and base
     * mappings. The three requests run in parallel; the type's display label is taken
     * from [labelHint] (the already-loaded in-memory type list) so no extra network
     * request is made. On any failure a [SchemaResult.Failed] carrying the stage is
     * returned and nothing is cached, so the caller can show Retry / "Use basic form".
     */
    fun typeSchema(type: String, labelHint: String? = null): SchemaResult<TypeSchema> {
        schemaCache[type]?.let { return SchemaResult.Success(it, it.basic) }
        val results = runBlocking(Dispatchers.IO) {
            try {
                withTimeout(overallTimeoutMs) {
                    val fieldsDef = async { get("fields", "itemTypeFields", mapOf("itemType" to type, "locale" to "en-US")) }
                    val creatorsDef = async { get("creators", "itemTypeCreatorTypes", mapOf("itemType" to type, "locale" to "en-US")) }
                    val templateDef = async { get("template", "items/new", mapOf("itemType" to type)) }
                    Triple(fieldsDef.await(), creatorsDef.await(), templateDef.await())
                }
            } catch (_: TimeoutCancellationException) {
                null
            }
        } ?: return SchemaResult.Failed("schema", null, "timeout")
        val (fieldsResult, creatorsResult, templateResult) = results
        val failed = listOf(fieldsResult, creatorsResult, templateResult).firstOrNull { it !is SchemaResult.Success }
        if (failed is SchemaResult.Failed) return failed

        val apiFields = (fieldsResult as SchemaResult.Success).value.let { root ->
            (root as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element.obj() ?: return@mapNotNull null
                val name = obj.text("field")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SchemaField(name, obj.text("localized")?.ifBlank { null } ?: humaniseSchemaName(name),
                    obj.text("baseField")?.ifBlank { null } ?: knownBaseFields[name])
            }
        }.orEmpty()
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
        val apiCreators = (creatorsResult as SchemaResult.Success).value.let { root ->
            (root as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element.obj() ?: return@mapNotNull null
                val creatorType = obj.text("creatorType")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CreatorTypeInfo(creatorType,
                    obj.text("localized")?.ifBlank { null } ?: humaniseSchemaName(creatorType),
                    (obj["primary"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }
        }.orEmpty()
        val creatorTypes = when {
            apiCreators.any { it.primary } -> apiCreators
            apiCreators.isNotEmpty() -> apiCreators.mapIndexed { index, info -> info.copy(primary = index == 0) }
            else -> listOf(CreatorTypeInfo("author", "Author", primary = true))
        }
        val label = labelHint ?: humaniseSchemaName(type)
        val schema = TypeSchema(type, label, fields, creatorTypes)
        schemaCache[type] = schema
        return SchemaResult.Success(schema)
    }
}
