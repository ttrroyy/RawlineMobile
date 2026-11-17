package app.rawline.mobile.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeoAssetSchedulerTest {
    @Test fun updatesMissingAndOldFilesButKeepsFreshFilesAndFutureTimestamps() {
        val day = TimeUnit.DAYS.toMillis(1)
        val now = day * 10
        assertTrue(GeoAssetScheduler.updateDue(0, now))
        assertTrue(GeoAssetScheduler.updateDue(now - day, now))
        assertFalse(GeoAssetScheduler.updateDue(now - day + 1, now))
        assertFalse(GeoAssetScheduler.updateDue(now + day, now))
    }
}
