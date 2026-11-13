package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.jsonObjectOf

/**
 * A full Xray config keeps its own `routing`, which may send traffic the client routed to `proxy` straight out again
 * (a provider's "ru -> direct"). Traffic a Rawline rule sends to `proxy` on purpose enters through its own inbound,
 * and rules in front of the provider's give it the provider's routing without the direct exits: its balancers, its
 * choice of servers and its blocks stay. Everything else goes through the provider's routing unchanged.
 */
internal object XrayExplicitRouting {
    private val NOT_PROXY = setOf("freedom", "blackhole", "dns", "loopback")

    /** Whether the config has a rule that lets proxied traffic out directly; without one nothing needs changing. */
    fun needed(config: JsonObject): Boolean {
        val direct = directTags(config)
        return direct.isNotEmpty() && rules(config).any { it.string("outboundTag") in direct && seesBridge(it) }
    }

    /** Puts the explicit inbound's rules in front of the provider's; false when no proxy exit is left for them. */
    fun apply(config: JsonObject, inboundTag: String): Boolean {
        val direct = directTags(config)
        val original = rules(config)
        val target = defaultTarget(config, original, direct) ?: return false
        val routing = config.obj("routing").takeIf { config.contains("routing") } ?: JsonObject()
        val out = JsonArray()
        for (rule in original) {
            // A rule for one of the provider's own inbounds never sees the bridge; a direct exit is what is dropped.
            if (!seesBridge(rule) || rule.string("outboundTag") in direct) continue
            out.add(rule.copy().also { it.remove("ruleTag"); it["inboundTag"] = JsonArray.of(inboundTag) })
        }
        out.add(jsonObjectOf("type" to "field", "inboundTag" to JsonArray.of(inboundTag)).also { it[target.first] = target.second })
        for (rule in original) out.add(rule)
        routing["rules"] = out
        config["routing"] = routing
        return true
    }

    /** A rule without inbounds, or one naming the bridge, applies to what the client sends in. */
    private fun seesBridge(rule: JsonObject) =
        !rule.contains("inboundTag") || Tags.XRAY_FULL_CONFIG_IN in rule.array("inboundTag").strings()

    private fun rules(config: JsonObject): List<JsonObject> =
        if (config.contains("routing")) config.obj("routing").array("rules").filterIsInstance<JsonObject>() else emptyList()

    private fun outbounds(config: JsonObject): List<JsonObject> = config.array("outbounds").filterIsInstance<JsonObject>()

    private fun directTags(config: JsonObject): Set<String> =
        outbounds(config).filter { it.string("protocol") == "freedom" }.map { it.string("tag") }.toSet()

    /**
     * Where the explicit traffic goes when none of the copied rules matches: the balancer or proxy of the provider's
     * catch-all rule, else Xray's default (the first outbound) when it is a proxy, else the first proxy outbound.
     */
    private fun defaultTarget(config: JsonObject, rules: List<JsonObject>, direct: Set<String>): Pair<String, String>? {
        val catchAll = rules.lastOrNull { rule -> rule.keys().all { it in setOf("type", "outboundTag", "balancerTag", "ruleTag", "network") } }
        catchAll?.string("balancerTag")?.takeIf { it.isNotEmpty() }?.let { return "balancerTag" to it }
        catchAll?.string("outboundTag")?.takeIf { it.isNotEmpty() && it !in direct && isProxy(config, it) }?.let { return "outboundTag" to it }
        val proxies = outbounds(config).filter { it.string("protocol") !in NOT_PROXY && it.string("tag").isNotEmpty() }
        return proxies.firstOrNull()?.let { "outboundTag" to it.string("tag") }
    }

    private fun isProxy(config: JsonObject, tag: String) =
        outbounds(config).any { it.string("tag") == tag && it.string("protocol") !in NOT_PROXY }
}
