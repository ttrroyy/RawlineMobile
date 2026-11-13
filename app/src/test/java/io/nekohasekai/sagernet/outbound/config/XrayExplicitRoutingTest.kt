package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.Custom
import app.rawline.mobile.route.OutboundIds
import app.rawline.mobile.route.RouteProfile
import app.rawline.mobile.route.RouteRule
import app.rawline.mobile.route.RuleSetCatalog
import org.junit.Assert.*
import org.junit.Test

class XrayExplicitRoutingTest {
    private val provider = """{
        "outbounds":[{"tag":"proxy","protocol":"vless","settings":{}},{"tag":"de","protocol":"vless","settings":{}},
            {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
        "routing":{"balancers":[{"tag":"auto","selector":["proxy","de"]}],"rules":[
            {"type":"field","inboundTag":["socks-in"],"outboundTag":"direct"},
            {"type":"field","ip":["geoip:ru"],"outboundTag":"direct","ruleTag":"ru"},
            {"type":"field","domain":["geosite:category-ads-all"],"outboundTag":"block"},
            {"type":"field","domain":["youtube.com"],"outboundTag":"de"},
            {"type":"field","network":"tcp,udp","balancerTag":"auto"}]}}"""

    private fun rules(config: JsonObject) = config.obj("routing").array("rules").filterIsInstance<JsonObject>()

    @Test fun explicitTrafficKeepsEverythingButTheDirectExits() {
        val config = JsonInput.parseObject(provider)
        assertTrue(XrayExplicitRouting.needed(config))
        assertTrue(XrayExplicitRouting.apply(config, "rules-in"))
        val all = rules(config)
        val mine = all.filter { it.array("inboundTag").strings() == listOf("rules-in") }
        // Blocks, the server choice and the balancer stay; the direct rules are gone, the catch-all goes to the balancer.
        assertEquals(listOf("block", "de", "", ""), mine.map { it.string("outboundTag") })
        assertEquals(listOf("auto", "auto"), mine.drop(2).map { it.string("balancerTag") })
        assertTrue(mine.none { it.contains("ruleTag") })
        // The provider's rules follow, untouched, for everything else.
        assertEquals(5, all.size - mine.size)
        assertEquals("ru", all.first { it.string("ruleTag") == "ru" }.string("ruleTag"))
        assertTrue(all.indexOfFirst { it.string("ruleTag") == "ru" } > all.indexOfLast { it.array("inboundTag").strings() == listOf("rules-in") })
    }

    @Test fun withoutADirectRuleNothingChanges() {
        val config = JsonInput.parseObject("""{"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"type":"field","inboundTag":["x"],"outboundTag":"direct"}]}}""")
        assertFalse(XrayExplicitRouting.needed(config))
        assertFalse(XrayExplicitRouting.needed(JsonInput.parseObject("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")))
    }

    @Test fun aDirectDefaultFallsBackToTheFirstProxy() {
        val config = JsonInput.parseObject("""{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"nl","protocol":"trojan"}],
            "routing":{"rules":[{"type":"field","ip":["geoip:ru"],"outboundTag":"direct"},{"type":"field","network":"tcp,udp","outboundTag":"direct"}]}}""")
        assertTrue(XrayExplicitRouting.apply(config, "rules-in"))
        assertEquals("nl", rules(config).first().string("outboundTag"))
        val noProxy = JsonInput.parseObject("""{"outbounds":[{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"type":"field","ip":["geoip:ru"],"outboundTag":"direct"}]}}""")
        assertFalse(XrayExplicitRouting.apply(noProxy, "rules-in"))
    }

    private fun build(appRule: Boolean): Pair<JsonObject, JsonObject> {
        val server = Custom().apply { subtype = Custom.CUSTOM_XRAY_FULL_CONFIG; name = "RU"; config = provider }
        val profiles = mapOf<Long, Outbound>(1L to server)
        val route = RouteProfile.defaultProfile().apply {
            if (appRule) rules.add(RouteRule().apply { name = "4PDA"; package_name.add("ru.fourpda.client"); outbound_id = OutboundIds.PROXY })
        }
        val result = ConfigGenerator(ProfileProvider { profiles[it] }, GeneratorSettings(), routing = RoutingInput(route, RuleSetCatalog(emptyList()))).build(1)
        assertNull(result.error)
        return JsonInput.parseObject(result.coreConfig) to JsonInput.parseObject(result.xrayConfig!!)
    }

    @Test fun anAppRuleToProxyReachesTheServerPastTheProviderDirectExits() {
        val (core, xray) = build(appRule = true)
        val outbounds = core.array("outbounds").filterIsInstance<JsonObject>()
        val explicit = outbounds.single { it.string("tag") == Tags.PROXY_EXPLICIT }
        // A plain or a logical rule, depending on whether the package is installed.
        val rule = core.obj("route").array("rules").filterIsInstance<JsonObject>().single { it.toCompact().contains("ru.fourpda.client") }
        assertEquals(Tags.PROXY_EXPLICIT, rule.string("outbound"))
        // Everything else still enters the provider's routing through the usual bridge.
        assertEquals(Tags.PROXY, core.obj("route").string("final"))
        val inbound = xray.array("inbounds").filterIsInstance<JsonObject>().single { it.string("tag") == Tags.XRAY_EXPLICIT_IN }
        assertEquals(explicit.int("server_port"), inbound.int("port"))
        assertNotEquals(outbounds.single { it.string("tag") == Tags.PROXY }.int("server_port"), inbound.int("port"))
        assertTrue(inbound.obj("sniffing").bool("enabled"))
        assertEquals(Tags.XRAY_EXPLICIT_IN, rules(xray).first().array("inboundTag").strings().single())
    }

    @Test fun theBridgeAloneStaysWhenNoRuleNamesProxy() {
        val (core, _) = build(appRule = false)
        // The extra bridge exists, unused: the final route never takes it.
        assertNotEquals(Tags.PROXY_EXPLICIT, core.obj("route").string("final"))
        assertTrue(core.obj("route").array("rules").filterIsInstance<JsonObject>().none { it.string("outbound") == Tags.PROXY_EXPLICIT })
    }
}
