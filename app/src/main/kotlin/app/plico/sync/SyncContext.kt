package app.plico.sync

import android.util.Log
import app.plico.BuildConfig
import app.plico.PlicoApp
import app.plico.account.AndroidSocketHooks
import app.plico.account.Credentials
import app.plico.account.Providers
import app.plico.data.Account
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.SelectResult
import app.plico.mail.smtp.SmtpClient

/**
 * What code running on a session loop may use. Created by AccountSession for each connection; used by Ops,
 * Sender, Bodies, Attachments and Search. Every call blocks or suspends on the loop thread (Dispatchers.IO).
 */
class SyncContext(
    val app: PlicoApp,
    val account: Account,
    val client: ImapClient,
    val credentials: Credentials,
    private val session: AccountSession,
) {
    internal val sync = SyncAccount(
        SyncEnv(
            account = account,
            client = client,
            accounts = app.accounts,
            folders = app.folders,
            messages = app.messages,
            ops = app.ops,
            resources = app.resources,
            notes = session.notes,
            protectedIds = { Ops.protectedIds(app, account.id) },
            knownMessages = session.knownMessages,
            progress = session::progress,
            onNewMail = session::onServerNewMail,
        ),
    )

    /**
     * SELECTs [folder] unless it is already selected; returns the last SELECT result for it, with EXISTS kept up
     * to date. For a kind-0 mailbox, or the Gmail `\Inbox` view (INBOX). The caller compares UIDVALIDITY.
     */
    fun select(folder: Folder): SelectResult = sync.env.selection.ensure(mailboxOf(folder))

    /** The folder of [role]: a mailbox when there is one, else a Gmail label view; Riseup archive: the user's choice first. */
    fun folder(role: FolderRole): Folder? = sync.folder(role)

    /** Folder rows of this account (fresh from the DB). */
    fun folders(): List<Folder> = app.folders.byAccount(account.id)

    /**
     * The mailbox of [role] as a MOVE or APPEND target, created on first use when it is missing (Riseup `Archive`,
     * `Spam`, `Sent`, `Drafts`, `Trash`). Gmail: throws MailError.Setup when Gmail hides it from IMAP.
     */
    fun ensureFolder(role: FolderRole): Folder = sync.ensureFolder(role)

    /** Runs the incremental sync of one mailbox now (DESIGN §7.2 / §7.3); a Gmail label view syncs All Mail. */
    suspend fun syncFolder(folder: Folder) {
        val report = SyncReport()
        sync.syncFolder(folder, report)
        session.publish(sync, report)
    }

    /** Opens an authenticated SMTP connection; the caller closes it. */
    suspend fun smtp(): SmtpClient {
        val server = Providers.of(account.provider).smtp
        val smtp = SmtpClient.connect(server.host, server.port, AndroidSocketHooks, debugLog("smtp"))
        try {
            credentials.authSmtp(smtp)
        } catch (e: Throwable) {
            smtp.close()
            throw e
        }
        return smtp
    }

    /** Renews the session wake lock; call during long work (batches, uploads). */
    fun progress(): Unit = session.progress()

    /** True when the UI or the engine asked for other work; long loops should yield at batch ends. */
    val workPending: Boolean get() = session.isWorkPending

    private fun mailboxOf(folder: Folder): String =
        if (folder.kind == FolderKind.LABEL && folder.role == FolderRole.INBOX) FolderRules.INBOX else folder.path
}

/** Protocol log for debug builds only: `:mail` passes tags, verbs, statuses and codes, never content. */
internal fun debugLog(tag: String): (String) -> Unit =
    if (BuildConfig.DEBUG) { line -> Log.d("Plico/$tag", line) } else { _ -> }
