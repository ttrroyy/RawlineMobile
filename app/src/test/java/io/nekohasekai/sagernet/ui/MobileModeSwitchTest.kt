package app.rawline.mobile.ui

import app.rawline.mobile.bg.BaseService
import org.junit.Assert.*
import org.junit.Test

class MobileModeSwitchTest {
    @Test fun waitsForTheOldServiceToStopAndConsumesTheRequestOnce() {
        val switch = MobileModeSwitch()
        switch.request("vpn", "proxy")
        for (state in listOf(BaseService.State.Connecting, BaseService.State.Connected, BaseService.State.Stopping, BaseService.State.Idle)) {
            assertNull(switch.takeAfterStop(state))
            assertEquals("proxy", switch.pendingMode)
        }
        assertEquals("proxy", switch.takeAfterStop(BaseService.State.Stopped))
        assertNull(switch.takeAfterStop(BaseService.State.Stopped))
        switch.request("proxy", "vpn")
        assertEquals("vpn", switch.takeAfterStop(BaseService.State.Stopped))
    }

    @Test fun restoredRequestWaitsForTheServiceAndIsConsumedOnce() {
        val switch = MobileModeSwitch("vpn")
        assertNull(switch.takeAfterStop(BaseService.State.Stopping))
        assertEquals("vpn", switch.takeAfterStop(BaseService.State.Stopped))
        assertNull(switch.takeAfterStop(BaseService.State.Stopped))
    }

    @Test fun stoppingTheCurrentModeOrCancellingDoesNotRestartIt() {
        val switch = MobileModeSwitch()
        switch.request("vpn", "proxy")
        switch.request("vpn", "vpn")
        assertNull(switch.takeAfterStop(BaseService.State.Stopped))
        switch.request("vpn", "proxy")
        switch.cancel()
        assertNull(switch.takeAfterStop(BaseService.State.Stopped))
    }
}
