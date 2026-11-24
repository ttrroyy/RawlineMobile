package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.AutoSelector
import app.rawline.mobile.outbound.types.Chain
import app.rawline.mobile.outbound.types.Socks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorChainTest {
    @Test fun membersShareTheFixedEntryAndTheExitDialsTheSelector() {
        fun socks(id: Int) = Socks().apply {
            server = "192.0.2.$id"
            serverPort = 1080
        }
        val entry = socks(1)
        val exit = socks(2)
        val member = socks(3)
        val selector = AutoSelector().apply { gids = mutableListOf(5) }
        val chain = Chain().apply { list.addAll(listOf(1L, 9L, 2L)) }
        val profiles = mapOf(1L to entry, 2L to exit, 3L to member, 9L to selector, 10L to chain)
        val routing = RoutingInput(
            RoutingInput.DEFAULT.profile, RoutingInput.DEFAULT.catalog,
            SelectorStore {
                if (it == 5L) SelectorGroup(5, "Tests", -1, -1, null, null, listOf(SelectorMember(3, member, 0, 0, "")))
                else null
            },
        )
        val result = ConfigGenerator(ProfileProvider(profiles::get), GeneratorSettings(), routing = routing).build(10L)
        assertEquals(result.error, true, result.ok)
        val build = result.autoSelector
        assertNotNull(build)
        assertTrue(requireNotNull(build).upstream)
        assertEquals(9L, build.selectorId)
        val outbounds = JsonInput.parseObject(result.coreConfig).array("outbounds").filterIsInstance<JsonObject>()
            .associateBy { it.string("tag") }
        assertEquals("auto-selector", outbounds.getValue("proxy").string("detour"))
        assertEquals("upstream-0", outbounds.getValue(build.members.keys.single()).string("detour"))
    }
}
