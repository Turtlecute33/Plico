package app.plico.data

import app.plico.account.Providers
import app.plico.mail.mime.Address
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountDaoTest : DbFixture() {

    @Test
    fun insertReadUpdate() {
        val id = accounts.insert(
            NewAccount(
                Provider.GMAIL, "me@gmail.com", "Me", "Gmail", 3, AuthKind.OAUTH, byteArrayOf(9, 8), null,
                Providers.GMAIL_SERVERS, 0,
            ),
        )
        val a = accounts.get(id)!!
        assertEquals(Provider.GMAIL, a.provider)
        assertEquals(Providers.GMAIL_SERVERS, a.servers)
        assertTrue(a.idle)
        assertEquals(AuthKind.OAUTH, a.auth)
        assertEquals(setOf("primary"), a.gmailCategories)
        assertEquals(PushMode.INSTANT, a.pushMode)
        assertEquals(15, a.pollMinutes)
        assertEquals(0, a.bodyDays)
        assertContentEquals(byteArrayOf(9, 8), accounts.secret(id))
        assertEquals(id, accounts.byEmail("me@gmail.com")?.id)
        assertEquals(id, accounts.byEmail("Me@Gmail.com")?.id)
        assertNull(accounts.byEmail("other@gmail.com"))

        accounts.update(
            a.copy(
                label = "Work", senderName = "M", color = 5, bodyDays = 30, pushMode = PushMode.POLL, pollMinutes = 30,
                gmailCategories = setOf("primary", "updates"), archiveFolder = 4, signature = "-- me", sort = 7,
                email = "ignored@gmail.com",
            ),
        )
        val b = accounts.get(id)!!
        assertEquals(listOf("Work", "M", 5, 30, PushMode.POLL, 30), listOf(b.label, b.senderName, b.color, b.bodyDays, b.pushMode, b.pollMinutes))
        assertEquals(setOf("primary", "updates"), b.gmailCategories)
        assertEquals(listOf(4L, "-- me", 7, "me@gmail.com"), listOf(b.archiveFolder, b.signature, b.sort, b.email))

        accounts.setAuth(id, AuthKind.DEVICE, null, "me@gmail.com")
        accounts.setState(id, AccountState.SIGN_IN_NEEDED, "Sign-in needed")
        accounts.setServerKeepalive(id, 120)
        accounts.setQuota(id, 10, 1000)
        val c = accounts.get(id)!!
        assertEquals(AuthKind.DEVICE, c.auth)
        assertNull(accounts.secret(id))
        assertEquals("me@gmail.com", c.deviceAccount)
        assertEquals(AccountState.SIGN_IN_NEEDED, c.state)
        assertEquals("Sign-in needed", c.stateText)
        assertEquals(120, c.serverKeepaliveS)
        assertEquals(listOf(10L, 1000L), listOf(c.quotaUsed, c.quotaLimit))
        accounts.setSecret(id, byteArrayOf(1))
        assertContentEquals(byteArrayOf(1), accounts.secret(id))
        accounts.setServerKeepalive(id, null)
        assertNull(accounts.get(id)!!.serverKeepaliveS)
    }

    @Test
    fun serversRoundTrip() {
        val servers = Servers(
            Endpoint("imap.example.org", 143, TlsMode.STARTTLS), Endpoint("smtp.example.org", 587, TlsMode.STARTTLS),
            login = "me",
        )
        val id = accounts.insert(
            NewAccount(Provider.OTHER, "me@example.org", "", "Mail", 0, AuthKind.PASSWORD, byteArrayOf(1), null, servers, idle = false),
        )
        val a = accounts.get(id)!!
        assertEquals(servers, a.servers)
        assertEquals("me", a.login)
        assertFalse(a.idle)
        accounts.setIdle(id, true)
        assertTrue(accounts.get(id)!!.idle)
        // The column has no cleartext value: anything unknown reads as implicit TLS.
        db.tx(Tables.ACCOUNT) { write("UPDATE account SET smtp_tls = 7, login = NULL WHERE id = ?", id) }
        assertEquals(TlsMode.IMPLICIT, accounts.get(id)!!.servers.smtp.tls)
        assertEquals("me@example.org", accounts.get(id)!!.login)
        // A provider id from a newer build reads as OTHER.
        db.tx(Tables.ACCOUNT) { write("UPDATE account SET provider = 'future' WHERE id = ?", id) }
        assertEquals(Provider.OTHER, accounts.get(id)!!.provider)
    }

    @Test
    fun serverEditWithAnotherHostStartsAFreshSync() {
        val files = Files(context)
        val id = otherAccount()
        val keep = account("keep@riseup.net")
        val keepInbox = mailbox(keep, "INBOX", FolderRole.INBOX)
        val keepMsg = insert(header(keep, keepInbox, 1)).single()
        val inbox = mailbox(id, "INBOX", FolderRole.INBOX)
        val archive = mailbox(id, "Archive", FolderRole.ARCHIVE)
        val msg = insert(header(id, inbox, 1, subject = "old server")).single()
        messages.saveBody(msg, null, "body", "body", "body", listOf(NewAttachment("2", "x.pdf", "application/pdf", 1, "base64", null, false)))
        val att = files.attachment(msg, messages.attachments(msg).single().id).apply { writeText("x") }
        ops.insert(NewOp(id, OpKind.FLAGS, listOf(msg)))
        val draft = drafts.create(NewDraft(id, "me@example.org"))
        drafts.setOnServer(draft, true)
        accounts.update(accounts.get(id)!!.copy(archiveFolder = archive))
        accounts.setQuota(id, 1, 2)
        accounts.setServerKeepalive(id, 600)

        // Same host and port, other security and user name: the mail stays.
        val sameHost = Servers(
            Endpoint("IMAP.example.org", 993, TlsMode.IMPLICIT), Endpoint("smtp.example.org", 465, TlsMode.IMPLICIT),
            login = "me",
        )
        accounts.setAuth(id, AuthKind.PASSWORD, byteArrayOf(2), null, ServerUpdate(sameHost, idle = false), files)
        val same = accounts.get(id)!!
        assertEquals(sameHost, same.servers)
        assertFalse(same.idle)
        assertContentEquals(byteArrayOf(2), accounts.secret(id))
        assertEquals(archive, same.archiveFolder)
        assertEquals(2L, count("SELECT count(*) FROM folder WHERE account_id = ?", id))
        assertEquals(1L, count("SELECT count(*) FROM op WHERE account_id = ?", id))
        assertTrue(att.exists())

        // Another IMAP host: folders, messages, ops and server state go; the draft stays, as not on the server.
        // A pending Sent copy stays queued: it names a role, not an old UID.
        ops.insert(NewOp(id, OpKind.APPEND, arg = """{"draft":$draft,"role":"sent","file":"sent.eml"}"""))
        val moved = Servers(
            Endpoint("imap.new.example", 993, TlsMode.IMPLICIT), Endpoint("smtp.new.example", 465, TlsMode.IMPLICIT),
        )
        accounts.setAuth(id, AuthKind.PASSWORD, byteArrayOf(3), null, ServerUpdate(moved, idle = true), files)
        val b = accounts.get(id)!!
        assertEquals(moved, b.servers)
        assertTrue(b.idle)
        assertEquals(listOf(null, null, null, null), listOf(b.archiveFolder, b.quotaUsed, b.quotaLimit, b.serverKeepaliveS))
        for (table in listOf("folder", "message")) {
            assertEquals(0L, count("SELECT count(*) FROM $table WHERE account_id = ?", id), table)
        }
        assertEquals(1L, count("SELECT count(*) FROM op WHERE account_id = ? AND kind = 'append'", id))
        assertEquals(1L, count("SELECT count(*) FROM op WHERE account_id = ?", id))
        assertEquals(1L, count("SELECT count(*) FROM message_fts"))
        assertEquals(0L, count("SELECT count(*) FROM message_fts WHERE message_fts MATCH 'old'"))
        assertFalse(drafts.get(draft)!!.onServer)
        assertFalse(att.exists() || att.parentFile!!.exists())
        // The other account keeps everything.
        assertEquals(keepMsg, messages.get(keepMsg)?.id)

        // Another port alone is another server too.
        val inbox2 = mailbox(id, "INBOX", FolderRole.INBOX)
        insert(header(id, inbox2, 1))
        val port = moved.copy(imap = moved.imap.copy(port = 143, tls = TlsMode.STARTTLS))
        accounts.setAuth(id, AuthKind.PASSWORD, byteArrayOf(3), null, ServerUpdate(port, idle = true), files)
        assertEquals(0L, count("SELECT count(*) FROM message WHERE account_id = ?", id))
        // Without server values nothing but the sign-in changes.
        val inbox3 = mailbox(id, "INBOX", FolderRole.INBOX)
        insert(header(id, inbox3, 1))
        accounts.setAuth(id, AuthKind.PASSWORD, byteArrayOf(4), null)
        assertEquals(port, accounts.get(id)!!.servers)
        assertEquals(1L, count("SELECT count(*) FROM message WHERE account_id = ?", id))
    }

    private fun otherAccount(): Long {
        val servers = Servers(
            Endpoint("imap.example.org", 993, TlsMode.IMPLICIT), Endpoint("smtp.example.org", 465, TlsMode.IMPLICIT),
        )
        return accounts.insert(
            NewAccount(Provider.OTHER, "me@example.org", "", "Mail", 0, AuthKind.PASSWORD, byteArrayOf(1), null, servers),
        )
    }

    @Test
    fun defaultLabelsAndSortOrder() = runBlocking {
        assertEquals("Riseup", accounts.defaultLabel(Provider.RISEUP))
        val first = account("a@riseup.net")
        assertEquals("Riseup 2", accounts.defaultLabel(Provider.RISEUP))
        assertEquals("Gmail", accounts.defaultLabel(Provider.GMAIL))
        assertEquals("Outlook", accounts.defaultLabel(Provider.MICROSOFT))
        assertEquals("Mail", accounts.defaultLabel(Provider.OTHER))
        val second = account("b@riseup.net")
        assertEquals("Riseup 3", accounts.defaultLabel(Provider.RISEUP))
        accounts.delete(first)
        assertEquals("Riseup", accounts.defaultLabel(Provider.RISEUP))
        val third = account("c@gmail.com", Provider.GMAIL)
        assertEquals(listOf(second, third), withTimeout(5_000) { accounts.observeAll().first() }.map { it.id })
        assertEquals(listOf(1, 2), accounts.all().map { it.sort })
        assertEquals(third, withTimeout(5_000) { accounts.observe(third).first() }?.id)
    }

    @Test
    fun identities() = runBlocking {
        val id = account()
        accounts.addIdentity(id, "alias@riseup.net", "Alias")
        accounts.addIdentity(id, "alias@riseup.net", "Renamed")
        accounts.addIdentity(id, "b@riseup.net", "")
        val list = accounts.identities(id)
        assertEquals(listOf("alias@riseup.net" to "Renamed", "b@riseup.net" to ""), list.map { it.email to it.name })
        accounts.removeIdentity(list.first().id)
        assertEquals(listOf("b@riseup.net"), withTimeout(5_000) { accounts.observeIdentities(id).first() }.map { it.email })
    }

    @Test
    fun purgeRemovesRowsFilesAndIndex() {
        val files = Files(context)
        val keep = account("keep@riseup.net")
        val keepInbox = mailbox(keep, "INBOX", FolderRole.INBOX)
        val gone = account("gone@riseup.net")
        val inbox = mailbox(gone, "INBOX", FolderRole.INBOX)
        val goneMsg = insert(header(gone, inbox, 1, subject = "confidential")).single()
        val keepMsg = insert(header(keep, keepInbox, 1)).single()
        messages.saveBody(goneMsg, null, "body", "body", "body", listOf(NewAttachment("2", "x.pdf", "application/pdf", 1, "base64", null, false)))
        val att = files.attachment(goneMsg, messages.attachments(goneMsg).single().id).apply { writeText("x") }
        val keepAtt = files.attachment(keepMsg, 1).apply { writeText("y") }
        val draft = drafts.create(NewDraft(gone, "gone@riseup.net"))
        val outFile = files.newOutboxFile(draft, "a.txt").apply { writeText("z") }
        ops.insert(NewOp(gone, OpKind.FLAGS, listOf(goneMsg)))
        ContactDao(db).touch(listOf(Address("Ann", "ann@example.org")))
        TrustDao(db).setTrusted("news@example.org", true, verified = false)

        accounts.purge(gone, files)

        assertNull(accounts.get(gone))
        assertFalse(att.exists() || att.parentFile!!.exists())
        assertFalse(outFile.exists() || outFile.parentFile!!.exists())
        assertTrue(keepAtt.exists())
        val left = mapOf(
            "message" to 1L, "folder" to 1L, "message_folder" to 1L, "draft" to 0L, "op" to 0L, "body" to 0L,
            "attachment" to 0L, "identity" to 0L,
        )
        for ((table, n) in left) assertEquals(n, count("SELECT count(*) FROM $table"), table)
        assertEquals(1, count("SELECT count(*) FROM message_fts"))
        assertEquals(0, count("SELECT count(*) FROM message_fts WHERE message_fts MATCH 'confidential'"))
        // Shared by the accounts: kept while one is left, gone with the last one.
        assertEquals(1, count("SELECT count(*) FROM contact"))
        assertEquals(1, count("SELECT count(*) FROM trusted_sender"))

        files.deleteAttachments(keepMsg)

        accounts.purge(keep, files)
        for (table in listOf("account", "message", "contact", "trusted_sender", "message_fts")) {
            assertEquals(0, count("SELECT count(*) FROM $table"), table)
        }
    }

    @Test
    fun contactsAndTrust() = runBlocking {
        val contacts = ContactDao(db)
        val trust = TrustDao(db)
        contacts.touch(
            listOf(Address("Anna Berg", "Anna@Example.org"), Address(null, "bob@example.org"), Address(null, "anna@example.org")),
            now = 10,
        )
        contacts.touch(listOf(Address(null, "bob@example.org")), now = 20)
        contacts.touch(listOf(Address("", " "), Address("Carl_X", "carl@example.org")), now = 5)
        contacts.touch(listOf(Address(null, "annette@example.org")), now = 30)

        // "b" starts the address of Bob and the word "Berg" in Anna's name; Bob has more uses.
        assertEquals(listOf("bob@example.org", "anna@example.org"), contacts.suggest("b").map { it.email })
        assertEquals(2, contacts.suggest("bob").single().uses)
        assertEquals(listOf("anna@example.org"), contacts.suggest("ber").map { it.email })
        assertEquals("Anna Berg", contacts.suggest("ANNA B").single().name)
        // Same uses: most recent first. Then more uses first; a touch without a name keeps the name.
        assertEquals(listOf("annette@example.org", "anna@example.org"), contacts.suggest("ann").map { it.email })
        contacts.touch(listOf(Address(null, "anna@example.org")), now = 40)
        assertEquals(listOf("anna@example.org", "annette@example.org"), contacts.suggest("ann").map { it.email })
        assertEquals("Anna Berg", contacts.suggest("anna@").single().name)
        // LIKE wildcards in the prefix are literal.
        assertEquals(listOf("carl@example.org"), contacts.suggest("carl_").map { it.email })
        assertEquals(emptyList(), contacts.suggest("b_b"))
        assertEquals(emptyList(), contacts.suggest("%"))
        assertEquals(emptyList(), contacts.suggest("  "))
        assertEquals(1, contacts.suggest("a", limit = 1).size)
        assertTrue(contacts.isKnown("ANNA@example.org"))
        assertFalse(contacts.isKnown("zed@example.org"))

        assertFalse(trust.isTrusted("news@example.org"))
        trust.setTrusted("News@Example.org", true, verified = true)
        assertTrue(trust.isTrusted("news@example.org"))
        assertEquals(TrustedOn.VERIFIED, withTimeout(5_000) { trust.observeTrusted("NEWS@example.org").first() })
        // A trust set by From only is the wider kind, and a later verified one does not make it narrower.
        trust.setTrusted("news@example.org", true, verified = false)
        assertEquals(TrustedOn.FROM_ONLY, withTimeout(5_000) { trust.observeTrusted("news@example.org").first() })
        trust.setTrusted("news@example.org", true, verified = true)
        assertEquals(TrustedOn.FROM_ONLY, withTimeout(5_000) { trust.observeTrusted("news@example.org").first() })
        trust.setTrusted("news@example.org", false, verified = true)
        assertFalse(trust.isTrusted("news@example.org"))
        assertNull(withTimeout(5_000) { trust.observeTrusted("news@example.org").first() })
    }
}
