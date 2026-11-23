package org.blaykie.nativeapp.zotero

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
import java.util.concurrent.TimeUnit

/** Outcome of a public schema lookup; never carries user data or credentials. */
sealed class SchemaResult<out T> {
    data class Success<T>(val value: T, val basic: Boolean = false) : SchemaResult<T>()
    data class HttpError(val status: Int) : SchemaResult<Nothing>()
    object NetworkFailure : SchemaResult<Nothing>()
    object Malformed : SchemaResult<Nothing>()
    final override fun toString(): String = "SchemaResult.${javaClass.simpleName}"
}

/**
 * Reads the public Zotero item schema (item types, fields, creator types and the
 * empty-item template) from api.zotero.org. These endpoints need no key, and none
 * is sent. Everything is cached in memory for the session only. When the schema
 * API cannot be reached, [ZoteroSchemaFallback] supplies the 11 common types.
 * Used from a background thread.
 */
class ZoteroSchemaClient private constructor(private val calls: Call.Factory, private val baseUrl: String) {
    constructor() : this(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
        "https://api.zotero.org/",
    )

    companion object {
        internal fun forTesting(calls: Call.Factory, baseUrl: String): ZoteroSchemaClient =
            ZoteroSchemaClient(calls, baseUrl)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var cachedTypes: SchemaResult.Success<List<ItemTypeInfo>>? = null
    private val schemaCache = mutableMapOf<String, TypeSchema>()

    private fun get(path: String, params: Map<String, String>): SchemaResult<JsonElement> {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(path)
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", blaykieUserAgent())
            .build()
        val response = try { calls.newCall(request).execute() } catch (_: IOException) {
            return SchemaResult.NetworkFailure
        }
        return response.use {
            when {
                !it.isSuccessful -> SchemaResult.HttpError(it.code)
                else -> try {
                    SchemaResult.Success(json.parseToJsonElement(it.body?.string() ?: ""))
                } catch (_: Exception) { SchemaResult.Malformed }
            }
        }
    }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** All regular item types (attachment, note and annotation hidden). Falls back to the basic list. */
    fun itemTypes(): SchemaResult<List<ItemTypeInfo>> {
        cachedTypes?.let { return it }
        val result = when (val response = get("itemTypes", mapOf("locale" to "en-US"))) {
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
            else -> SchemaResult.Success(ZoteroSchemaFallback.types, basic = true)
        }
        cachedTypes = result
        return result
    }

    /**
     * The full form schema for one type. The empty-item template decides which field
     * names a POST may use; the fields endpoint supplies localized labels and base
     * mappings. Falls back to the built-in schema for the 11 common types.
     */
    fun typeSchema(type: String): SchemaResult<TypeSchema> {
        schemaCache[type]?.let { return SchemaResult.Success(it, it.basic) }
        val fieldsResult = get("itemTypeFields", mapOf("itemType" to type, "locale" to "en-US"))
        val creatorsResult = get("itemTypeCreatorTypes", mapOf("itemType" to type, "locale" to "en-US"))
        val templateResult = get("items/new", mapOf("itemType" to type))
        if (fieldsResult !is SchemaResult.Success && creatorsResult !is SchemaResult.Success &&
            templateResult !is SchemaResult.Success) {
            ZoteroSchemaFallback.schema(type)?.let {
                schemaCache[type] = it
                return SchemaResult.Success(it, basic = true)
            }
            return when {
                fieldsResult is SchemaResult.HttpError -> fieldsResult
                creatorsResult is SchemaResult.HttpError -> creatorsResult
                templateResult is SchemaResult.HttpError -> templateResult
                fieldsResult is SchemaResult.NetworkFailure -> fieldsResult
                creatorsResult is SchemaResult.NetworkFailure -> creatorsResult
                templateResult is SchemaResult.NetworkFailure -> templateResult
                else -> SchemaResult.Malformed
            }
        }
        val apiFields = (fieldsResult as? SchemaResult.Success)?.value?.let { root ->
            (root as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element.obj() ?: return@mapNotNull null
                val name = obj.text("field")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SchemaField(name, obj.text("localized")?.ifBlank { null } ?: humaniseSchemaName(name),
                    obj.text("baseField")?.ifBlank { null } ?: knownBaseFields[name])
            }
        }.orEmpty()
        val templateKeys = (templateResult as? SchemaResult.Success)?.value?.let { root ->
            val obj = (root as? JsonArray)?.firstOrNull()?.obj() ?: root.obj()
            obj?.keys?.toList()
        }.orEmpty()
        val fieldByName = apiFields.associateBy { it.name }
        val canonicalNames = when {
            templateKeys.isNotEmpty() -> templateKeys
            apiFields.isNotEmpty() -> apiFields.map { it.name }
            else -> ZoteroSchemaFallback.schema(type)?.fields?.map { it.name }.orEmpty()
        }.filter { it !in hiddenSchemaFields && it != "creators" }
        if (canonicalNames.isEmpty()) {
            ZoteroSchemaFallback.schema(type)?.let {
                schemaCache[type] = it
                return SchemaResult.Success(it, basic = true)
            }
            return SchemaResult.Malformed
        }
        val fields = canonicalNames.map { name ->
            fieldByName[name] ?: SchemaField(name, humaniseSchemaName(name), knownBaseFields[name])
        }
        val apiCreators = (creatorsResult as? SchemaResult.Success)?.value?.let { root ->
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
            else -> ZoteroSchemaFallback.schema(type)?.creatorTypes
                ?: listOf(CreatorTypeInfo("author", "Author", primary = true))
        }
        val label = itemTypes().let { result ->
            (result as? SchemaResult.Success)?.value?.firstOrNull { it.type == type }?.label
        } ?: humaniseSchemaName(type)
        val schema = TypeSchema(type, label, fields, creatorTypes)
        schemaCache[type] = schema
        return SchemaResult.Success(schema)
    }
}
