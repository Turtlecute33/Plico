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
 * The [OpContext] of one connection: what code running on a session loop may use. Created by AccountSession for
 * each connection; used by the sync steps, Ops, Sender, Bodies, Attachments and Search. Every call blocks or
 * suspends on the loop thread (Dispatchers.IO).
 */
internal class SyncContext(
    app: PlicoApp,
    override val account: Account,
    override val client: ImapClient,
    private val credentials: Credentials,
    private val session: AccountSession,
) : OpContext {
    override val store: OpStore get() = session.store

    val sync = SyncAccount(
        SyncEnv(
            account = account,
            client = client,
            accounts = app.accounts,
            folders = app.folders,
            messages = app.messages,
            ops = app.ops,
            resources = app.resources,
            notes = app.prefs,
            protectedIds = { app.ops.referencedMessageIds(account.id) },
            knownMessages = session.knownMessages,
            progress = session::progress,
            onNewMail = session::onServerNewMail,
        ),
    )

    /**
     * SELECTs [folder] unless it is already selected; returns the last SELECT result for it, with EXISTS kept up
     * to date. For a kind-0 mailbox, or the Gmail `\Inbox` view (INBOX). The caller compares UIDVALIDITY.
     */
    override fun select(folder: Folder): SelectResult = sync.env.selection.ensure(mailboxOf(folder))

    /** Runs the incremental sync of one mailbox now (DESIGN §7.2 / §7.3); a Gmail label view syncs All Mail. */
    override suspend fun syncFolder(folder: Folder) {
        val report = SyncReport()
        sync.syncFolder(folder, report)
        session.publish(sync, report)
    }

    /** Opens an authenticated SMTP connection; the caller closes it. */
    override suspend fun smtp(): SmtpClient {
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
    override fun progress(timeoutMs: Long): Unit = session.progress(timeoutMs)

    /** True when the UI or the engine asked for other work; long loops should yield at batch ends. */
    override val workPending: Boolean get() = session.isWorkPending

    private fun mailboxOf(folder: Folder): String =
        if (folder.kind == FolderKind.LABEL && folder.role == FolderRole.INBOX) FolderRules.INBOX else folder.path
}

/** Protocol log for debug builds only: `:mail` passes tags, verbs, statuses and codes, never content. */
internal fun debugLog(tag: String): (String) -> Unit =
    if (BuildConfig.DEBUG) { line -> Log.d("Plico/$tag", line) } else { _ -> }
