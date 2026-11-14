package app.rawline.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Test

/** A server test without the provider's geo file keeps everything but the rules that need it. */
class ProviderGeoTestRulesTest {
    @Test fun rulesAndDnsServersNamingTheMissingFileGo() {
        val config = """{"routing":{"rules":[{"domain":["geosite:ru"],"outboundTag":"direct"},{"ip":["!geoip:ru"],"outboundTag":"proxy"},""" +
            """{"domain":["example.com"],"outboundTag":"block"}]},"dns":{"servers":[{"address":"1.1.1.1","domains":["geosite:ru"]},"8.8.8.8"]},"outbounds":[{"tag":"proxy"}]}"""
        assertEquals(
            """{"routing":{"rules":[{"ip":["!geoip:ru"],"outboundTag":"proxy"},{"domain":["example.com"],"outboundTag":"block"}]},""" +
                """"dns":{"servers":["8.8.8.8"]},"outbounds":[{"tag":"proxy"}]}""",
            ProviderGeoAssets.withoutGeoRules(config, "geosite"))
        assertEquals("""{"routing":{"rules":[{"domain":["example.com"],"outboundTag":"block"}]},"outbounds":[{"tag":"proxy"}]}""",
            ProviderGeoAssets.withoutGeoRules("""{"routing":{"rules":[{"ip":["!geoip:ru"]},{"domain":["example.com"],"outboundTag":"block"}]},"outbounds":[{"tag":"proxy"}]}""", "geoip"))
        assertEquals("not json", ProviderGeoAssets.withoutGeoRules("not json", "geoip"))
    }
}
