package app.rawline.mobile.route

import app.rawline.mobile.outbound.json.JsonInput
import org.junit.Assert.assertEquals
import org.junit.Test

/** A server test without the provider's geo file keeps everything but the rules that need it. */
class ProviderGeoTestRulesTest {
    private val config = """{"routing":{"rules":[{"domain":["geosite:ru"],"outboundTag":"direct"},{"ip":["!geoip:ru"],"outboundTag":"proxy"},""" +
        """{"domain":["example.com"],"outboundTag":"block"}]},"dns":{"servers":[{"address":"1.1.1.1","domains":["geosite:ru"]},"8.8.8.8"]},"outbounds":[{"tag":"proxy"}]}"""

    @Test fun rulesAndDnsServersNamingTheMissingFileGo() {
        val noSites = JsonInput.parseObject(ProviderGeoAssets.withoutGeoRules(config, "geosite"))
        assertEquals(listOf("proxy", "block"), noSites.obj("routing").array("rules").map { (it as app.rawline.mobile.outbound.json.JsonObject).string("outboundTag") })
        assertEquals(listOf("8.8.8.8"), noSites.obj("dns").array("servers").strings())
        assertEquals(1, noSites.array("outbounds").size)
        val noIps = JsonInput.parseObject(ProviderGeoAssets.withoutGeoRules(config, "geoip"))
        assertEquals(listOf("direct", "block"), noIps.obj("routing").array("rules").map { (it as app.rawline.mobile.outbound.json.JsonObject).string("outboundTag") })
        assertEquals("not json", ProviderGeoAssets.withoutGeoRules("not json", "geoip"))
    }
}
