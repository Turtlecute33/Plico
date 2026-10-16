package app.plico.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaTest {
    /** The schema must stay exactly the DESIGN.md §5 block; a change there needs a new version and a migration. */
    @Test
    fun matchesDesignVerbatim() {
        val design = File("../docs/DESIGN.md").readText()
        val block = design.substringAfter("## 5. Data").substringAfter("```sql\n").substringBefore("```")
        assertEquals(normalize(block), normalize(Schema.V1.joinToString("\n")))
    }

    @Test
    fun statementsAreSingle() {
        assertEquals(1, Schema.VERSION)
        for (statement in Schema.V1) {
            // execSQL runs one statement; outside comments, only the trigger body holds a semicolon.
            val semicolons = statement.replace(Regex("--[^\n]*"), "").count { it == ';' }
            assertEquals(if (statement.startsWith("CREATE TRIGGER")) 1 else 0, semicolons, statement)
        }
        assertTrue(Schema.V1.any { "AFTER DELETE ON message" in it && "DELETE FROM message_fts" in it })
    }

    @Test
    fun tableNamesMatchTheSchema() {
        val created = Schema.V1.mapNotNull { Regex("""CREATE (?:VIRTUAL )?TABLE (\w+)""").find(it)?.groupValues?.get(1) }
        val names = listOf(
            Tables.ACCOUNT, Tables.IDENTITY, Tables.FOLDER, Tables.MESSAGE, Tables.MESSAGE_FOLDER, Tables.BODY,
            Tables.ATTACHMENT, Tables.MESSAGE_FTS, Tables.OP, Tables.DRAFT, Tables.CONTACT, Tables.TRUSTED_SENDER,
        )
        assertEquals(names.toSet(), created.toSet())
        assertEquals(setOf(Tables.CONTACT, Tables.TRUSTED_SENDER), names.toSet() - Tables.ACCOUNT_OWNED.toSet())
    }

    private fun normalize(sql: String) = sql.replace(";", "").replace(Regex("\\s+"), " ").trim()
}
