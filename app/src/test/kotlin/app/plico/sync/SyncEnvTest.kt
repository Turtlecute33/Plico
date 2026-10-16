package app.plico.sync

import app.plico.mail.imap.Command
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapConnection
import app.plico.mail.net.MailStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SyncEnvTest {
    private class Canned(lines: List<String>) : MailStream {
        override val input = ByteArrayInputStream(lines.joinToString("") { "$it\r\n" }.toByteArray())
        override val output = ByteArrayOutputStream()
        override var readTimeoutMs = 0
        override fun startCompression() = error("Not used")
        override fun close() {}
    }

    @Test
    fun selectionTracksTheLiveCountAndReportsOnlyNewExists() {
        val stream = Canned(
            listOf(
                "* OK [CAPABILITY IMAP4rev1] ready",
                "* 5 EXISTS",
                "* OK [UIDVALIDITY 3] ok",
                "* OK [UIDNEXT 9] ok",
                "A1 OK [READ-WRITE] done",
                "* 2 EXPUNGE",
                "* 6 EXISTS",
                "A2 OK done",
                "* 4 EXISTS",
                "A3 OK done",
            ),
        )
        val client = ImapClient(ImapConnection(stream)).also { it.conn.greeting() }
        var newMail = 0
        val selection = SyncSelection(client) { newMail++ }

        val first = selection.select("INBOX")
        assertEquals(5, first.exists)
        assertEquals(0, newMail, "EXISTS of the SELECT is not new mail")
        assertSame(first, selection.ensure("INBOX"), "no second SELECT")

        client.noop()
        assertEquals(6, selection.current("INBOX")!!.exists)
        assertEquals(1, newMail, "4 after the EXPUNGE, then 6")

        client.conn.execute(Command.raw("NOOP"))
        assertEquals(4, selection.current("INBOX")!!.exists)
        assertEquals(1, newMail, "a lower count is not new mail")
        assertNull(selection.current("Sent"))
        assertEquals("INBOX", selection.selected)
    }
}
