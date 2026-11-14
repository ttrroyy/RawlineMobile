package app.rawline.mobile.ui

import app.rawline.mobile.bg.test.TestSpec
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.group.SubscriptionClient
import app.rawline.mobile.ui.test.TestSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A subscription that was just added or updated gets its servers' flags at once: an IP test of the servers that have
 * no country yet finds where each one comes out (the country of the exit address).
 */
object SubscriptionCountries {
    /** Collects in [scope] for as long as it lives; a refresh that ends is one that leaves [SubscriptionClient.states]. */
    fun follow(scope: CoroutineScope) = scope.launch {
        var refreshing = emptySet<Long>()
        SubscriptionClient.states.collect { states ->
            val ended = refreshing - states.keys
            refreshing = states.keys
            for (gid in ended) test(gid)
        }
    }

    private suspend fun test(gid: Long) {
        val ids = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getByGroup(gid).filter { it.testCountry.isNullOrEmpty() }.map { it.id }
        }
        // A test already running keeps its place: the flags then come with the next update.
        if (ids.isNotEmpty()) TestSessionClient.start(TestSpec(TestSpec.KIND_IP, ids.toLongArray(), quiet = true), 0L)
    }
}
