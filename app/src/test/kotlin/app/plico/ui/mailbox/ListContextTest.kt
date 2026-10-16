package app.plico.ui.mailbox

import app.plico.R
import app.plico.account.MailError
import app.plico.data.AccountAbilities
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
        val context = ListContext(listOf(riseup, gmail), folders, inboxView = true)
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
        val elsewhere = ListContext(listOf(riseup, gmail), folders, inboxView = false)
        assertFalse(elsewhere.info(summary(4, 10, accountId = 2)).archivable)
        assertTrue(elsewhere.info(summary(1, 1)).archivable)
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
