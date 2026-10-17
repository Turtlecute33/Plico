package app.plico.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaTest {
    /** The schema must stay exactly the DESIGN.md §5 blocks; a change there needs a new version and a migration. */
    @Test
    fun matchesDesignVerbatim() {
        val design = File("../docs/DESIGN.md").readText().substringAfter("## 5. Data").substringBefore("## 6.")
        val v1 = design.substringAfter("```sql\n").substringBefore("```")
        assertEquals(normalize(v1), normalize(Schema.V1.joinToString("\n")))
        // The migration block of DESIGN.md has SQL comments; Schema.MIGRATIONS has them as Kotlin comments.
        for ((i, heading) in listOf("### Migration 1 → 2", "### Migration 2 → 3").withIndex()) {
            val migration = design.substringAfter(heading).substringAfter("```sql\n").substringBefore("```")
            assertEquals(normalize(migration.replace(sqlComment, "")), normalize(Schema.MIGRATIONS[i].joinToString("\n")), heading)
        }
    }

    @Test
    fun statementsAreSingle() {
        assertEquals(3, Schema.VERSION)
        assertEquals(Schema.VERSION - 1, Schema.MIGRATIONS.size)
        for (statement in Schema.V1 + Schema.MIGRATIONS.flatten()) {
            // execSQL runs one statement; outside comments, only the trigger body holds a semicolon.
            val semicolons = statement.replace(sqlComment, "").count { it == ';' }
            assertEquals(if (statement.startsWith("CREATE TRIGGER")) 1 else 0, semicolons, statement)
        }
        // ALTER TABLE ADD COLUMN copies a trailing comment into the stored CREATE TABLE and breaks it.
        for (statement in Schema.MIGRATIONS.flatten()) assertTrue("--" !in statement, statement)
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

    private val sqlComment = Regex("--[^\n]*")

    private fun normalize(sql: String) = sql.replace(";", "").replace(Regex("\\s+"), " ").trim()
}
