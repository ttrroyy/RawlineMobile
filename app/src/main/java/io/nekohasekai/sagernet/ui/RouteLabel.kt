package app.rawline.mobile.ui

import android.content.Context
import app.rawline.mobile.R
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.route.RouteSource

/** The line that says which routing a group's servers run with: the home screen's, in either style. */
object RouteLabel {
    /** [server]: one of the group's, whose full Xray config keeps its own routing. Blocking: reads the database. */
    fun of(context: Context, group: ProxyGroup, server: ProxyEntity?): String {
        val info = runCatching { RouteSource.of(group) }.getOrNull() ?: return ""
        return text(context, info, runCatching { server?.outbound?.isXrayFullConfig() == true }.getOrDefault(false))
    }

    /** [xrayFull]: the subscription's servers are full Xray configs, whose own routing CoreConfigs keeps. */
    fun text(context: Context, info: RouteSource.Info, xrayFull: Boolean = false): String {
        val rest = if (xrayFull) context.getString(R.string.qmobile_route_label_xray_rest) else ""
        val base = if (xrayFull && info.providerLocked) context.getString(R.string.qmobile_route_label_provider_xray) else when (info.kind) {
            RouteSource.Kind.PROVIDER -> context.getString(R.string.qmobile_route_label_provider, info.name)
            RouteSource.Kind.PROVIDER_OFF -> context.getString(R.string.qmobile_route_label_provider_off)
            RouteSource.Kind.GROUP -> context.getString(R.string.qmobile_route_label_group, info.name) + rest
            RouteSource.Kind.CLIENT -> context.getString(R.string.qmobile_route_label_client, info.name) + rest
        }
        val apps = info.apps?.let { when {
            it.off -> context.getString(R.string.qmobile_route_apps_off)
            it.bypass -> context.getString(R.string.qmobile_route_apps_bypass, it.packages.size)
            else -> context.getString(R.string.qmobile_route_apps_proxy, it.packages.size)
        } }
        return if (apps == null) base else "$base · $apps"
    }
}
