package app.plico.sync

import app.plico.PlicoApp
import app.plico.account.Credentials
import app.plico.data.Account
import app.plico.data.Folder
import app.plico.data.FolderRole
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.SelectResult
import app.plico.mail.smtp.SmtpClient

/**
 * What code running on a session loop may use. Created by AccountSession (sync-engine agent);
 * used by Ops, Sender, Bodies, Attachments and Search (ops agent).
 */
class SyncContext(
    val app: PlicoApp,
    val account: Account,
    val client: ImapClient,
    val credentials: Credentials,
    private val session: AccountSession,
) {
    /** SELECTs [folder] unless it is already selected; returns the last SELECT result for it. */
    fun select(folder: Folder): SelectResult = TODO()

    fun folder(role: FolderRole): Folder? = TODO()

    /** Folder rows of this account (fresh from the DB). */
    fun folders(): List<Folder> = TODO()

    /** Runs the incremental sync of one mailbox now (DESIGN §7.2 / §7.3). */
    suspend fun syncFolder(folder: Folder): Unit = TODO()

    /** Opens an authenticated SMTP connection; the caller closes it. */
    suspend fun smtp(): SmtpClient = TODO()

    /** Renews the session wake lock; call during long work (batches, uploads). */
    fun progress(): Unit = TODO()

    /** True when the UI or the engine asked for other work; long loops should yield at batch ends. */
    val workPending: Boolean get() = TODO()
}
