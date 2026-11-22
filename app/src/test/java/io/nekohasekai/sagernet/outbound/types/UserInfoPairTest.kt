package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.OutboundFactory
import app.rawline.mobile.validation.Issue
import app.rawline.mobile.validation.Severity
import app.rawline.mobile.outbound.link.Base64Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserInfoPairTest {
    @Test fun encodedSeparatorSplitsTuicAndJuicity() {
        val tuic = OutboundFactory.parseLink("tuic://id%3Asecret@example.org:443?sni=example.org") as Tuic
        assertEquals("id", tuic.uuid)
        assertEquals("secret", tuic.password)
        val literal = OutboundFactory.parseLink("tuic://id:sec%3Aret@example.org:443?sni=example.org") as Tuic
        assertEquals("id", literal.uuid)
        assertEquals("sec:ret", literal.password)
        val juicity = OutboundFactory.parseLink("juicity://id%3Ap%40ss@example.org:443?sni=example.org") as Juicity
        assertEquals("id", juicity.uuid)
        assertEquals("p@ss", juicity.password)
    }

    @Test fun juicityCertificateChainPinSurvivesLinkJsonAndBuild() {
        val pin = Base64Strict.encode(ByteArray(32) { -1 })
        val link = "juicity://11111111-1111-4111-8111-111111111111:demo@example.test:443?pinned_certchain_sha256=$pin"
        val profile = OutboundFactory.parseLink(link) as Juicity
        assertEquals(pin, profile.pinned_certchain_sha256)
        val shared = OutboundFactory.parseLink(profile.exportToLink()) as Juicity
        assertEquals(pin, shared.pinned_certchain_sha256)
        val restored = Juicity()
        assertTrue(restored.parseFromJson(profile.exportToJson()))
        assertEquals(pin, restored.build(BuildContext()).json.string("pinned_certchain_sha256"))
        assertTrue(JuicityChecks.juicityPinnedCertChain("ab".repeat(32)).isNotEmpty())
        restored.tls.certificate_sha256.add(pin)
        val issues = mutableListOf<Issue>()
        restored.validate(issues, BuildContext())
        assertTrue(issues.any { it.field == "pinned_certchain_sha256" && it.severity == Severity.Error })
    }

    @Test fun shadowsocksKeepsBothForms() {
        val plain = OutboundFactory.parseLink("ss://aes-256-gcm%3Ap%2Bss@example.org:8388") as Shadowsocks
        assertEquals("aes-256-gcm", plain.method)
        assertEquals("p+ss", plain.password)
        val encoded = Base64Strict.encode("aes-128-gcm:secret").trimEnd('=')
        val legacy = OutboundFactory.parseLink("ss://$encoded@example.org:8388") as Shadowsocks
        assertEquals("aes-128-gcm", legacy.method)
        assertEquals("secret", legacy.password)
    }
}
