package app.plico.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.plico.account.Providers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import org.junit.After
import java.util.UUID

/** A fresh database file per test, deleted afterwards, with helpers to create rows. */
abstract class DbFixture {
    protected val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-${UUID.randomUUID()}.db"
    protected val db = Db(context, name)
    protected val accounts = AccountDao(db)
    protected val folders = FolderDao(db)
    protected val messages = MessageDao(db)
    protected val ops = OpDao(db)
    protected val drafts = DraftDao(db)

    @After
    fun deleteDb() {
        db.close()
        context.deleteDatabase(name)
    }

    protected fun account(
        email: String = "user@riseup.net",
        provider: Provider = Provider.RISEUP,
        servers: Servers = serversOf(provider),
    ): Long = accounts.insert(
        NewAccount(provider, email, "User", accounts.defaultLabel(provider), 0, AuthKind.PASSWORD, byteArrayOf(1), null, servers, 30),
    )

    /** An account of a server that is neither Riseup nor Gmail (no table entry, so no provider rule applies). */
    protected fun otherAccount(email: String = "me@example.org", servers: Servers = OTHER_SERVERS): Long =
        account(email, Provider.OTHER, servers)

    protected fun mailbox(accountId: Long, path: String, role: FolderRole? = null, delimiter: String = "/"): Long =
        folders.upsert(NewFolder(accountId, FolderKind.MAILBOX, path, path, role, delimiter))

    protected fun label(accountId: Long, path: String, role: FolderRole? = null): Long =
        folders.upsert(NewFolder(accountId, FolderKind.LABEL, path, path, role, null))

    protected fun header(
        accountId: Long,
        folderId: Long,
        uid: Long,
        date: Long = 1_000L * uid,
        subject: String = "Subject $uid",
        seen: Boolean = false,
        flagged: Boolean = false,
        messageId: String? = "<m$uid@example.org>",
        notified: Boolean = true,
        labels: Set<Long>? = null,
    ) = NewMessage(
        accountId = accountId, folderId = folderId, uid = uid, date = date, subject = subject,
        fromName = "Sender $uid", fromEmail = "sender$uid@example.org", toList = "user@riseup.net",
        seen = seen, flagged = flagged, messageId = messageId, notified = notified, labels = labels,
    )

    protected fun insert(vararg rows: NewMessage): List<Long> = messages.upsert(rows.toList())

    protected fun count(sql: String, vararg args: Any): Long = db.sql.long(sql, *args)!!

    protected companion object {
        /** Servers under the reserved example.org domain: never contacted (the tests use scripted servers). */
        val OTHER_SERVERS = Servers(Endpoint("imap.example.org", 993, TlsMode.IMPLICIT), Endpoint("smtp.example.org", 465, TlsMode.IMPLICIT))

        fun serversOf(provider: Provider): Servers = when (provider) {
            Provider.GMAIL -> Providers.GMAIL_SERVERS
            Provider.RISEUP -> Providers.RISEUP_SERVERS
            Provider.MICROSOFT -> Providers.OUTLOOK_SERVERS
            Provider.OTHER -> OTHER_SERVERS
        }
    }
}
