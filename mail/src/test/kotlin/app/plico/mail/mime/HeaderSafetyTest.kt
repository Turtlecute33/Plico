package app.plico.mail.mime

import org.junit.Test
import kotlin.test.assertEquals

class HeaderSafetyTest {
    @Test
    fun cleanReplacesControlsAndCollapses() {
        assertEquals("Hello Bcc: victim@example.com", HeaderSafety.clean("Hello\r\nBcc: victim@example.com"))
        assertEquals("a b c d", HeaderSafety.clean("a\tb\u0000c\u007Fd"))
        assertEquals("a b", HeaderSafety.clean("  a \r\n\t  b  "))
        assertEquals("x", HeaderSafety.clean("\u0001\u001Fx\u001B"))
        assertEquals("", HeaderSafety.clean(""))
        assertEquals("", HeaderSafety.clean(" \r\n\t\u0000 "))
    }

    @Test
    fun cleanKeepsPrintableAndUnicode() {
        assertEquals("Jörg \"JM\" Müller <j@x.example>", HeaderSafety.clean("Jörg \"JM\" Müller <j@x.example>"))
        assertEquals("日本語 🎉", HeaderSafety.clean("日本語 🎉"))
        assertEquals("a b", HeaderSafety.clean("a b"))
    }

    @Test
    fun cleanNeutralisesDecodedEncodedWords() {
        val decoded = EncodedWords.decode("=?UTF-8?Q?Invoice=0D=0AContent-Type:_text/html=0D=0A=0D=0A<b>x</b>?=")
        assertEquals("Invoice Content-Type: text/html <b>x</b>", HeaderSafety.clean(decoded))
    }

    @Test
    fun revealHiddenReplacesEachHiddenCharacter() {
        val bmp = "\u0000\u001B\u0085\u00AD\u034F\u17B4\u17B5\u1806\u180B\u180E\u200B\u200D\u200E\u202E\u2060\u2065" +
            "\u2066\uFE0F\uFEFF\uFFF0\uFFF8"
        for (c in bmp) {
            assertEquals("a\uFFFDb", HeaderSafety.revealHidden("a${c}b"))
        }
        // Plane 14 (tag characters, variation selectors, unassigned): one U+FFFD for each code point.
        assertEquals("a\uFFFD\uFFFDb", HeaderSafety.revealHidden("a\uDB40\uDC61\uDB40\uDD00b"))
        for (c in listOf("\uDB40\uDC00", "\uDB40\uDC80", "\uDB40\uDDF0", "\uDB43\uDFFF")) {
            assertEquals("a\uFFFDb", HeaderSafety.revealHidden("a${c}b"))
        }
        assertEquals("jörg@exämple.de", HeaderSafety.revealHidden("jörg@exämple.de"))
    }

    @Test
    fun messageIds() {
        assertEquals(
            listOf("<CAFx3=first@mail.gmail.com>", "<CAFx3=prev@mail.gmail.com>"),
            HeaderSafety.messageIds("<CAFx3=first@mail.gmail.com>\r\n <CAFx3=prev@mail.gmail.com>"),
        )
        assertEquals(listOf("<a@b>"), HeaderSafety.messageIds("<a@b>"))
    }

    @Test
    fun messageIdsDropsJunkAndDuplicates() {
        assertEquals(
            listOf("<1@x.example>", "<2@x.example>"),
            HeaderSafety.messageIds("junk <1@x.example> (comment) <no-at> <2@x.example>,<1@x.example> <bad id@x> <>"),
        )
        assertEquals(emptyList(), HeaderSafety.messageIds(""))
        assertEquals(emptyList(), HeaderSafety.messageIds("<a@b\r\nBcc: v@x.example>"))
    }

    @Test
    fun messageIdsOutlookAndAppleForms() {
        assertEquals(
            listOf("<AM0PR01MB1234ABCD5678EF90@AM0PR01MB1234.eurprd01.prod.outlook.com>"),
            HeaderSafety.messageIds("<AM0PR01MB1234ABCD5678EF90@AM0PR01MB1234.eurprd01.prod.outlook.com>"),
        )
        assertEquals(
            listOf("<6F1C2B3A-4D5E-4F60-8A9B-0C1D2E3F4A5B@icloud.com>"),
            HeaderSafety.messageIds("<6F1C2B3A-4D5E-4F60-8A9B-0C1D2E3F4A5B@icloud.com>"),
        )
        // Nested brackets: only the inner valid token survives.
        assertEquals(listOf("<a@b.example>"), HeaderSafety.messageIds("<<a@b.example>>"))
    }

    @Test(timeout = 5_000)
    fun hostileInputStaysFast() {
        assertEquals(emptyList(), HeaderSafety.messageIds("<" + "@".repeat(500_000)))
        assertEquals(emptyList(), HeaderSafety.messageIds("<a" + "@a".repeat(250_000) + " "))
        assertEquals(1, HeaderSafety.messageIds("<x@y> " + "<".repeat(500_000)).size)
        assertEquals("a b", HeaderSafety.clean("a" + "\r\n".repeat(500_000) + "b"))
    }
}
