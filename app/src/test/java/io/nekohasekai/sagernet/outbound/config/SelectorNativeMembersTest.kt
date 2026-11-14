package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.`import`.ProfileImport
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.AutoSelector
import org.junit.Assert.*
import org.junit.Test

/** An auto-selector of VK-call servers alone builds: the core check never sees their bridgeless outbound. */
class SelectorNativeMembersTest {
    @Test fun nativeTransportMembersAreNotRejectedByTheCoreCheck() {
        val native = ProfileImport.parse("qwdtt://config?peer=192.0.2.1%3A56002&pass=fixture&hashes=demo_hash_123456789").outbounds.single()
        val members = listOf(SelectorMember(1, native, 0, 0, ""))
        val selector = AutoSelector().apply { gids = mutableListOf(5) }
        // A core check that turns away anything it cannot build alone, as the real one does with a native transport.
        val check = MemberCheck { list, ctx -> list.filter { (_, outbound) -> !outbound.buildChecked(ctx).ok }.associate { it.first to "rejected" } }
        val routing = RoutingInput(RoutingInput.DEFAULT.profile, RoutingInput.DEFAULT.catalog,
            SelectorStore { if (it == 5L) SelectorGroup(5, "TURN+", -1, -1, null, null, members) else null }, check)
        val result = ConfigGenerator(ProfileProvider { when (it) { 1L -> native; 10L -> selector; else -> null } }, GeneratorSettings(), routing = routing).build(10)
        assertNull(result.error)
        val group = JsonInput.parseObject(result.coreConfig).array("outbounds").filterIsInstance<JsonObject>().single { it.string("type") == "auto-selector" }
        assertEquals(1, group.array("outbounds").size)
    }
}
