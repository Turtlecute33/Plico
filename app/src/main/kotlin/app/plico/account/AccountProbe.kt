package app.plico.account

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.plico.PlicoApp
import app.plico.R
import app.plico.data.FolderRole
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapMissingCapabilityException
import app.plico.mail.net.Endpoint
import app.plico.mail.smtp.SmtpAuthUnavailableException
import app.plico.mail.smtp.SmtpClient
import app.plico.sync.FolderRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** What setup's connection test found: notes for the user, and false [idle] when the server has no IDLE. */
data class ProbeResult(val notes: List<String>, val idle: Boolean)

/** Setup's connection test (DESIGN.md §13): IMAP login and capabilities, mailbox access, SMTP login. */
object AccountProbe {
    /** Notes for the Gmail mailboxes that LIST does not show ("Show in IMAP" off); a hidden All Mail stops setup. */
    internal val MISSING_NOTES = mapOf(
        FolderRole.TRASH to R.string.account_note_gmail_trash_hidden,
        FolderRole.JUNK to R.string.account_note_gmail_spam_hidden,
    )

    /** The minimal IMAP ID for servers that refuse mailboxes without one (NetEase): no version, OS or device. */
    internal val MINIMAL_ID = listOf("name" to "Mail")

    /** The poll interval of a new account in minutes (the schema default of `account.poll_minutes`). */
    private const val POLL_MINUTES = 15

    /**
     * Signs in to the IMAP and SMTP [servers] with [credentials] and logs out; returns user-visible notes and
     * whether the server has IDLE. Every failure is thrown as a [MailError] (see [MailErrors.classify] and
     * [MailErrors.localized]).
     *
     * Gmail: a hidden All Mail fails with [MailError.Setup]; a hidden Trash or Spam only adds a note. Other
     * servers: LIST and EXAMINE INBOX must work (Exchange and NetEase refuse only mailbox commands). A Gmail
     * server for another provider fails (no promotion to Gmail). Microsoft: an SMTP refusal adds a note instead
     * of a failure, because the account can still receive.
     */
    suspend fun probe(app: PlicoApp, provider: Provider, servers: Servers, credentials: Credentials): ProbeResult =
        withContext(Dispatchers.IO) {
            probe(
                texts = Texts(app::getString) { id, count -> app.resources.getQuantityString(id, count, count) },
                provider = provider,
                servers = servers,
                credentials = credentials,
                imapConnect = { ImapClient.connect(it, AndroidSocketHooks) },
                smtpConnect = { SmtpClient.connect(it, AndroidSocketHooks) },
                validated = { networkValidated(app) },
            )
        }

    /** The strings of the probe; tests give their own. */
    internal class Texts(val string: (Int) -> String, val plural: (id: Int, count: Int) -> String)

    /** [probe] with its platform parts given: strings, the two connects and the network state. */
    internal suspend fun probe(
        texts: Texts,
        provider: Provider,
        servers: Servers,
        credentials: Credentials,
        imapConnect: (Endpoint) -> ImapClient,
        smtpConnect: (Endpoint) -> SmtpClient,
        validated: () -> Boolean,
    ): ProbeResult = try {
        val notes = ArrayList<String>()
        val idle = imapConnect(servers.imap).use { imap -> testImap(texts, provider, imap, credentials, notes) }
        if (!idle) notes += texts.plural(R.plurals.account_note_no_idle, POLL_MINUTES)
        smtpConnect(servers.smtp).use { smtp ->
            try {
                credentials.authSmtp(smtp)
                smtp.quit()
            } catch (e: MailError.Setup) {
                if (provider != Provider.MICROSOFT || !MailErrors.hasSmtpCause(e)) throw e
                notes += texts.string(R.string.account_note_smtp_off)
            }
        }
        ProbeResult(notes, idle)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw failure(texts, e, validated)
    }

    /** The IMAP part; returns true when the server has IDLE. */
    private suspend fun testImap(
        texts: Texts,
        provider: Provider,
        imap: ImapClient,
        credentials: Credentials,
        notes: MutableList<String>,
    ): Boolean {
        credentials.authImap(imap)
        imap.requireCapabilities()
        if (imap.isGmail && provider != Provider.GMAIL) {
            throw MailError.Setup(texts.string(R.string.account_error_gmail_server))
        }
        if (ProviderTable.needsId(imap.host)) imap.id(MINIMAL_ID)
        if (provider == Provider.GMAIL) {
            val missing = FolderRules.gmail(0, imap.list()).missing
            if (FolderRole.ALL in missing) throw MailError.Setup(texts.string(R.string.account_gmail_all_mail_hidden))
            missing.mapNotNullTo(notes) { role -> MISSING_NOTES[role]?.let(texts.string) }
        } else {
            imap.list()
            imap.examine("INBOX")
        }
        val idle = "IDLE" in imap.caps
        try {
            imap.logout()
        } catch (_: IOException) {
            // The test passed; a lost LOGOUT reply does not change that.
        }
        return idle
    }

    /** No usable password mechanism gets its own text; every other failure is classified. */
    private fun failure(texts: Texts, e: Exception, validated: () -> Boolean): MailError = when {
        e is ImapMissingCapabilityException && "AUTH=PLAIN" in e.missing || e is SmtpAuthUnavailableException ->
            MailError.Setup(texts.string(R.string.account_error_auth_method), e)
        else -> MailErrors.localized(MailErrors.classify(e, validated()), texts.string)
    }

    private fun networkValidated(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
