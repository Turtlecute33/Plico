package app.plico.ui.mailbox

import app.plico.R
import app.plico.account.MailError
import app.plico.data.FolderRole
import app.plico.data.Provider
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
        val context = ListContext(listOf(riseup, gmail), folders)
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
    }

    @Test
    fun errorsBecomeUserText() {
        assertEquals(ErrorText(R.string.mailbox_error_sign_in), ErrorText.of(MailError.AuthRequired("x")))
        assertEquals(ErrorText(R.string.mailbox_error_network), ErrorText.of(MailError.Network(IOException())))
        assertEquals(ErrorText(R.string.mailbox_error_network), ErrorText.of(IOException("reset")))
        assertEquals(ErrorText(R.string.mailbox_error_busy), ErrorText.of(MailError.Throttled("slow down")))
        assertEquals(ErrorText(R.string.mailbox_error_server, "No such folder"), ErrorText.of(MailError.Server("No such folder")))
        assertEquals(ErrorText(R.string.mailbox_error_detail, "Turn on IMAP"), ErrorText.of(MailError.Setup("Turn on IMAP")))
        assertEquals(ErrorText(R.string.mailbox_error_failed), ErrorText.of(IllegalStateException("bug")))
    }
}
