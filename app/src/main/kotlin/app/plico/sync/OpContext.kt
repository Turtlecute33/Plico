package app.plico.sync

import android.content.Context
import androidx.annotation.StringRes
import app.plico.PlicoApp
import app.plico.data.Account
import app.plico.data.AccountDao
import app.plico.data.ContactDao
import app.plico.data.Db
import app.plico.data.DraftDao
import app.plico.data.Files
import app.plico.data.Folder
import app.plico.data.FolderDao
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MessageDao
import app.plico.data.OpDao
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.SelectResult
import app.plico.mail.smtp.SmtpClient

/**
 * What ops, sends, bodies, attachments and search use while they run on a session loop. In the app it is
 * a [SyncContext]; tests use a scripted server.
 */
internal interface OpContext {
    val store: OpStore
    val account: Account
    val client: ImapClient

    /** SELECTs [folder] unless it is selected already; the result of its last SELECT. */
    fun select(folder: Folder): SelectResult

    /** An authenticated SMTP connection; the caller closes it. */
    suspend fun smtp(): SmtpClient

    /** Runs the incremental sync of one mailbox now. */
    suspend fun syncFolder(folder: Folder)

    /** Renews the session wake lock; call during long work. */
    fun progress()

    /** True when other work waits; long loops stop at a batch end. */
    val workPending: Boolean
}

/** The database, files and clock of the ops code. Every DAO call blocks: run it on Dispatchers.IO. */
internal class OpStore(
    val context: Context,
    val db: Db,
    val files: Files,
    val accounts: AccountDao = AccountDao(db),
    val folders: FolderDao = FolderDao(db),
    val messages: MessageDao = MessageDao(db),
    val ops: OpDao = OpDao(db),
    val drafts: DraftDao = DraftDao(db),
    val contacts: ContactDao = ContactDao(db),
    /** Wall clock in epoch ms, the clock of op run_at and draft send_at. */
    val now: () -> Long = System::currentTimeMillis,
) {
    fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** The mailbox (kind 0) with [role], or null. */
    fun mailbox(accountId: Long, role: FolderRole): Folder? = folders.byRole(accountId, role, FolderKind.MAILBOX)

    companion object {
        fun of(app: PlicoApp) = OpStore(
            app, app.db, app.files, app.accounts, app.folders, app.messages, app.ops, app.drafts, app.contacts,
        )
    }
}

/** The [OpContext] of a session loop. */
internal class SessionOpContext(private val ctx: SyncContext) : OpContext {
    override val store = OpStore.of(ctx.app)
    override val account: Account get() = ctx.account
    override val client: ImapClient get() = ctx.client
    override fun select(folder: Folder): SelectResult = ctx.select(folder)
    override suspend fun smtp(): SmtpClient = ctx.smtp()
    override suspend fun syncFolder(folder: Folder) = ctx.syncFolder(folder)
    override fun progress() = ctx.progress()
    override val workPending: Boolean get() = ctx.workPending
}
