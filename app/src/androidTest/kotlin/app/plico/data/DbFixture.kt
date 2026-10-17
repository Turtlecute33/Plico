package app.plico.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.plico.account.Providers
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

    protected fun account(email: String = "user@riseup.net", provider: Provider = Provider.RISEUP): Long {
        val servers = if (provider == Provider.GMAIL) Providers.GMAIL_SERVERS else Providers.RISEUP_SERVERS
        return accounts.insert(
            NewAccount(provider, email, "User", accounts.defaultLabel(provider), 0, AuthKind.PASSWORD, byteArrayOf(1), null, servers, 30),
        )
    }

    protected fun mailbox(accountId: Long, path: String, role: FolderRole? = null): Long =
        folders.upsert(NewFolder(accountId, FolderKind.MAILBOX, path, path, role, "/"))

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
}
