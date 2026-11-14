package app.rawline.mobile.database.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/** SQLite refuses a table that names a column twice, and the whole backup fails with it. */
class DesktopSchemaTest {
    @Test fun everyTableNamesEachColumnOnce() {
        DesktopSchema.DDL.filter { it.contains("CREATE TABLE") }.forEach { statement ->
            val columns = statement.lines().map { it.trim() }
                .filter { it.isNotEmpty() && it.first().isLetter() && !it.startsWith("CREATE") && !it.startsWith("PRIMARY") && !it.startsWith("FOREIGN") && !it.startsWith("UNIQUE") }
                .map { it.substringBefore(' ') }
            assertEquals(statement.lines().first { it.contains("CREATE TABLE") }, columns.distinct(), columns)
        }
    }
}
