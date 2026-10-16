package app.plico.mail.imap

import kotlin.test.Test
import kotlin.test.assertEquals

class MailboxNameTest {

    private fun roundTrip(decoded: String, wire: String) {
        assertEquals(wire, MailboxName.encode(decoded))
        assertEquals(decoded, MailboxName.decode(wire))
    }

    @Test
    fun asciiIsUnchanged() {
        roundTrip("INBOX", "INBOX")
        roundTrip("[Gmail]/Posta inviata", "[Gmail]/Posta inviata")
        roundTrip("riseup.Lists", "riseup.Lists")
        roundTrip("", "")
    }

    @Test
    fun ampersand() {
        roundTrip("&", "&-")
        roundTrip("Tom & Jerry", "Tom &- Jerry")
        roundTrip("&&", "&-&-")
    }

    @Test
    fun nonAscii() {
        roundTrip("Entwürfe", "Entw&APw-rfe")
        roundTrip("Café", "Caf&AOk-")
        roundTrip("[Gmail]/Corbeille/Été", "[Gmail]/Corbeille/&AMk-t&AOk-")
        // RFC 3501 §5.1.3 example.
        roundTrip("~peter/mail/台北/日本語", "~peter/mail/&U,BTFw-/&ZeVnLIqe-")
        roundTrip("日本語", "&ZeVnLIqe-")
    }

    @Test
    fun surrogatePairsAndControls() {
        roundTrip("Fun 😀", "Fun &2D3eAA-")
        roundTrip("a\u0001b", "a&AAE-b")
        roundTrip("\u007f", "&AH8-")
    }

    @Test
    fun manyRoundTrips() {
        val samples = listOf("Привет/Входящие", "Ελληνικά & more", "中文,目录/子", "ü&ü", "  spaced  ", "a/b.c-d_e~f")
        for (s in samples) assertEquals(s, MailboxName.decode(MailboxName.encode(s)))
    }

    @Test
    fun decodeIsLenient() {
        assertEquals("&", MailboxName.decode("&"))
        assertEquals("a&b", MailboxName.decode("a&b"))
        assertEquals("Caf&AOk", MailboxName.decode("Caf&AOk"))
        assertEquals("&Jjo!-", MailboxName.decode("&Jjo!-"))
        // One byte is not UTF-16.
        assertEquals("&AA-", MailboxName.decode("&AA-"))
        // An unpaired high surrogate.
        assertEquals("x&2D0-y", MailboxName.decode("x&2D0-y"))
        assertEquals("&Jjo!- Café", MailboxName.decode("&Jjo!- Caf&AOk-"))
    }
}
