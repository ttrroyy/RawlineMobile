package app.rawline.mobile.outbound.config

/** [id] is the wire name shared by the core service and the route tester. */
enum class RouteRuleOriginKind(val id: String) {
    PROFILE_RULE("profile_rule"),
    XRAY_BRIDGE("xray_bridge"),
    SNIFF("sniff"),
    RESOLVE("resolve"),
    DNS_HIJACK("dns_hijack"),
    DNS_INBOUND_REJECT("dns_inbound_reject"),
    TUN_GUARD("tun_guard"),
    PER_APP_GUARD("per_app_guard"),
    QUIC_GUARD("quic_guard"),
    REMOTE_NETWORK("remote_network"),
    AD_BLOCK("adblock"),
    BLOCK_FINAL("block_final");

    companion object {
        fun ofId(id: String): RouteRuleOriginKind? = entries.firstOrNull { it.id == id }
    }
}

/** RouteRuleOrigin: where one route.rules entry came from; [profileRule] indexes RouteProfile.rules, [name] is that rule's name. */
class RouteRuleOrigin(
    @JvmField val kind: RouteRuleOriginKind,
    @JvmField val profileRule: Int = -1,
    @JvmField val name: String = "",
)
