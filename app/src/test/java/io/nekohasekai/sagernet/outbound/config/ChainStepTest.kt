package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.types.Socks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChainStepTest {
    @Test fun buildsSoloAndThroughProbesForTheSecondHopAndGroupLanding() {
        val profiles = ProfileProvider { id ->
            if (id in 1L..4L) Socks().apply {
                server = "192.0.2.$id"
                serverPort = 1080
            } else null
        }
        val report = ConfigGenerator(profiles, GeneratorSettings()).buildChainStepTest(
            listOf(1L, 2L), landingProxyId = 4L, frontProxyId = 3L,
        )
        assertEquals("", report.error)
        assertNotNull(report.build)
        assertEquals(
            setOf(0 to false, 1 to true, 1 to false, 2 to true, 2 to false),
            report.tagToProbe.values.map { it.hop to it.alone }.toSet(),
        )
    }
}
