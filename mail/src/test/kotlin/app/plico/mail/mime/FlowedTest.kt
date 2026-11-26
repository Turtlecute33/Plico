package app.plico.mail.mime

import org.junit.Test
import kotlin.test.assertEquals

class FlowedTest {
    @Test
    fun softBreakJoins() {
        assertEquals("one two three\nnext", unflow("one \ntwo \nthree\nnext", false))
    }

    @Test
    fun delSpDropsTheSpace() {
        assertEquals("onetwo", unflow("one \ntwo", true))
        assertEquals("one two", unflow("one  \ntwo", true))
    }

    @Test
    fun quoteDepthChangeIsAHardBreak() {
        assertEquals("> a b\n>> c\nd", unflow("> a \n> b\n>> c \nd", false))
        assertEquals("> a\nb", unflow("> a \nb", false))
    }

    @Test
    fun quotedLinesJoin() {
        assertEquals("> a b c", unflow(">a \n> b \n>c", false))
    }

    @Test
    fun signatureNeverFlows() {
        assertEquals("text\n-- \nname", unflow("text\n-- \nname", false))
        // Also after a flowed line: the separator starts its own line.
        assertEquals("text\n-- \nname", unflow("text \n-- \nname", false))
    }

    @Test
    fun stuffedLineLosesOneSpace() {
        assertEquals("From here\n  two", unflow(" From here\n   two", false))
    }

    @Test
    fun crlfAndLfGiveTheSameResult() {
        assertEquals(unflow("a \nb\n\nc\n", false), unflow("a \r\nb\r\n\r\nc\r\n", false))
        assertEquals("a b\n\nc\n", unflow("a \nb\n\nc\n", false))
    }

    @Test
    fun flowedLineBeforeBlankLineKeepsTheBlank() {
        assertEquals("a\n\nb", unflow("a \n\nb", false))
    }
}
