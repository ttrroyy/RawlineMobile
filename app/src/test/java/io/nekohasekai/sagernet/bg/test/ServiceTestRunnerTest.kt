package app.rawline.mobile.bg.test

import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceTestRunnerTest {
    @Test fun savesKnownCategoriesAndPreservesUnlistedResults() {
        val categories = ServiceTestRunner.parseCategories(
            """{"categories":[{"id":"unused","name":"Unused"},{"id":"streaming","name":"Streaming"}]}""",
        )
        val document = ServiceTestRunner.resultDocument(
            """{"checks":[{"id":"example","category":"streaming","status":"available"},{"id":"test","category":"other","status":"restricted"}]}""",
            "", categories, 123L,
        )
        val saved = JsonInput.parseObject(document)
        assertEquals(listOf("streaming", "other"), saved.array("cats").filterIsInstance<JsonObject>().map { it.string("id") })
        assertEquals(listOf("available", "restricted"), saved.array("checks").filterIsInstance<JsonObject>().map { it.string("status") })
        assertEquals("timeout", JsonInput.parseObject(ServiceTestRunner.resultDocument("", "timeout", categories, 123L)).string("error"))
    }
}
