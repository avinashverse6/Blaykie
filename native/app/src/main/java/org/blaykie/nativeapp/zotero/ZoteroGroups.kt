package org.blaykie.nativeapp.zotero

/**
 * Group lists are read-only metadata (names and ids, never keys) and every failure —
 * including a key without group access (403) — quietly means "no groups".
 */
internal fun quietGroups(client: ZoteroClient, userId: Long, apiKey: String): List<ZoteroGroup> =
    (client.groups(userId, apiKey) as? ZoteroResult.Success)?.value ?: emptyList()

/** Loads the group list at most once per short cache window per user, per process. */
internal object ZoteroGroupsCache {
    private const val TTL_MILLIS = 5 * 60 * 1000L
    private var userId: Long = -1
    private var loadedAtMillis: Long = 0
    private var groups: List<ZoteroGroup> = emptyList()

    @Synchronized fun fetch(client: ZoteroClient, userId: Long, apiKey: String,
                            nowMillis: Long = System.currentTimeMillis()): List<ZoteroGroup> {
        if (this.userId == userId && nowMillis - loadedAtMillis < TTL_MILLIS) return groups
        val loaded = quietGroups(client, userId, apiKey)
        this.userId = userId; loadedAtMillis = nowMillis; groups = loaded
        return loaded
    }

    @Synchronized fun clear() { userId = -1; loadedAtMillis = 0; groups = emptyList() }
}
