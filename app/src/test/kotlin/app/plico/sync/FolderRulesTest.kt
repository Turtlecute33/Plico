package app.plico.sync

import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewFolder
import app.plico.mail.imap.MailboxInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderRulesTest {
    private fun box(name: String, vararg attributes: String, delimiter: String = ".") =
        MailboxInfo(name, delimiter, setOf("\\HasNoChildren", *attributes))

    private fun List<NewFolder>.roles() = associate { it.path to it.role }

    @Test
    fun riseupRolesFromSpecialUseAndInboxByName() {
        val rows = FolderRules.riseup(
            7,
            listOf(
                box("INBOX"), box("Sent", "\\Sent"), box("Drafts", "\\Drafts"), box("Trash", "\\Trash"),
                box("Spam", "\\Junk"), box("riseup.Lists"), box("Old", "\\Noselect"),
            ),
        )
        assertEquals(
            mapOf(
                "INBOX" to FolderRole.INBOX, "Sent" to FolderRole.SENT, "Drafts" to FolderRole.DRAFTS,
                "Trash" to FolderRole.TRASH, "Spam" to FolderRole.JUNK, "riseup.Lists" to null,
            ),
            rows.roles(),
            "\\Noselect is not stored",
        )
        assertTrue(rows.all { it.kind == FolderKind.MAILBOX && it.accountId == 7L && !it.hidden })
        assertEquals("Inbox", rows.first { it.path == "INBOX" }.name)
        assertEquals("Lists", rows.first { it.path == "riseup.Lists" }.name)
    }

    @Test
    fun riseupArchiveOrder() {
        val named = listOf(box("INBOX"), box("archives"), box("Projects.Archive"))
        assertEquals(FolderRole.ARCHIVE, FolderRules.riseup(1, named).roles()["archives"], "by name, top level only")
        assertNull(FolderRules.riseup(1, named).roles()["Projects.Archive"])

        val flagged = listOf(box("INBOX"), box("Archive"), box("Kept", "\\Archive"))
        assertEquals(FolderRole.ARCHIVE, FolderRules.riseup(1, flagged).roles()["Kept"], "\\Archive before the name")
        assertNull(FolderRules.riseup(1, flagged).roles()["Archive"])

        val chosen = FolderRules.riseup(1, flagged, archivePath = "Archive").roles()
        assertEquals(FolderRole.ARCHIVE, chosen["Archive"], "the user's choice first")
        assertNull(chosen["Kept"], "one mailbox per role")

        assertEquals(FolderRole.JUNK, FolderRules.riseup(1, listOf(box("Spam"))).roles()["Spam"], "Riseup's fixed name")
        assertNull(FolderRules.riseup(1, listOf(box("spam"))).roles()["spam"], "the name is case-sensitive")
    }

    @Test
    fun gmailMailboxesAndLabelViews() {
        val gmail = FolderRules.gmail(
            3,
            listOf(
                box("INBOX", delimiter = "/"),
                box("[Gmail]", "\\Noselect", delimiter = "/"),
                box("[Gmail]/All Mail", "\\All", delimiter = "/"),
                box("[Gmail]/Drafts", "\\Drafts", delimiter = "/"),
                box("[Gmail]/Important", "\\Important", delimiter = "/"),
                box("[Gmail]/Sent Mail", "\\Sent", delimiter = "/"),
                box("[Gmail]/Spam", "\\Junk", delimiter = "/"),
                box("[Gmail]/Starred", "\\Flagged", delimiter = "/"),
                box("[Gmail]/Bin", "\\Trash", delimiter = "/"),
                box("Work/Projects", delimiter = "/"),
                box("Receipts", delimiter = "/"),
            ),
        )
        assertEquals(emptySet(), gmail.missing)
        val mailboxes = gmail.folders.filter { it.kind == FolderKind.MAILBOX }
        assertEquals(
            mapOf(
                "[Gmail]/All Mail" to FolderRole.ALL, "[Gmail]/Drafts" to FolderRole.DRAFTS,
                "[Gmail]/Spam" to FolderRole.JUNK, "[Gmail]/Bin" to FolderRole.TRASH,
            ),
            mailboxes.roles(),
            "found by SPECIAL-USE, never by name",
        )
        assertEquals(listOf("[Gmail]/Drafts"), mailboxes.filter { it.hidden }.map { it.path })
        val views = gmail.folders.filter { it.kind == FolderKind.LABEL }
        assertEquals(
            mapOf(
                "Work/Projects" to null, "Receipts" to null, "\\Inbox" to FolderRole.INBOX,
                "\\Sent" to FolderRole.SENT, "\\Draft" to FolderRole.DRAFTS, "\\Important" to FolderRole.IMPORTANT,
            ),
            views.roles(),
            "no view for INBOX or other SPECIAL-USE mailboxes",
        )
        assertEquals("Projects", views.first { it.path == "Work/Projects" }.name)
        assertTrue(views.none { it.hidden })
    }

    @Test
    fun gmailMissingMailboxes() {
        val gmail = FolderRules.gmail(3, listOf(box("INBOX"), box("[Gmail]/Bin", "\\Trash")))
        assertEquals(setOf(FolderRole.ALL, FolderRole.JUNK), gmail.missing)
    }

    @Test
    fun labelViewPaths() {
        assertEquals("\\Inbox", FolderRules.viewPath("\\Inbox"))
        assertEquals("\\Draft", FolderRules.viewPath("\\Drafts"))
        assertEquals("\\Draft", FolderRules.viewPath("\\Draft"))
        assertEquals("\\Important", FolderRules.viewPath("\\important"))
        assertNull(FolderRules.viewPath("\\Starred"), "starred is the flagged state")
        assertEquals("Work/Projects", FolderRules.viewPath("Work/Projects"))
        assertNull(FolderRules.viewPath(""))
    }

    @Test
    fun createdNames() {
        assertEquals("Archive", FolderRules.createName(FolderRole.ARCHIVE))
        assertEquals("Spam", FolderRules.createName(FolderRole.JUNK))
        assertEquals("Trash", FolderRules.createName(FolderRole.TRASH))
        assertNull(FolderRules.createName(FolderRole.INBOX))
        assertNull(FolderRules.createName(FolderRole.ALL))
    }

    @Test
    fun leafNames() {
        assertEquals("c", FolderRules.leaf("a.b.c", "."))
        assertEquals("a.b", FolderRules.leaf("a.b", null))
        assertEquals("x.", FolderRules.leaf("x.", "."))
    }
}
