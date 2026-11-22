package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.validation.Issue
import moe.matsuri.nb4a.utils.LogRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustTunnelCongestionTest {
    private fun parsed(congestion: String) = TrustTunnel().apply {
        parseFromJson(JsonInput.parseObject(
            """{"type":"trusttunnel","server":"example.org","server_port":443,"username":"u","password":"p","quic":true,"quic_congestion_control":"$congestion"}"""
        ))
    }

    @Test fun clientRandomAuthKeyRoundTripsAndValidatesSni() {
        val key = "0123456789abcdef"
        val profile = TrustTunnel()
        assertTrue(profile.parseFromLink("trusttunnel://tester:demo@example.test:443?client_random_auth_key=$key"))
        assertEquals(key, profile.client_random_auth_key)
        val restored = TrustTunnel()
        assertTrue(restored.parseFromLink(profile.exportToLink()))
        assertEquals(key, restored.client_random_auth_key)
        assertTrue(restored.parseFromJson(profile.exportToJson()))
        assertEquals(key, restored.build(BuildContext()).json.string("client_random_auth_key"))
        restored.server = "192.0.2.1"
        val issues = mutableListOf<Issue>()
        restored.validate(issues, BuildContext())
        assertTrue(issues.any { it.field == "client_random_auth_key" && it.value.isEmpty() })
        assertFalse(LogRedactor(false).redact(restored.exportToJson().toCompact()).contains(key))
    }

    @Test fun menuTextBecomesTheCoreName() {
        assertEquals("cubic", parsed("CUBIC").congestion_control)
        assertEquals("reno", parsed(" Reno ").congestion_control)
        // the core would run an unknown name as BBR under another label
        assertEquals("", parsed("vegas").congestion_control)
    }
}
