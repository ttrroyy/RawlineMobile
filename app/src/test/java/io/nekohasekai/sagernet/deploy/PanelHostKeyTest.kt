package app.rawline.mobile.deploy

import org.junit.Assert.*
import org.junit.Test

/** Regression: a VPS reinstalled has a new SSH key; the app must ask again and remember the new one, not fail. */
class PanelHostKeyTest {
    private fun server(key: String = "") = PanelCommands.newServer().apply { this["hostKey"] = key }

    @Test fun storedKeyPassesWithoutAsking() {
        val server = server("SHA256:old")
        assertTrue(PanelSsh.acceptHostKey(server, "SHA256:old") { _, _ -> fail("asked about the stored key"); false })
    }

    @Test fun firstKeyIsAskedAsNewAndStored() {
        val server = server()
        var changed: Boolean? = null
        assertTrue(PanelSsh.acceptHostKey(server, "SHA256:first") { _, c -> changed = c; true })
        assertEquals(false, changed)
        assertEquals("SHA256:first", server["hostKey"])
    }

    @Test fun changedKeyIsAskedAsChangedAndReplacesTheOld() {
        val server = server("SHA256:old")
        var asked: Pair<String, Boolean>? = null
        assertTrue(PanelSsh.acceptHostKey(server, "SHA256:new") { f, c -> asked = f to c; true })
        assertEquals("SHA256:new" to true, asked)
        assertEquals("SHA256:new", server["hostKey"])
    }

    @Test fun refusedKeyIsNotStored() {
        val server = server("SHA256:old")
        assertFalse(PanelSsh.acceptHostKey(server, "SHA256:new") { _, _ -> false })
        assertEquals("SHA256:old", server["hostKey"])
    }
}
