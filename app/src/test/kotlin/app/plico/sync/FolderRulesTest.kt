package app.plico.sync

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewFolder
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.mail.imap.MailboxInfo
import app.plico.mail.imap.Namespace
import app.plico.mail.imap.Namespaces
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
        val rows = FolderRules.mailboxes(
            7,
            listOf(
                box("INBOX"), box("Sent", "\\Sent"), box("Drafts", "\\Drafts"), box("Trash", "\\Trash"),
                box("Spam", "\\Junk"), box("riseup.Lists"), box("Old", "\\Noselect"),
            ),
            riseup = true,
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
        assertEquals(FolderRole.ARCHIVE, FolderRules.mailboxes(1, named, riseup = true).roles()["archives"], "by name, top level only")
        assertNull(FolderRules.mailboxes(1, named, riseup = true).roles()["Projects.Archive"])

        val flagged = listOf(box("INBOX"), box("Archive"), box("Kept", "\\Archive"))
        assertEquals(FolderRole.ARCHIVE, FolderRules.mailboxes(1, flagged, riseup = true).roles()["Kept"], "\\Archive before the name")
        assertNull(FolderRules.mailboxes(1, flagged, riseup = true).roles()["Archive"])

        val chosen = FolderRules.mailboxes(1, flagged, archivePath = "Archive", riseup = true).roles()
        assertEquals(FolderRole.ARCHIVE, chosen["Archive"], "the user's choice first")
        assertNull(chosen["Kept"], "one mailbox per role")

        assertEquals(FolderRole.JUNK, FolderRules.mailboxes(1, listOf(box("Spam")), riseup = true).roles()["Spam"], "Riseup's fixed name")
        assertNull(FolderRules.mailboxes(1, listOf(box("spam")), riseup = true).roles()["spam"], "the name is case-sensitive")
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
    fun theArchiveIsTheChoiceThenTheRoleThenTheName() {
        val riseup = account(Provider.RISEUP)
        val named = folder(1, "archives")
        val role = folder(2, "Old mail", FolderRole.ARCHIVE)
        val chosen = folder(3, "Kept")
        val all = listOf(named, role, chosen, folder(4, "Archive", kind = FolderKind.LABEL), folder(5, "INBOX", FolderRole.INBOX))
        assertEquals(chosen, FolderRules.archiveOf(riseup.copy(archiveFolder = 3), all))
        assertEquals(role, FolderRules.archiveOf(riseup.copy(archiveFolder = 99), all), "a chosen folder that is gone")
        assertNull(FolderRules.archiveOf(riseup, listOf(folder(6, "Work/Archive"))), "only a top-level name")
        assertEquals(role, FolderRules.archiveOf(riseup, all))
        assertEquals(named, FolderRules.archiveOf(riseup, all - role))
        assertNull(FolderRules.archiveOf(riseup, listOf(named.copy(hidden = true))), "hidden folders do not count")
        assertNull(FolderRules.archiveOf(riseup, listOf(named.copy(accountId = 9))), "another account")
        assertNull(FolderRules.archiveOf(account(Provider.GMAIL), all), "Gmail archives with labels")
    }

    @Test
    fun otherServersArchiveIsTheChoiceThenTheRoleNeverABareName() {
        val other = account(Provider.OTHER)
        val named = folder(1, "Archive")
        val role = folder(2, "INBOX.Archiv", FolderRole.ARCHIVE)
        val chosen = folder(3, "Kept")
        assertEquals(chosen, FolderRules.archiveOf(other.copy(archiveFolder = 3), listOf(named, role, chosen)))
        assertEquals(role, FolderRules.archiveOf(other, listOf(named, role, chosen)))
        assertNull(FolderRules.archiveOf(other, listOf(named)), "names are decided at LIST time (role), not here")
        assertNull(FolderRules.archiveOf(other, listOf(role.copy(hidden = true))), "a hidden mailbox is no archive")
    }

    // ---- Servers other than Riseup and Gmail (DESIGN §7.2, plan §5.3)

    private fun generic(list: List<MailboxInfo>, ns: Namespaces? = null, archivePath: String? = null) =
        FolderRules.mailboxes(5, list, archivePath, ns, riseup = false)

    private fun personal(prefix: String, delimiter: String = ".") = Namespaces(listOf(Namespace(prefix, delimiter)), emptyList(), emptyList())

    @Test
    fun genericRolesByNameWithoutSpecialUse() {
        val rows = generic(
            listOf(
                box("INBOX", delimiter = "/"), box("Sent Items", delimiter = "/"), box("Drafts", delimiter = "/"),
                box("Deleted Items", delimiter = "/"), box("Junk Email", delimiter = "/"), box("Archive", delimiter = "/"),
                box("Projects", delimiter = "/"),
            ),
        )
        assertEquals(
            mapOf(
                "INBOX" to FolderRole.INBOX, "Sent Items" to FolderRole.SENT, "Drafts" to FolderRole.DRAFTS,
                "Deleted Items" to FolderRole.TRASH, "Junk Email" to FolderRole.JUNK, "Archive" to FolderRole.ARCHIVE,
                "Projects" to null,
            ),
            rows.roles(),
        )
        assertTrue(rows.none { it.hidden })
        assertEquals("Inbox", rows.first { it.path == "INBOX" }.name)
    }

    @Test
    fun localizedExchangeNames() {
        val czech = generic(
            listOf(box("INBOX"), box("Odeslaná pošta"), box("Koncepty"), box("Odstraněná pošta"), box("Nevyžádaná pošta")),
        ).roles()
        assertEquals(FolderRole.SENT, czech["Odeslaná pošta"])
        assertEquals(FolderRole.DRAFTS, czech["Koncepty"])
        assertEquals(FolderRole.TRASH, czech["Odstraněná pošta"])
        assertEquals(FolderRole.JUNK, czech["Nevyžádaná pošta"])
        val polish = generic(
            listOf(box("INBOX"), box("Elementy wysłane"), box("Wersje robocze"), box("Elementy usunięte"), box("Wiadomości-śmieci")),
        ).roles()
        assertEquals(FolderRole.SENT, polish["Elementy wysłane"])
        assertEquals(FolderRole.DRAFTS, polish["Wersje robocze"])
        assertEquals(FolderRole.TRASH, polish["Elementy usunięte"])
        assertEquals(FolderRole.JUNK, polish["Wiadomości-śmieci"])
        val italian = generic(listOf(box("INBOX"), box("Posta inviata"), box("Bozze"), box("Cestino"), box("Archivio"))).roles()
        assertEquals(FolderRole.SENT, italian["Posta inviata"])
        assertEquals(FolderRole.DRAFTS, italian["Bozze"])
        assertEquals(FolderRole.TRASH, italian["Cestino"])
        assertEquals(FolderRole.ARCHIVE, italian["Archivio"])
        assertEquals(FolderRole.SENT, generic(listOf(box("ÉLÉMENTS ENVOYÉS"))).roles()["ÉLÉMENTS ENVOYÉS"], "any case")
    }

    @Test
    fun tableOrderPicksTheFirstName() {
        val sent = generic(listOf(box("INBOX"), box("Sent Messages"), box("Sent"))).roles()
        assertEquals(FolderRole.SENT, sent["Sent"], "\"sent\" comes first in the table")
        assertNull(sent["Sent Messages"])
        val archive = generic(listOf(box("Archives"), box("Archive"))).roles()
        assertEquals(FolderRole.ARCHIVE, archive["Archive"])
        assertNull(archive["Archives"])
    }

    @Test
    fun twoTrashOrJunkNamesGiveNoRole() {
        val rows = generic(listOf(box("INBOX"), box("Trash"), box("Deleted"), box("Junk"), box("Spam"))).roles()
        assertNull(rows["Trash"], "a wrong Trash would be emptied")
        assertNull(rows["Deleted"])
        assertNull(rows["Junk"])
        assertNull(rows["Spam"])
        assertEquals(FolderRole.TRASH, generic(listOf(box("Trash"), box("Old Trash"))).roles()["Trash"], "one match")
    }

    @Test
    fun attributeBeatsName() {
        val rows = generic(listOf(box("INBOX"), box("Sent"), box("Gesendet", "\\Sent"), box("Trash"), box("Bin", "\\Trash"))).roles()
        assertEquals(FolderRole.SENT, rows["Gesendet"])
        assertNull(rows["Sent"])
        assertEquals(FolderRole.TRASH, rows["Bin"])
        assertNull(rows["Trash"])
        assertEquals(FolderRole.ARCHIVE, generic(listOf(box("Archive"), box("Old")), archivePath = "Old").roles()["Old"], "the user's choice")
    }

    @Test
    fun inboxPrefixFromNamespace() {
        val rows = generic(
            listOf(box("INBOX"), box("INBOX.Sent"), box("INBOX.Trash"), box("INBOX.Drafts"), box("INBOX.Work.Archive"), box("Trash")),
            ns = personal("INBOX."),
        )
        val roles = rows.roles()
        assertEquals(FolderRole.SENT, roles["INBOX.Sent"])
        assertEquals(FolderRole.TRASH, roles["INBOX.Trash"])
        assertNull(roles["Trash"], "outside the personal root")
        assertNull(roles["INBOX.Work.Archive"], "not a direct child")
        assertEquals("Sent", rows.first { it.path == "INBOX.Sent" }.name)
    }

    @Test
    fun inboxPrefixWithoutNamespace() {
        val roles = generic(listOf(box("INBOX"), box("INBOX.Sent"), box("INBOX.Spam"), box("INBOX.Archive"))).roles()
        assertEquals(FolderRole.SENT, roles["INBOX.Sent"])
        assertEquals(FolderRole.JUNK, roles["INBOX.Spam"])
        assertEquals(FolderRole.ARCHIVE, roles["INBOX.Archive"])
        assertEquals(FolderRole.SENT, generic(listOf(box("INBOX"), box("INBOX.Sent"), box("Sent"))).roles()["Sent"], "not every mailbox is below INBOX.")
    }

    @Test
    fun personalRoot() {
        assertEquals("INBOX.", FolderRules.prefixOf("INBOX.", listOf("INBOX"), "."))
        assertEquals("INBOX.", FolderRules.prefixOf(null, listOf("INBOX", "INBOX.Sent", "INBOX.a.b"), "."))
        assertEquals("INBOX.", FolderRules.prefixOf("", listOf("INBOX", "INBOX.Sent"), "."), "an empty prefix is no prefix")
        assertEquals("", FolderRules.prefixOf(null, listOf("INBOX", "INBOX.Sent", "Trash"), "."))
        assertEquals("", FolderRules.prefixOf(null, listOf("INBOX"), "."), "INBOX alone says nothing")
        assertEquals("", FolderRules.prefixOf(null, listOf("INBOX", "INBOX.Sent"), null))
        assertEquals("INBOX/", FolderRules.prefixOf(null, listOf("INBOX", "INBOX/Sent"), "/"))
        assertEquals("INBOX.Trash", FolderRules.withPrefix("INBOX.", "Trash"))
        assertEquals("INBOX.Trash", FolderRules.withPrefix("INBOX.", "INBOX.Trash"))
        assertEquals("INBOX", FolderRules.withPrefix("INBOX.", "INBOX"))
        assertEquals("Trash", FolderRules.withPrefix("", "Trash"))
    }

    @Test
    fun nestedArchiveAndImportedGmailFoldersGetNoRole() {
        val roles = generic(
            listOf(box("INBOX", delimiter = "/"), box("Work/Archive", delimiter = "/"), box("[Gmail]/Trash", delimiter = "/"), box("[Gmail]", delimiter = "/")),
        ).roles()
        assertNull(roles["Work/Archive"])
        assertNull(roles["[Gmail]/Trash"], "no [Gmail]/ strip on other servers")
    }

    @Test
    fun otherUsersAndSharedMailboxesAreHidden() {
        val ns = Namespaces(
            personal = listOf(Namespace("", "/")),
            otherUsers = listOf(Namespace("Other Users/", "/")),
            shared = listOf(Namespace("Public/", "/"), Namespace("Public/Team/", "/")),
        )
        val rows = generic(
            listOf(
                box("INBOX", delimiter = "/"), box("Trash", delimiter = "/"), box("Other Users/ann/Trash", delimiter = "/"),
                box("Public/Team/Sent", delimiter = "/"), box("Public", delimiter = "/"), box("Publications", delimiter = "/"),
            ),
            ns,
        )
        val hidden = rows.filter { it.hidden }.map { it.path }.toSet()
        assertEquals(setOf("Other Users/ann/Trash", "Public/Team/Sent", "Public"), hidden, "the namespace root too")
        assertTrue(rows.filter { it.hidden }.all { it.role == null })
        assertEquals(FolderRole.TRASH, rows.roles()["Trash"], "the other user's Trash does not count")
        assertNull(rows.roles()["Public/Team/Sent"])
    }

    @Test
    fun longestPrefixDecidesAndPersonalWins() {
        val ns = Namespaces(
            personal = listOf(Namespace("Mail/", "/")),
            otherUsers = emptyList(),
            shared = listOf(Namespace("Mail/", "/"), Namespace("Mail/Shared/", "/")),
        )
        val hidden = generic(listOf(box("INBOX", delimiter = "/"), box("Mail/Sent", delimiter = "/"), box("Mail/Shared/x", delimiter = "/")), ns)
            .filter { it.hidden }.map { it.path }
        assertEquals(listOf("Mail/Shared/x"), hidden, "a personal prefix wins a tie; the longer shared prefix wins")
    }

    @Test
    fun emptySharedPrefixIsIgnored() {
        // Cyrus: (("" ".")) for shared folders.
        val ns = Namespaces(listOf(Namespace("INBOX.", ".")), emptyList(), listOf(Namespace("", ".")))
        val rows = generic(listOf(box("INBOX"), box("INBOX.Sent"), box("team")), ns)
        assertTrue(rows.none { it.hidden })
        assertEquals(FolderRole.SENT, rows.roles()["INBOX.Sent"])
    }

    @Test
    fun withoutNamespaceNothingIsHiddenByPrefix() {
        assertTrue(generic(listOf(box("INBOX"), box("Other Users.ann"), box("Public.x"))).none { it.hidden })
    }

    @Test
    fun virtualAllAndFlaggedAreHidden() {
        val rows = generic(listOf(box("INBOX"), box("Virtual.All", "\\All"), box("Virtual.Flagged", "\\Flagged"), box("Archive")))
        assertEquals(setOf("Virtual.All", "Virtual.Flagged"), rows.filter { it.hidden }.map { it.path }.toSet())
        assertTrue(rows.none { it.role == FolderRole.ALL })
        assertEquals(FolderRole.ARCHIVE, rows.roles()["Archive"])
        assertTrue(generic(listOf(box("Old", "\\Noselect"))).isEmpty())
    }

    @Test
    fun riseupRulesStayExact() {
        val list = listOf(box("INBOX"), box("Junk"), box("Sent Items"), box("Deleted"), box("All", "\\All"))
        val riseup = FolderRules.mailboxes(1, list, riseup = true)
        assertTrue(riseup.none { it.hidden }, "Riseup hides nothing")
        assertEquals(mapOf("INBOX" to FolderRole.INBOX, "Junk" to null, "Sent Items" to null, "Deleted" to null, "All" to FolderRole.ALL), riseup.roles())
    }

    private fun account(provider: Provider) = Account(
        id = 1, provider = provider, email = "me@example.org", senderName = "", label = "", color = 0,
        auth = AuthKind.PASSWORD, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15,
        gmailCategories = emptySet(), archiveFolder = null, signature = "", state = AccountState.OK, stateText = null,
        serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = 0,
    )

    private fun folder(id: Long, path: String, role: FolderRole? = null, kind: Int = FolderKind.MAILBOX) = Folder(
        id = id, accountId = 1, path = path, name = path, role = role, kind = kind, delimiter = ".", hidden = false,
        notify = false, guard = false, uidValidity = 0, uidNext = 0, modSeq = 0, backfillUid = 0, syncedAt = 0,
    )

    @Test
    fun leafNames() {
        assertEquals("c", FolderRules.leaf("a.b.c", "."))
        assertEquals("a.b", FolderRules.leaf("a.b", null))
        assertEquals("x.", FolderRules.leaf("x.", "."))
    }
}
