package org.blaykie.nativeapp.zotero

/** Outcome of a groups load: a real (possibly empty) list, or a user-visible failure. */
internal sealed class GroupsLoad {
    data class Loaded(val groups: List<ZoteroGroup>) : GroupsLoad()
    data class Failed(val message: String) : GroupsLoad()
}

/** Failures keep their cause (HTTP status where there is one); only a successful empty list means "no groups". */
internal fun groupsLoadResult(result: ZoteroResult<List<ZoteroGroup>>): GroupsLoad = when (result) {
    is ZoteroResult.Success -> GroupsLoad.Loaded(result.value)
    is ZoteroResult.EmptyLibrary -> GroupsLoad.Loaded(emptyList())
    is ZoteroResult.InvalidKey -> GroupsLoad.Failed("Could not load groups: HTTP 403")
    is ZoteroResult.HttpError -> GroupsLoad.Failed("Could not load groups: HTTP ${result.status}")
    is ZoteroResult.RateLimited -> GroupsLoad.Failed("Could not load groups: HTTP 429")
    is ZoteroResult.NetworkFailure -> GroupsLoad.Failed("Could not load groups. Check your connection.")
    is ZoteroResult.MalformedJson -> GroupsLoad.Failed("Could not load groups.")
    is ZoteroResult.Cancelled -> GroupsLoad.Failed("Could not load groups.")
}

internal fun loadGroups(client: ZoteroClient, userId: Long, apiKey: String): GroupsLoad =
    groupsLoadResult(client.groups(userId, apiKey))

/** Quiet variant for background recovery: any failure simply means "no groups to try". */
internal fun quietGroups(client: ZoteroClient, userId: Long, apiKey: String): List<ZoteroGroup> =
    (loadGroups(client, userId, apiKey) as? GroupsLoad.Loaded)?.groups ?: emptyList()

/**
 * Caches only successful loads, per user, for a short window. Failures are never cached,
 * so a retry (or the next sheet open) always asks Zotero again. Cleared whenever the
 * saved key changes, so a previous key's groups are never shown for a new key.
 */
internal object ZoteroGroupsCache {
    private const val TTL_MILLIS = 5 * 60 * 1000L
    private var userId: Long = -1
    private var loadedAtMillis: Long = 0
    private var cached: List<ZoteroGroup>? = null

    @Synchronized fun fetch(client: ZoteroClient, userId: Long, apiKey: String, force: Boolean = false,
                            nowMillis: Long = System.currentTimeMillis()): GroupsLoad {
        val hit = cached
        if (!force && this.userId == userId && hit != null && nowMillis - loadedAtMillis < TTL_MILLIS) {
            return GroupsLoad.Loaded(hit)
        }
        val result = loadGroups(client, userId, apiKey)
        if (result is GroupsLoad.Loaded) {
            this.userId = userId; loadedAtMillis = nowMillis; cached = result.groups
        }
        return result
    }

    @Synchronized fun clear() { userId = -1; loadedAtMillis = 0; cached = null }
}
