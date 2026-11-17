package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.jsonObjectOf
import app.rawline.mobile.outbound.types.Socks
import org.junit.Assert.*
import org.junit.Test

class DnsMultipleServersTest {
    private fun generator(remote: String, direct: String) = ConfigGenerator(
        ProfileProvider { Socks().apply { server = "192.0.2.1"; serverPort = 1080 } },
        GeneratorSettings(remoteDns = remote, directDns = direct),
    )

    @Test fun listsAreTrimmedAndDeduplicatedAndEachAddressIsValidated() {
        assertEquals(listOf("1.1.1.1", "tls://8.8.8.8"), DnsServers.splitAddresses(" 1.1.1.1, tls://8.8.8.8,1.1.1.1, "))
        assertEquals("", DnsServers.addressListProblem("1.1.1.1, tls://8.8.8.8"))
        assertTrue(DnsServers.addressListProblem("1.1.1.1, http://bad.example").contains("http://bad.example"))
        assertTrue(DnsServers.addressListProblem(" , ").isNotEmpty())
    }

    @Test fun severalResolversKeepTheirDetoursAndProduceOnlyKnownServerReferences() {
        val result = generator("https://1.1.1.1/dns-query,https://8.8.8.8/dns-query", "8.8.8.8,1.1.1.1").build(1)
        assertNull(result.error)
        val dns = JsonInput.parseObject(result.coreConfig).obj("dns")
        val servers = dns.array("servers").filterIsInstance<JsonObject>().associateBy { it.string("tag") }
        assertEquals(2, servers.values.count { it.string("detour") == "proxy" })
        assertTrue(servers.values.filter { it.string("tag").startsWith("dns-direct") }.all { !it.contains("detour") })
        val rules = dns.array("rules").filterIsInstance<JsonObject>()
        assertEquals(2, rules.count { it.string("action") == "evaluate" })
        assertTrue(rules.filter { it.contains("server") }.all { servers.containsKey(it.string("server")) })
        assertEquals(4, rules.count { it.string("action") == "respond" && it.bool("race") })
        assertEquals("SERVFAIL", rules.last().string("rcode"))
    }

    @Test fun responseTagsAreUniqueAndConditionsAndFamilyGuardsAreKept() {
        val generator = generator("1.1.1.1", "8.8.8.8")
        val guard = jsonObjectOf("query_type" to JsonArray.of("AAAA"), "action" to "predefined")
        val source = JsonArray.of(guard,
            jsonObjectOf("domain_suffix" to JsonArray.of("example.org"), "action" to "route", "server" to "direct"),
            jsonObjectOf("package_name" to JsonArray.of("app.test"), "action" to "route", "server" to "direct"))
        val rules = generator.expandDnsRaces(source, mapOf("direct" to listOf("first", "second"))).filterIsInstance<JsonObject>()
        assertEquals(guard, rules.first())
        val evaluations = rules.filter { it.string("action") == "evaluate" }
        assertEquals(4, evaluations.map { it.string("tag") }.distinct().size)
        assertEquals(2, evaluations.count { it.array("domain_suffix").contains("example.org") })
        assertEquals(2, evaluations.count { it.array("package_name").contains("app.test") })
        assertTrue(rules.filter { it.contains("match_response") }.all { response ->
            evaluations.any { it.string("tag") == response.string("match_response") }
        })
        assertTrue(rules.filter { it.string("rcode") == "SERVFAIL" }.none { it.contains("server") })
    }

    @Test fun aSingleResolverDoesNotChangeTheExistingRuleShape() {
        val result = generator("https://1.1.1.1/dns-query", "8.8.8.8").build(1)
        assertNull(result.error)
        val rules = JsonInput.parseObject(result.coreConfig).obj("dns").array("rules").filterIsInstance<JsonObject>()
        assertFalse(rules.any { it.string("action") == "evaluate" || it.contains("race") })
        assertEquals("dns-remote", rules.last().string("server"))
    }
}
