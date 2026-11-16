package app.rawline.mobile.outbound.config

import app.rawline.mobile.database.SettingsRegistry
import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.types.Socks
import org.junit.Assert.*
import org.junit.Test

class UnownedTunGuardTest {
    private fun build(settings: GeneratorSettings, probe: Boolean = false): JsonObject {
        val profile = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
        val generator = ConfigGenerator(ProfileProvider { profile }, settings)
        val result = if (probe) generator.buildTest(listOf(TestCandidate(1))) else generator.build(1)
        assertNull(result.error)
        return JsonInput.parseObject(result.coreConfig).obj("route")
    }
    private fun guards(route: JsonObject) = route.array("rules").filterIsInstance<JsonObject>().filter { rule ->
        rule.string("action") == "reject" && rule.array("rules").filterIsInstance<JsonObject>().any { it.contains("package_name_regex") }
    }
    @Test fun baseModeChecksOnlyTcpFromTheTunnelAddresses() {
        val settings = GeneratorSettings(vpnMode = true, trafficStats = false,
            tunIPv4Cidr = "172.19.0.1/30", tunIPv6Cidr = "fdfe:dcba:9876::1/126")
        val route = build(settings)
        val guard = guards(route).single()
        val conditions = guard.array("rules").filterIsInstance<JsonObject>()
        val traffic = conditions[0]
        assertEquals("tcp", traffic.string("network"))
        assertEquals(listOf(Tags.TUN_IN), traffic.array("inbound").strings())
        assertEquals(listOf("172.19.0.1/32", "fdfe:dcba:9876::1/128"), traffic.array("source_ip_cidr").strings())
        assertTrue("Apps with an identified package must not be rejected", conditions.single { it.contains("package_name_regex") }.bool("invert"))
        assertTrue(route.bool("find_process"))
        val strict = guards(build(settings.copy(blockUnownedTunStrict = true))).single().array("rules")[0] as JsonObject
        assertFalse(strict.contains("network"))
        assertFalse(strict.contains("source_ip_cidr"))
    }

    @Test fun strictModeIncludesUnknownUdpIcmpAndForwardedClientsButKeepsKnownOwners() {
        val route = build(GeneratorSettings(vpnMode = true, blockUnownedTun = true, blockUnownedTunStrict = true, trafficStats = false))
        val rule = guards(route).single()
        val conditions = rule.array("rules").filterIsInstance<JsonObject>()
        val ingress = conditions[0]
        assertEquals(listOf(Tags.TUN_IN), ingress.array("inbound").strings())
        assertFalse("Strict protection must also cover non-TCP", ingress.contains("network"))
        assertFalse("Forwarded clients must not escape strict checks by their source address", ingress.contains("source_ip_cidr"))
        assertTrue(conditions.single { it.contains("package_name_regex") }.bool("invert"))
        val system = conditions.single { it.contains("user_id") }
        assertTrue(system.bool("invert"))
        assertTrue(system.array("user_id").map { it.toString() }.contains("1000"))
        assertTrue(route.bool("find_process"))
        val rules = route.array("rules")
        assertTrue(rules.indexOf(rule) < rules.indexOfFirst { (it as? JsonObject)?.string("action") == "sniff" })
    }
    @Test fun disabledParentWinsOverASavedStrictSetting() {
        assertTrue(guards(build(GeneratorSettings(vpnMode = true, blockUnownedTun = false, blockUnownedTunStrict = true))).isEmpty())
    }
    @Test fun proxyAndProbeConfigsNeverReceiveTheTunGuard() {
        val settings = GeneratorSettings(vpnMode = false, blockUnownedTun = true, blockUnownedTunStrict = true)
        assertTrue(guards(build(settings)).isEmpty())
        assertTrue(guards(build(settings.copy(vpnMode = true), probe = true)).isEmpty())
    }
    @Test fun defaultsEnableOnlyTheBaseMode() {
        assertTrue(SettingsRegistry.BLOCK_UNOWNED_TUN.default)
        assertFalse(SettingsRegistry.BLOCK_UNOWNED_TUN_STRICT.default)
        assertTrue(GeneratorSettings().blockUnownedTun)
        assertFalse(GeneratorSettings().blockUnownedTunStrict)
    }
}
