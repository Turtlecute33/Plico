package app.plico.account

import app.plico.R
import app.plico.data.FolderRole
import app.plico.mail.imap.MailboxInfo
import app.plico.sync.FolderRules
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountProbeTest {
    @Test
    fun `every Gmail mailbox that sync needs stops setup or adds a note`() {
        // A hidden All Mail stops setup; every other required mailbox has a note.
        assertEquals(FolderRules.GMAIL_REQUIRED - FolderRole.ALL, AccountProbe.MISSING_NOTES.keys)
    }

    @Test
    fun `Gmail probe finds hidden mailboxes as sync does`() {
        val all = MailboxInfo("[Gmail]/All Mail", "/", setOf("\\HasNoChildren", "\\All"))
        val trash = MailboxInfo("[Gmail]/Bin", "/", setOf("\\HasNoChildren", "\\Trash"))
        val junk = MailboxInfo("[Gmail]/Spam", "/", setOf("\\HasNoChildren", "\\junk"))
        val inbox = MailboxInfo("INBOX", "/", setOf("\\HasNoChildren"))
        fun notes(vararg list: MailboxInfo) =
            FolderRules.gmail(0, list.toList()).missing.mapNotNull { AccountProbe.MISSING_NOTES[it] }

        assertEquals(emptyList(), notes(inbox, all, trash, junk))
        assertEquals(listOf(R.string.account_note_gmail_trash_hidden), notes(inbox, all, junk))
        assertEquals(
            listOf(R.string.account_note_gmail_trash_hidden, R.string.account_note_gmail_spam_hidden),
            notes(inbox, all),
        )
        // A \Noselect All Mail cannot be synced, so the probe treats it as hidden too.
        val noselect = all.copy(attributes = all.attributes + "\\Noselect")
        assertEquals(setOf(FolderRole.ALL), FolderRules.gmail(0, listOf(inbox, noselect, trash, junk)).missing)
    }
}
