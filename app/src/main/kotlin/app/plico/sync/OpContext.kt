package app.plico.sync

import android.content.Context
import androidx.annotation.StringRes
import app.plico.PlicoApp
import app.plico.data.Account
import app.plico.data.AccountDao
import app.plico.data.ContactDao
import app.plico.data.Db
import app.plico.data.Draft
import app.plico.data.DraftDao
import app.plico.data.Files
import app.plico.data.Folder
import app.plico.data.FolderDao
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MessageDao
import app.plico.data.OpDao
import app.plico.data.Tables
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.SelectResult
import app.plico.mail.smtp.SmtpClient

/**
 * What ops, sends, bodies, attachments and search use while they run on a session loop. In the app it is
 * the [SyncContext] of the connection; tests use a scripted server.
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

    /** Renews the session wake lock for [timeoutMs]; call during long work, and before a long upload. */
    fun progress(timeoutMs: Long = PROGRESS_MS)

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

    /**
     * Reads draft [id] and saves [change] of it in one transaction, so that no other write comes between
     * (attachments added at the same time stay). False when the draft is gone, sending or sent.
     */
    fun updateDraft(id: Long, change: (Draft) -> Draft): Boolean = db.tx(Tables.DRAFT) {
        drafts.get(id)?.let { drafts.update(change(it)) } == true
    }

    companion object {
        fun of(app: PlicoApp) = OpStore(
            app, app.db, app.files, app.accounts, app.folders, app.messages, app.ops, app.drafts, app.contacts,
        )
    }
}

/** The wake-lock time of a step without server data. */
internal const val PROGRESS_MS = 60_000L

/** Wake-lock time for an upload of [bytes]: at least [PROGRESS_MS], else the time at 25 KB/s (a slow uplink). */
internal fun uploadMs(bytes: Int): Long = maxOf(PROGRESS_MS, bytes * 1_000L / UPLOAD_BYTES_PER_S)

private const val UPLOAD_BYTES_PER_S = 25_000L
