package app.plico.account

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.plico.PlicoApp
import app.plico.R
import app.plico.data.FolderRole
import app.plico.data.Provider
import app.plico.mail.imap.ImapClient
import app.plico.mail.smtp.SmtpClient
import app.plico.sync.FolderRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Setup's connection test (DESIGN.md §13): IMAP login and capabilities, Gmail mailboxes, SMTP login. */
object AccountProbe {
    /** Notes for the Gmail mailboxes that LIST does not show ("Show in IMAP" off); a hidden All Mail stops setup. */
    internal val MISSING_NOTES = mapOf(
        FolderRole.TRASH to R.string.account_note_gmail_trash_hidden,
        FolderRole.JUNK to R.string.account_note_gmail_spam_hidden,
    )

    /**
     * Signs in to the provider's IMAP and SMTP servers with [credentials] and logs out; returns user-visible
     * notes. Every failure is thrown as a [MailError] (see [MailErrors.classify]). A Gmail account without All
     * Mail in IMAP fails with [MailError.Setup]; a hidden Trash or Spam only adds a note.
     */
    suspend fun probe(app: PlicoApp, provider: Provider, credentials: Credentials): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val spec = Providers.of(provider)
                val notes = ArrayList<String>()
                ImapClient.connect(spec.imap.host, spec.imap.port, AndroidSocketHooks).use { imap ->
                    credentials.authImap(imap)
                    imap.requireCapabilities()
                    if (provider == Provider.GMAIL) {
                        val missing = FolderRules.gmail(0, imap.list()).missing
                        if (FolderRole.ALL in missing) throw MailError.Setup(app.getString(R.string.account_gmail_all_mail_hidden))
                        missing.mapNotNullTo(notes) { role -> MISSING_NOTES[role]?.let(app::getString) }
                    }
                    try {
                        imap.logout()
                    } catch (_: IOException) {
                        // The test passed; a lost LOGOUT reply does not change that.
                    }
                }
                SmtpClient.connect(spec.smtp.host, spec.smtp.port, AndroidSocketHooks).use { smtp ->
                    credentials.authSmtp(smtp)
                    smtp.quit()
                }
                notes
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
