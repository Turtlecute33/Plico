package app.plico.sync

import app.plico.data.Account
import app.plico.data.DbFixture
import app.plico.data.Files
import app.plico.data.Folder
import app.plico.data.Provider
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapConnection
import app.plico.mail.imap.SelectResult
import app.plico.mail.smtp.SmtpClient
import org.junit.After
import org.junit.Before
import java.io.File

/** A database, an [OpStore] with a settable clock, [MailActions] with a recording host, and scripted servers. */
abstract class OpFixture : DbFixture() {
    /** Epoch ms of the store's clock. */
    protected var clock = System.currentTimeMillis()

    internal val store by lazy { OpStore(context, db, Files(context), now = { clock }) }
    internal val host = RecordingHost()
    internal val actions by lazy { MailActions(store, host) }

    @Before
    @After
    fun clearFiles() {
        File(context.filesDir, "outbox").deleteRecursively()
        File(context.cacheDir, "att").deleteRecursively()
    }

    protected fun gmailAccount() = account("me@gmail.com", Provider.GMAIL)

    /** A session context for [accountId] on [imap]; [smtp] servers answer the SMTP connections in order. */
    internal fun session(accountId: Long, imap: FakeImap, vararg smtp: FakeSmtp) =
        FakeOpContext(store, accounts.get(accountId)!!, imap, smtp.toMutableList()).also { host.context = it }
}

/** An [OpContext] on scripted servers; SELECT is skipped when the mailbox is selected, as in a session. */
internal class FakeOpContext(
    override val store: OpStore,
    override val account: Account,
    imap: FakeImap,
    private val smtpServers: MutableList<FakeSmtp>,
) : OpContext {
    override val client = ImapClient(ImapConnection(imap)).also { it.conn.greeting() }
    private var last: SelectResult? = null
    val synced = ArrayList<Folder>()

    override fun select(folder: Folder): SelectResult {
        last?.let { if (client.selected == folder.path) return it }
        return client.select(folder.path).also { last = it }
    }

    /** Runs when the sender opens its SMTP connection. */
    var beforeSmtp: () -> Unit = {}

    override suspend fun smtp(): SmtpClient {
        beforeSmtp()
        return SmtpClient(smtpServers.removeAt(0)).also { it.start() }
    }

    override suspend fun syncFolder(folder: Folder) {
        synced += folder
    }

    override fun progress(timeoutMs: Long) = Unit

    override val workPending = false
}

/** Records kicks, syncs and notification refreshes; runs requests on [context]. */
internal class RecordingHost : ActionHost {
    var context: OpContext? = null
    val kicks = ArrayList<Long>()
    val syncs = ArrayList<Long>()
    val refreshes = ArrayList<Long>()

    override fun kick(accountId: Long) {
        kicks += accountId
    }

    override fun refresh(accountId: Long) {
        syncs += accountId
    }

    override fun messagesChanged(accountId: Long) {
        refreshes += accountId
    }

    override suspend fun <T> request(accountId: Long, closeOnCancel: Boolean, block: suspend OpContext.() -> T): T =
        (context ?: throw AssertionError("No server for this test")).block()

    override val undoSendSeconds = 0

    override fun accountsChanged() = Unit

    override suspend fun removeAccount(account: Account) = Unit
}
