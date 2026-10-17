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
import app.plico.mail.smtp.SmtpClient
import app.plico.sync.FolderRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** What setup's connection test found: notes for the user, and false [idle] when the server has no IDLE. */
data class ProbeResult(val notes: List<String>, val idle: Boolean)

/** Setup's connection test (DESIGN.md §13): IMAP login and capabilities, Gmail mailboxes, SMTP login. */
object AccountProbe {
    /** Notes for the Gmail mailboxes that LIST does not show ("Show in IMAP" off); a hidden All Mail stops setup. */
    internal val MISSING_NOTES = mapOf(
        FolderRole.TRASH to R.string.account_note_gmail_trash_hidden,
        FolderRole.JUNK to R.string.account_note_gmail_spam_hidden,
    )

    /**
     * Signs in to the IMAP and SMTP [servers] with [credentials] and logs out; returns user-visible notes and
     * whether the server has IDLE. Every failure is thrown as a [MailError] (see [MailErrors.classify]). A Gmail
     * account without All Mail in IMAP fails with [MailError.Setup]; a hidden Trash or Spam only adds a note.
     */
    suspend fun probe(app: PlicoApp, provider: Provider, servers: Servers, credentials: Credentials): ProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val notes = ArrayList<String>()
                val idle = ImapClient.connect(servers.imap, AndroidSocketHooks).use { imap ->
                    credentials.authImap(imap)
                    imap.requireCapabilities()
                    if (provider == Provider.GMAIL) {
                        val missing = FolderRules.gmail(0, imap.list()).missing
                        if (FolderRole.ALL in missing) throw MailError.Setup(app.getString(R.string.account_gmail_all_mail_hidden))
                        missing.mapNotNullTo(notes) { role -> MISSING_NOTES[role]?.let(app::getString) }
                    }
                    val hasIdle = "IDLE" in imap.caps
                    try {
                        imap.logout()
                    } catch (_: IOException) {
                        // The test passed; a lost LOGOUT reply does not change that.
                    }
                    hasIdle
                }
                SmtpClient.connect(servers.smtp, AndroidSocketHooks).use { smtp ->
                    credentials.authSmtp(smtp)
                    smtp.quit()
                }
                ProbeResult(notes, idle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MailErrors.classify(e, networkValidated(app))
            }
        }

    private fun networkValidated(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
