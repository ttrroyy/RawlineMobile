package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.json.JsonInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSelectorGroupsTest {
    @Test fun passiveScoringIsOptInAndKeepsItsSiteMemoryThroughExport() {
        val selector = AutoSelector()
        assertFalse(selector.passive)
        selector.parseFromJson(JsonInput.parseObject("""{"type":"autoselector","name":"a","passive":true,"site_memory_mins":5000}"""))
        selector.normalize()
        val restored = AutoSelector()
        restored.parseFromJson(selector.exportToJson())
        assertTrue(restored.passive)
        assertEquals(1440, restored.siteMemoryMins)
    }

    @Test fun singleGroupProfilesKeepTracking() {
        val old = AutoSelector()
        old.parseFromJson(JsonInput.parseObject("""{"type":"autoselector","name":"a","gid":5}"""))
        assertEquals(listOf(5L), old.gids)
        val exported = old.exportToJson()
        assertEquals(5L, (exported["gid"] as Number).toLong())

        val multi = AutoSelector()
        multi.parseFromJson(JsonInput.parseObject("""{"type":"autoselector","name":"a","gid":5,"gids":[3,5,3,-1]}"""))
        multi.normalize()
        assertEquals(listOf(3L, 5L), multi.gids)
        val restored = AutoSelector()
        restored.parseFromJson(multi.exportToJson())
        assertEquals(listOf(3L, 5L), restored.gids)
    }
}
