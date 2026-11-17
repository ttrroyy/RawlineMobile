package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.Socks
import org.junit.Assert.*
import org.junit.Test

class TunStackTest {
    @Test fun autoKeepsTheCoreDefaultAndExplicitChoicesReachOnlyTheTun() {
        val profile = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
        for (stack in listOf("", "system", "gvisor", "mixed")) {
            val generator = ConfigGenerator(ProfileProvider { profile }, GeneratorSettings(vpnMode = true, tunStack = stack))
            val result = generator.build(1)
            assertNull(result.error)
            val inbounds = JsonInput.parseObject(result.coreConfig).array("inbounds").filterIsInstance<JsonObject>()
            val tun = inbounds.single { it.string("type") == "tun" }
            assertEquals(stack.isNotEmpty(), tun.contains("stack"))
            if (stack.isNotEmpty()) assertEquals(stack, tun.string("stack"))
            val probe = generator.buildTest(listOf(TestCandidate(1)))
            assertNull(probe.error)
            assertFalse(JsonInput.parseObject(probe.coreConfig).array("inbounds").filterIsInstance<JsonObject>().any {
                it.string("type") == "tun" || it.contains("stack")
            })
        }
    }
}
