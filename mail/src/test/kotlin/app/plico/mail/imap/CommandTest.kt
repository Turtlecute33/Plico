package app.plico.mail.imap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandTest {

    @Test
    fun verb() {
        assertEquals("UID", Command.raw("UID FETCH", "1:*", "(FLAGS)").verb)
        assertEquals("SELECT", Command.build { raw("select"); string("INBOX") }.verb)
        assertEquals("", Command.build { string("x") }.verb)
    }

    @Test
    fun nonAsciiIsFoundInNestedLists() {
        assertFalse(Command.build { raw("X-GM-RAW"); string("in:inbox") }.hasNonAscii)
        assertTrue(Command.build { raw("TEXT"); string("Grüße") }.hasNonAscii)
        assertTrue(Command.build { list { list { string("日本") } } }.hasNonAscii)
        assertFalse(Command.build { bytes("ü".toByteArray()) }.hasNonAscii)
    }

    @Test
    fun listAndAppend() {
        val criteria = Command.build { raw("UID"); raw("5:*") }
        val command = Command.build {
            raw("UID SEARCH")
            append(criteria)
            list { raw("\\Inbox"); string("Work") }
        }
        assertEquals(
            listOf(
                Command.Part.Raw("UID SEARCH"),
                Command.Part.Raw("UID"),
                Command.Part.Raw("5:*"),
                Command.Part.Group(listOf(Command.Part.Raw("\\Inbox"), Command.Part.Text("Work"))),
            ),
            command.parts,
        )
    }

    @Test
    fun rawRejectsLineBreaks() {
        assertFailsWith<IllegalArgumentException> { Command.raw("NOOP\r\nA2 LOGOUT") }
        assertFailsWith<IllegalArgumentException> { Command.build { raw("X\n") } }
    }
}
