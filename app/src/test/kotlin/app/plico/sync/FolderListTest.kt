package app.plico.sync

import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapConnection
import app.plico.mail.imap.MailboxStatus
import app.plico.mail.net.MailStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderListTest {
    private class Canned(lines: List<String>) : MailStream {
        override val input = ByteArrayInputStream(lines.joinToString("") { "$it\r\n" }.toByteArray())
        override val output = ByteArrayOutputStream()
        override var readTimeoutMs = 0
        override fun startCompression() = error("Not used")
        override fun close() {}
        fun written() = output.toString(Charsets.UTF_8).trimEnd()
    }

    @Test
    fun listStatusReadsEveryMailboxInOneRoundTrip() {
        val stream = Canned(
            listOf(
                "* OK [CAPABILITY IMAP4rev1 LIST-STATUS CONDSTORE] ready",
                "* LIST (\\HasNoChildren) \".\" INBOX",
                "* STATUS INBOX (MESSAGES 12 UIDNEXT 40 UIDVALIDITY 3 HIGHESTMODSEQ 99)",
                "* LIST (\\HasNoChildren \\Junk) \".\" Spam",
                "* STATUS Spam (MESSAGES 0 UIDNEXT 1 UIDVALIDITY 5 HIGHESTMODSEQ 0)",
                "* LIST (\\HasNoChildren) \".\" \"Gr&APw-n\"",
                "* STATUS \"Gr&APw-n\" (MESSAGES 1 UIDNEXT 2 UIDVALIDITY 6 HIGHESTMODSEQ 4)",
                "* STATUS Broken (UIDNEXT 2)",
                "A1 OK List completed",
            ),
        )
        val client = ImapClient(ImapConnection(stream)).also { it.conn.greeting() }
        val statuses = FolderList.listStatus(client)
        assertEquals(
            """A1 LIST "" "*" RETURN (STATUS (MESSAGES UIDNEXT UIDVALIDITY HIGHESTMODSEQ))""",
            stream.written(),
        )
        assertEquals(
            mapOf(
                "INBOX" to MailboxStatus(12, 40, 3, 99),
                "Spam" to MailboxStatus(0, 1, 5, null),
                "Grün" to MailboxStatus(1, 2, 6, 4),
            ),
            statuses,
            "names decoded from modified UTF-7; incomplete entries left out",
        )
    }
}
