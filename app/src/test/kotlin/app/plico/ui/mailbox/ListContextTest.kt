package app.plico.ui.mailbox

import app.plico.R
import app.plico.account.MailError
import app.plico.data.AccountAbilities
import app.plico.data.Auth
import app.plico.data.FolderRole
import app.plico.data.Provider
import app.plico.ui.components.ErrorText
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListContextTest {
    @Test
    fun rowsKnowTheirPlaceAndAccount() {
        val riseup = account(1, archiveFolder = 3)
        val gmail = account(2, Provider.GMAIL)
        val folders = listOf(
            folder(1, 1, "INBOX", FolderRole.INBOX),
            folder(2, 1, "Trash", FolderRole.TRASH),
            folder(3, 1, "Old mail"),
            folder(10, 2, "[Gmail]/All Mail", FolderRole.ALL),
        )
        val context = ListContext(listOf(riseup, gmail), folders, inboxView = true, emptyMap())
        assertEquals(Place.NORMAL, context.info(summary(1, 1)).place)
        assertEquals(Place.TRASH, context.info(summary(2, 2)).place)
        assertEquals(Place.ARCHIVE, context.info(summary(3, 3)).place)

        val gmailRow = context.info(summary(4, 10, accountId = 2, seen = false, flagged = true))
        assertTrue(gmailRow.gmail)
        assertFalse(gmailRow.seen)
        assertTrue(gmailRow.flagged)
        // Gmail shows no Trash or Spam to IMAP here: Delete and Spam are off.
        assertEquals(AccountAbilities(canDelete = false, canSpam = false), gmailRow.abilities)
        assertEquals(AccountAbilities(), context.info(summary(1, 1)).abilities)

        // Outside the inbox views Gmail rows cannot archive; Riseup rows can (archive is a move there).
        val elsewhere = ListContext(listOf(riseup, gmail), folders, inboxView = false, emptyMap())
        assertFalse(elsewhere.info(summary(4, 10, accountId = 2)).archivable)
        assertTrue(elsewhere.info(summary(1, 1)).archivable)
    }

    @Test
    fun logoOnlyForReceivedMailOutsideSpam() {
        val riseup = account(1)
        val folders = listOf(
            folder(1, 1, "INBOX", FolderRole.INBOX),
            folder(2, 1, "Spam", FolderRole.JUNK),
            folder(3, 1, "Sent", FolderRole.SENT),
            folder(4, 1, "Drafts", FolderRole.DRAFTS),
            folder(5, 1, "Trash", FolderRole.TRASH),
        )
        val context = ListContext(listOf(riseup), folders, inboxView = true, mapOf(1L to emptyList()))
        val own = riseup.email
        // Received mail in INBOX (and Trash) may show the sender's logo.
        assertTrue(context.info(summary(1, 1, fromEmail = "news@example.com")).logoAllowed)
        assertTrue(context.info(summary(2, 5, fromEmail = "news@example.com")).logoAllowed)
        // Never in Spam.
        assertFalse(context.info(summary(3, 2, fromEmail = "news@example.com")).logoAllowed)
        // Never for mail the user sent: no recipient domain is ever sent.
        assertFalse(context.info(summary(4, 3, fromEmail = own, toList = "x@example.com")).logoAllowed)
        // A forged own From, in Spam and in INBOX.
        assertFalse(context.info(summary(5, 2, fromEmail = own)).logoAllowed)
        assertFalse(context.info(summary(6, 1, fromEmail = own.uppercase())).logoAllowed)
        // Drafts: in the Drafts folder, and with the draft flag elsewhere.
        assertFalse(context.info(summary(7, 4, fromEmail = "news@example.com")).logoAllowed)
        assertFalse(context.info(summary(8, 1, draft = true, fromEmail = "news@example.com")).logoAllowed)
    }

    @Test
    fun gmailLogoNeedsADmarcPass() {
        val gmail = account(2, Provider.GMAIL)
        val folders = listOf(folder(10, 2, "[Gmail]/All Mail", FolderRole.ALL), folder(11, 2, "[Gmail]/Spam", FolderRole.JUNK))
        val context = ListContext(listOf(gmail), folders, inboxView = true, mapOf(2L to emptyList()))
        assertTrue(context.info(summary(1, 10, accountId = 2, auth = Auth.PASS)).logoAllowed)
        assertFalse(context.info(summary(2, 10, accountId = 2, auth = Auth.UNKNOWN)).logoAllowed)
        assertFalse(context.info(summary(3, 10, accountId = 2, auth = Auth.FAIL)).logoAllowed)
        assertFalse(context.info(summary(4, 11, accountId = 2, auth = Auth.PASS)).logoAllowed)
    }

    @Test
    fun noLogoForARowWithoutItsAccount() {
        val context = ListContext(emptyList(), listOf(folder(1, 9, "INBOX", FolderRole.INBOX)), inboxView = true, emptyMap())
        assertFalse(context.info(summary(1, 1, accountId = 9)).logoAllowed)
    }

    @Test
    fun noLogoForAnIdentityOfTheUser() {
        val first = account(1)
        val second = account(2)
        val folders = listOf(folder(1, 1, "INBOX", FolderRole.INBOX), folder(2, 2, "INBOX", FolderRole.INBOX))
        val identities = mapOf(1L to listOf("Alias@Own.example"), 2L to emptyList())
        val context = ListContext(listOf(first, second), folders, inboxView = true, identities)
        assertTrue(context.info(summary(1, 1, fromEmail = "news@example.com")).logoAllowed)
        // A copy to self from an identity, or spam in INBOX with a forged identity From: never the user's domain.
        assertFalse(context.info(summary(2, 1, fromEmail = "alias@own.example")).logoAllowed)
        assertFalse(context.info(summary(3, 1, fromEmail = "ALIAS@OWN.EXAMPLE")).logoAllowed)
        // Mail from another account of the user, or from an identity of it.
        assertFalse(context.info(summary(4, 2, accountId = 2, fromEmail = first.email)).logoAllowed)
        assertFalse(context.info(summary(5, 2, accountId = 2, fromEmail = "alias@own.example")).logoAllowed)
        // The identities of an account are not read yet: no logo for its rows.
        val unread = ListContext(listOf(first, second), folders, inboxView = true, mapOf(2L to emptyList()))
        assertFalse(unread.info(summary(6, 1, fromEmail = "news@example.com")).logoAllowed)
        assertTrue(unread.info(summary(7, 2, accountId = 2, fromEmail = "news@example.com")).logoAllowed)
    }

    @Test
    fun errorsBecomeUserText() {
        assertEquals(ErrorText(R.string.mailbox_error_sign_in), actionError(MailError.AuthRequired("x")))
        assertEquals(ErrorText(R.string.mailbox_error_network), actionError(MailError.Network(IOException())))
        assertEquals(ErrorText(R.string.mailbox_error_network), actionError(IOException("reset")))
        assertEquals(ErrorText(R.string.ui_error_tls), actionError(MailError.Tls(IOException())))
        assertEquals(ErrorText(R.string.ui_error_busy), actionError(MailError.Throttled("slow down")))
        assertEquals(ErrorText(R.string.mailbox_error_server, "No such folder"), actionError(MailError.Server("No such folder")))
        assertEquals(ErrorText(R.string.mailbox_error_detail, "Turn on IMAP"), actionError(MailError.Setup("Turn on IMAP")))
        assertEquals(ErrorText(R.string.mailbox_error_failed), actionError(IllegalStateException("bug")))
    }
}
