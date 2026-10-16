package app.plico.ui.mailbox

import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.Provider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailboxFoldersTest {
    private val riseup = account(1)
    private val gmail = account(2, Provider.GMAIL)

    private val riseupFolders = listOf(
        folder(10, 1, "INBOX", FolderRole.INBOX),
        folder(11, 1, "Sent", FolderRole.SENT),
        folder(12, 1, "Drafts", FolderRole.DRAFTS),
        folder(13, 1, "Trash", FolderRole.TRASH),
        folder(14, 1, "Spam", FolderRole.JUNK),
        folder(15, 1, "Archives"),
        folder(16, 1, "lists"),
        folder(17, 1, "Bills"),
        folder(18, 1, "Old", hidden = true),
    )

    private val gmailFolders = listOf(
        folder(20, 2, "[Gmail]/All Mail", FolderRole.ALL),
        folder(21, 2, "[Gmail]/Trash", FolderRole.TRASH),
        folder(22, 2, "[Gmail]/Spam", FolderRole.JUNK),
        folder(23, 2, "[Gmail]/Drafts", FolderRole.DRAFTS, hidden = true),
        folder(24, 2, "\\Inbox", FolderRole.INBOX, FolderKind.LABEL),
        folder(25, 2, "\\Sent", FolderRole.SENT, FolderKind.LABEL),
        folder(26, 2, "\\Draft", FolderRole.DRAFTS, FolderKind.LABEL),
        folder(27, 2, "\\Drafts", FolderRole.DRAFTS, FolderKind.LABEL),
        folder(28, 2, "\\Important", FolderRole.IMPORTANT, FolderKind.LABEL),
        folder(29, 2, "Work/Clients", kind = FolderKind.LABEL),
        folder(30, 2, "family", kind = FolderKind.LABEL),
        folder(31, 2, "Unlisted", kind = FolderKind.LABEL, hidden = true),
    )

    @Test
    fun riseupMainOrderWithArchiveByName() {
        val main = MailboxFolders.mainFolders(riseup, riseupFolders)
        assertEquals(listOf(10L, 11L, 12L, 15L, 14L, 13L), main.map { it.second.id })
        assertEquals(FolderRole.ARCHIVE, main[3].first)
    }

    @Test
    fun gmailMainOrderPrefersLabelViewsAndSkipsHidden() {
        val main = MailboxFolders.mainFolders(gmail, gmailFolders)
        // Inbox, Sent, Drafts (oldest label view), All mail, Important, Spam, Trash.
        assertEquals(listOf(24L, 25L, 26L, 20L, 28L, 22L, 21L), main.map { it.second.id })
    }

    @Test
    fun missingFixedFoldersAreLeftOut() {
        val main = MailboxFolders.mainFolders(gmail, gmailFolders.filter { it.role != FolderRole.TRASH })
        assertTrue(main.none { it.first == FolderRole.TRASH })
    }

    @Test
    fun archiveChoice() {
        val withRole = riseupFolders + folder(40, 1, "Archive", FolderRole.ARCHIVE)
        assertEquals(40L, MailboxFolders.archiveOf(riseup, withRole)?.id)
        // The user's choice wins over the \Archive mailbox.
        assertEquals(17L, MailboxFolders.archiveOf(account(1, archiveFolder = 17), withRole)?.id)
        // A chosen folder that is gone falls back to the rules.
        assertEquals(40L, MailboxFolders.archiveOf(account(1, archiveFolder = 99), withRole)?.id)
        // Only a top-level name matches.
        assertNull(MailboxFolders.archiveOf(riseup, listOf(folder(41, 1, "Work/Archive"))))
        assertNull(MailboxFolders.archiveOf(gmail, gmailFolders))
    }

    @Test
    fun moreHoldsOtherVisibleFoldersByName() {
        val section = MailboxFolders.sections(listOf(riseup), riseupFolders, mapOf(16L to 3, 10L to 2)).single()
        assertEquals(listOf(17L, 16L), section.more.map { it.folder.id })
        assertEquals(3, section.more.single { it.folder.id == 16L }.unread)
        assertEquals(2, section.main.first().unread)
    }

    @Test
    fun anUnchosenArchiveMailboxStaysReachable() {
        val folders = riseupFolders + folder(40, 1, "Archive", FolderRole.ARCHIVE)
        val section = MailboxFolders.sections(listOf(account(1, archiveFolder = 17)), folders, emptyMap()).single()
        assertTrue(section.more.any { it.folder.id == 40L })
        assertTrue(section.more.none { it.folder.id == 17L })
    }

    @Test
    fun gmailMoreHasLabelsButNoDuplicateRoles() {
        val section = MailboxFolders.sections(listOf(gmail), gmailFolders, emptyMap()).single()
        assertEquals(listOf(30L, 29L), section.more.map { it.folder.id })
    }

    @Test
    fun sectionsFollowAccountOrderAndOwnFolders() {
        val sections = MailboxFolders.sections(listOf(gmail, riseup), riseupFolders + gmailFolders, emptyMap())
        assertEquals(listOf(2L, 1L), sections.map { it.account.id })
        assertTrue(sections[0].main.all { it.folder.accountId == 2L })
    }

    @Test
    fun notifyToggleOnlyWhereItHasAMeaning() {
        assertFalse(MailboxFolders.canNotify(riseupFolders[0], gmail = false)) // INBOX always notifies
        assertFalse(MailboxFolders.canNotify(riseupFolders[3], gmail = false)) // Trash
        assertTrue(MailboxFolders.canNotify(riseupFolders[6], gmail = false))
        assertTrue(MailboxFolders.canNotify(gmailFolders[8], gmail = true)) // Important label
        assertTrue(MailboxFolders.canNotify(gmailFolders[9], gmail = true))
        assertFalse(MailboxFolders.canNotify(gmailFolders[0], gmail = true)) // All Mail is a mailbox
    }

    @Test
    fun riseupMoveTargets() {
        val targets = MailboxFolders.moveTargets(riseup, riseupFolders, exclude = setOf(10))
        // No Drafts, no hidden folder, not the open INBOX; roles first, then names.
        assertEquals(listOf(11L, 14L, 13L, 15L, 17L, 16L), targets.map { it.id })
    }

    @Test
    fun gmailMoveAndLabelTargets() {
        assertEquals(listOf(24L, 30L, 29L), MailboxFolders.moveTargets(gmail, gmailFolders).map { it.id })
        assertEquals(listOf(30L, 29L), MailboxFolders.moveTargets(gmail, gmailFolders, setOf(24)).map { it.id })
        assertEquals(listOf(24L, 30L, 29L), MailboxFolders.labelTargets(gmail, gmailFolders).map { it.id })
        assertTrue(MailboxFolders.labelTargets(riseup, riseupFolders).isEmpty())
    }

    @Test
    fun displayPathShowsTheHierarchy() {
        assertEquals("Work / Clients", MailboxFolders.displayPath(gmailFolders[9]))
        assertEquals("INBOX / lists", MailboxFolders.displayPath(folder(50, 1, "INBOX.lists", delimiter = ".")))
        assertEquals("a/b", MailboxFolders.displayPath(folder(51, 1, "a/b", delimiter = null)))
    }
}
