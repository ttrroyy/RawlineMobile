package app.rawline.mobile.group

import org.junit.Assert.*
import org.junit.Test

class ProviderAppsTest {
    private fun parse(vararg fields: Pair<String, String>, fetch: (String) -> String = { error("no download expected") }) =
        ProviderApps.parse(fields.toMap(), fetch)

    @Test fun nothingSentLeavesTheUserSettings() {
        assertNull(parse())
        assertEquals("", ProviderApps.read({ "" }, "vless://a@b:1#x"))
    }

    @Test fun happModes() {
        assertEquals(ProviderApps(ProviderApps.PROXY, listOf("com.google.chrome", "com.meta.instagram")),
            parse("per-app-proxy-mode" to "on", "per-app-proxy-list" to "com.google.chrome,com.meta.instagram"))
        assertEquals(ProviderApps.BYPASS, parse("per-app-proxy-mode" to "bypass", "per-app-proxy-list" to "ru.sberbankmobile")!!.mode)
        assertTrue(parse("per-app-proxy-mode" to "off")!!.off)
        // "All apps except the list" puts the listed ones past the VPN.
        assertEquals(ProviderApps.BYPASS, parse("per-app-proxy-mode" to "on", "per-app-proxy-list-invert" to "ru.sberbankmobile")!!.mode)
        assertEquals(listOf("org.telegram.messenger"), parse("per-app-proxy-mode" to "on", "per-app-proxy-list-set" to "org.telegram.messenger")!!.packages)
    }

    @Test fun incyFields() {
        assertTrue(parse("per-app-proxy-enable" to "0", "per-app-proxy-mode" to "proxy", "per-app-proxy-list" to "a.b")!!.off)
        assertEquals(ProviderApps(ProviderApps.BYPASS, listOf("ru.fourpda.client", "ru.vk.app")),
            parse("per-app-proxy-enable" to "1", "per-app-proxy-mode" to "bypass", "per-app-proxy-list" to "ru.fourpda.client\nru.vk.app\nnot a package"))
        val base64 = java.util.Base64.getEncoder().encodeToString("com.a.b,com.c.d".toByteArray())
        assertEquals(listOf("com.a.b", "com.c.d"), parse("per-app-proxy-enable" to "1", "per-app-proxy-list" to base64)!!.packages)
        assertEquals(listOf("com.x.y"), parse("per-app-proxy-mode" to "proxy", "per-app-proxy-list" to "https://example.com/apps.txt") { "com.x.y\n" }!!.packages)
    }

    @Test fun aModeWithoutAppsOrAnUnknownModeChangesNothing() {
        assertNull(parse("per-app-proxy-mode" to "proxy"))
        assertNull(parse("per-app-proxy-mode" to "sideways", "per-app-proxy-list" to "a.b"))
        assertNull(parse("per-app-proxy-mode" to "proxy", "per-app-proxy-list" to "https://example.com/apps.txt") { "" })
    }

    @Test fun headersWinOverBodyLinesAndTheStoredFormRoundTrips() {
        val headers = mapOf("per-app-proxy-mode" to "bypass", "per-app-proxy-list" to "com.a.b")
        val body = "#per-app-proxy-mode: on\n#per-app-proxy-list: com.c.d\nvless://u@h:1#n"
        val stored = ProviderApps.read({ headers[it].orEmpty() }, body)
        assertEquals(ProviderApps(ProviderApps.BYPASS, listOf("com.a.b")), ProviderApps.fromJson(stored))
        assertEquals(ProviderApps(ProviderApps.PROXY, listOf("com.c.d")), ProviderApps.fromJson(ProviderApps.read({ "" }, body)))
        assertNull(ProviderApps.fromJson(""))
        assertNull(ProviderApps.fromJson("{\"mode\":\"nope\"}"))
    }
}
