package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.outbound.types.XrayVless
import app.rawline.mobile.route.RouteProfile
import app.rawline.mobile.route.RuleSetCatalog
import org.junit.Assert.*
import org.junit.Test

class DnsRulesTest {
    @Test fun pickerReplacesOnlyRuleSetsAndKeepsSitesAndUrls() {
        val selected = listOf("geosite-category-ai-chat-!cn", "https://example.org/custom.srs", "geosite-category-ai-chat-!cn")
        val sites = DnsRules.withRuleSets(listOf("full:example.com", "geosite:old", "rule-set:previous"), selected)
        assertEquals(listOf("full:example.com", "rule-set:geosite-category-ai-chat-!cn", "rule-set:https://example.org/custom.srs"), sites)
        assertEquals(selected.distinct(), DnsRules.parse(sites).ruleSets)
        assertEquals("", DnsRule(sites = sites, server = "1.1.1.1").problem())
        assertEquals(listOf("full:example.com"), DnsRules.withRuleSets(sites, emptyList()))
    }

    private val twitch = DnsRule(sites = listOf("geosite:category-ai-chat-!cn", "domain:usher.ttvnw.net", "full:gql.twitch.tv",
        "keyword:openai", "regexp:^chat[0-9]+\\.example\\.org$", "example.com"), server = "https://dns.zapret.moe/dns-query")

    @Test fun sitesTakeTheNekoBoxForms() {
        val match = DnsRules.parse(twitch.sites)
        assertEquals(listOf("usher.ttvnw.net", "example.com"), match.suffixes)
        assertEquals(listOf("gql.twitch.tv"), match.domains)
        assertEquals(listOf("openai"), match.keywords)
        assertEquals(listOf("^chat[0-9]+\\.example\\.org$"), match.regexes)
        assertEquals(listOf("geosite-category-ai-chat-!cn"), match.ruleSets)
        assertEquals(listOf("a.com", "b.com", "c.com"), DnsRules.lines("a.com\n\n# note\nb.com, c.com"))
    }

    @Test fun storedRulesRoundTripAndDamageReadsAsNone() {
        val rules = listOf(twitch, DnsRule(enabled = false, sites = listOf("x.org"), server = "1.1.1.1", direct = true))
        assertEquals(rules, DnsRules.decode(DnsRules.encode(rules)))
        for (bad in listOf("", "{", "{\"a\":1}", "[1,\"x\",null]")) assertEquals(bad, emptyList<DnsRule>(), DnsRules.decode(bad))
    }

    @Test fun problemsAreNamedAndDisabledRulesAreNotChecked() {
        assertEquals("", twitch.problem())
        assertNotEquals("", DnsRule(sites = emptyList(), server = "1.1.1.1").problem())
        assertNotEquals("", DnsRule(sites = listOf("regexp:(["), server = "1.1.1.1").problem())
        assertNotEquals("", DnsRule(sites = listOf("ip:1.2.3.4"), server = "1.1.1.1").problem())
        assertNotEquals("", DnsRule(sites = listOf("x.org"), server = "").problem())
        assertEquals("", DnsRule(enabled = false, sites = emptyList(), server = "").problem())
    }

    @Test fun ruleResolvesItsSitesThroughItsServerBeforeTheFinal() {
        val catalog = RuleSetCatalog(listOf("geosite-category-ai-chat-!cn" to "https://example.org/ai.srs"))
        val config = build(GeneratorSettings(dnsRules = listOf(twitch, DnsRule(sites = listOf("full:local.test"), server = "8.8.8.8", direct = true))), catalog)
        val dns = config.obj("dns")
        val servers = dns.array("servers").filterIsInstance<JsonObject>().associateBy { it.string("tag") }
        val proxied = servers.getValue("dns-user-0")
        assertEquals("https", proxied.string("type"))
        assertEquals("dns.zapret.moe", proxied.string("server"))
        assertEquals("proxy", proxied.string("detour"))
        assertEquals("dns-local", proxied.string("domain_resolver"))
        val direct = servers.getValue("dns-user-1")
        assertFalse(direct.contains("detour"))
        val rules = dns.array("rules").filterIsInstance<JsonObject>()
        val first = rules.indexOfFirst { it.string("server") == "dns-user-0" }
        assertTrue(first >= 0)
        assertTrue(rules.any { it.string("server") == "dns-user-0" && (it["rule_set"] as? JsonArray)?.strings() == listOf("geosite-category-ai-chat-!cn") })
        assertTrue(rules.any { it.string("server") == "dns-user-0" && (it["domain_suffix"] as? JsonArray)?.strings()?.contains("usher.ttvnw.net") == true })
        assertTrue(rules.any { it.string("server") == "dns-user-1" && (it["domain"] as? JsonArray)?.strings() == listOf("local.test") })
        // The fall-through stays last.
        assertTrue(first < rules.indexOfLast { it.string("server") == "dns-remote" })
        val sets = config.obj("route").array("rule_set").filterIsInstance<JsonObject>().map { it.string("tag") }
        assertTrue("geosite-category-ai-chat-!cn" in sets)
    }

    @Test fun unknownListsAndBrokenRulesNeverStopTheConnection() {
        val config = build(GeneratorSettings(dnsRules = listOf(
            DnsRule(sites = listOf("geosite:not-in-catalog"), server = "1.1.1.1"),
            DnsRule(sites = listOf("x.org"), server = "bad://"),
            DnsRule(enabled = false, sites = listOf("y.org"), server = "1.1.1.1"),
        )), RuleSetCatalog(emptyList()))
        val tags = config.obj("dns").array("servers").filterIsInstance<JsonObject>().map { it.string("tag") }
        assertTrue(tags.none { it.startsWith("dns-user") })
    }

    @Test fun customRuleSetUrlIsRegisteredAndUsesItsOwnDnsServer() {
        val url = "https://example.org/custom.srs?labels=a,full:b"
        val sites = DnsRules.lines("rule-set:$url, full:local.test")
        assertEquals(listOf("rule-set:$url", "full:local.test"), sites)
        val config = build(GeneratorSettings(dnsRules = listOf(
            DnsRule(sites = sites, server = "1.1.1.1"),
        )), RuleSetCatalog(emptyList()))
        val set = config.obj("route").array("rule_set").filterIsInstance<JsonObject>().single()
        assertEquals(url, set.string("url"))
        val rules = config.obj("dns").array("rules").filterIsInstance<JsonObject>()
        assertTrue(rules.any { it.string("server") == "dns-user-0" &&
            (it["rule_set"] as? JsonArray)?.strings() == listOf(set.string("tag")) })
    }

    @Test fun xrayResolvesTheRuleServerOverHttps() {
        val xray = XrayVless().apply { server = "192.0.2.1"; serverPort = 443; uuid = "5a351653-bef1-4baf-a6e9-89cbf1a2329e" }
        val result = ConfigGenerator(ProfileProvider { xray }, GeneratorSettings(dnsRules = listOf(DnsRule(sites = listOf("x.org"), server = "8.8.8.8")))).build(1)
        assertNull(result.error)
        val server = JsonInput.parseObject(result.coreConfig).obj("dns").array("servers").filterIsInstance<JsonObject>().first { it.string("tag") == "dns-user-0" }
        assertEquals("https", server.string("type"))
    }

    private fun build(settings: GeneratorSettings, catalog: RuleSetCatalog): JsonObject {
        val profile = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
        val result = ConfigGenerator(ProfileProvider { profile }, settings, routing = RoutingInput(RouteProfile.defaultProfile(), catalog)).build(1)
        assertNull(result.error)
        return JsonInput.parseObject(result.coreConfig)
    }
}
