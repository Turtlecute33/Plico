package app.plico.account

import app.plico.mail.imap.MailboxInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountProbeTest {
    @Test
    fun `Gmail probe finds hidden special-use mailboxes`() {
        val all = MailboxInfo("[Gmail]/All Mail", "/", setOf("\\HasNoChildren", "\\All"))
        val trash = MailboxInfo("[Gmail]/Bin", "/", setOf("\\HasNoChildren", "\\Trash"))
        val junk = MailboxInfo("[Gmail]/Spam", "/", setOf("\\HasNoChildren", "\\junk"))
        val inbox = MailboxInfo("INBOX", "/", setOf("\\HasNoChildren"))
        assertEquals(emptySet(), AccountProbe.missingGmailMailboxes(listOf(inbox, all, trash, junk)))
        assertEquals(setOf(AccountProbe.TRASH), AccountProbe.missingGmailMailboxes(listOf(inbox, all, junk)))
        assertEquals(
            setOf(AccountProbe.ALL, AccountProbe.TRASH, AccountProbe.JUNK),
            AccountProbe.missingGmailMailboxes(listOf(inbox)),
        )
    }
}
