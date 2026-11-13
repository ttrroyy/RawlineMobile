package app.rawline.mobile.route

import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.RouteManager
import app.rawline.mobile.group.ProviderApps
import app.rawline.mobile.outbound.json.JsonInput

/**
 * Which routing a server of [ProxyGroup] runs with, in the order CoreConfigs.buildMainWith decides it: the group's own
 * routing profile, else the subscription's Happ / Incy routing, else the global routing profile. The apps the
 * subscription sends ([ProviderApps]) apply whichever routing it is.
 */
object RouteSource {
    enum class Kind { GROUP, PROVIDER, PROVIDER_OFF, CLIENT }

    data class Info(val kind: Kind, val name: String, val apps: ProviderApps?) {
        /** The client's routing profiles cannot be chosen for this group while the provider's routing is on. */
        val providerLocked: Boolean get() = kind == Kind.PROVIDER || kind == Kind.PROVIDER_OFF
    }

    fun of(group: ProxyGroup?): Info {
        val global = RouteManager.current()
        if (group == null) return Info(Kind.CLIENT, global.name, null)
        val options = group.subOptions
        val apps = options.takeUnless { it.providerIgnoreImport }?.providerApps?.let { ProviderApps.fromJson(it) }
        val own = RouteManager.routeFor(group.id)
        if (own.id != global.id) return Info(Kind.GROUP, own.name, apps)
        return kind(options.routingProvider, options.providerRoutingEnabled, options.providerRouting, global.name, apps)
    }

    internal fun kind(provider: String, enabled: Boolean, routing: String, globalName: String, apps: ProviderApps?): Info {
        if (provider.isBlank() || !enabled) return Info(Kind.CLIENT, globalName, apps)
        if (routing == "off") return Info(Kind.PROVIDER_OFF, provider, apps)
        val name = JsonInput.parseObjectOrNull(routing)?.string("Name").orEmpty()
        return Info(Kind.PROVIDER, name.ifBlank { provider }, apps)
    }
}
