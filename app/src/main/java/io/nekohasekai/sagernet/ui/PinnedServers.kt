package app.rawline.mobile.ui

import app.rawline.mobile.database.DataStore

/** Servers pinned to the top of their group, the same set in every interface style. */
object PinnedServers {
    fun ids(): Set<Long> = DataStore.mobilePinnedProfiles.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    fun toggle(id: Long) = toggle(listOf(id))

    @Synchronized
    fun toggle(selected: Collection<Long>) {
        if (selected.isEmpty()) return
        val ids = ids().toMutableSet()
        if (ids.containsAll(selected)) ids.removeAll(selected.toSet()) else ids.addAll(selected)
        DataStore.mobilePinnedProfiles = ids.joinToString(",")
    }

    fun sameSection(first: Long, second: Long): Boolean = ids().let { (first in it) == (second in it) }
    /** Pinned first; the rest keep their order. */
    fun <T> first(items: List<T>, id: (T) -> Long): List<T> = ids().let { pinned -> if (pinned.isEmpty()) items else items.sortedBy { if (id(it) in pinned) 0 else 1 } }
}
