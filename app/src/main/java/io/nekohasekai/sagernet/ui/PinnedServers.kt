package app.rawline.mobile.ui

import app.rawline.mobile.database.DataStore

/** Servers pinned to the top of their group, the same set in every interface style. */
object PinnedServers {
    fun ids(): Set<Long> = DataStore.mobilePinnedProfiles.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    fun toggle(id: Long) {
        val ids = ids().toMutableSet()
        if (!ids.remove(id)) ids.add(id)
        DataStore.mobilePinnedProfiles = ids.joinToString(",")
    }
    /** Pinned first; the rest keep their order. */
    fun <T> first(items: List<T>, id: (T) -> Long): List<T> = ids().let { pinned -> if (pinned.isEmpty()) items else items.sortedBy { if (id(it) in pinned) 0 else 1 } }
}
