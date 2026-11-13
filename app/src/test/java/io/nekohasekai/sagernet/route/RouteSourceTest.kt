package app.rawline.mobile.route

import org.junit.Assert.*
import org.junit.Test

class RouteSourceTest {
    @Test fun providerRoutingLocksTheClientProfilesOnlyWhileItIsOn() {
        val on = RouteSource.kind("Happ", true, """{"Name":"RoscomVPN","DirectSites":[]}""", "Default", null)
        assertEquals(RouteSource.Kind.PROVIDER, on.kind)
        assertEquals("RoscomVPN", on.name)
        assertTrue(on.providerLocked)
        assertEquals("Incy", RouteSource.kind("Incy", true, "{}", "Default", null).name)
        val off = RouteSource.kind("Happ", true, "off", "Default", null)
        assertEquals(RouteSource.Kind.PROVIDER_OFF, off.kind)
        assertTrue(off.providerLocked)
        // Switched off by the user, or never sent: the client's profile.
        for (info in listOf(RouteSource.kind("Happ", false, "{}", "Default", null), RouteSource.kind("", true, "", "Default", null))) {
            assertEquals(RouteSource.Kind.CLIENT, info.kind)
            assertEquals("Default", info.name)
            assertFalse(info.providerLocked)
        }
    }
}
