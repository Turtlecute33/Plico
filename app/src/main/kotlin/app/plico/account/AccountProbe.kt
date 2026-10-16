package app.plico.account

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.plico.PlicoApp
import app.plico.R
import app.plico.data.Provider
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.MailboxInfo
import app.plico.mail.smtp.SmtpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Setup's connection test (DESIGN.md §13): IMAP login and capabilities, Gmail mailboxes, SMTP login. */
object AccountProbe {
    /** [caps] are the IMAP capabilities after login (for `account.caps`); [notes] are user-visible hints. */
    data class Result(val caps: Set<String>, val notes: List<String>)

    internal const val ALL = "\\All"
    internal const val TRASH = "\\Trash"
    internal const val JUNK = "\\Junk"

    /**
     * Signs in to the provider's IMAP and SMTP servers with [credentials] and logs out. Every failure is
     * thrown as a [MailError] (see [MailErrors.classify]). A Gmail account without All Mail in IMAP fails with
     * [MailError.Setup]; a hidden Trash or Spam only adds a note.
     */
    suspend fun probe(app: PlicoApp, provider: Provider, credentials: Credentials): Result =
        withContext(Dispatchers.IO) {
            try {
                val spec = Providers.of(provider)
                val notes = ArrayList<String>()
                val caps = ImapClient.connect(spec.imap.host, spec.imap.port, AndroidSocketHooks).use { imap ->
                    credentials.authImap(imap)
                    imap.requireCapabilities()
                    if (provider == Provider.GMAIL) {
                        val missing = missingGmailMailboxes(imap.list())
                        if (ALL in missing) throw MailError.Setup(app.getString(R.string.account_gmail_all_mail_hidden))
                        if (TRASH in missing) notes += app.getString(R.string.account_note_gmail_trash_hidden)
                        if (JUNK in missing) notes += app.getString(R.string.account_note_gmail_spam_hidden)
                    }
                    imap.caps.toSet().also {
                        try {
                            imap.logout()
                        } catch (_: IOException) {
                            // The test passed; a lost LOGOUT reply does not change that.
                        }
                    }
                }
                SmtpClient.connect(spec.smtp.host, spec.smtp.port, AndroidSocketHooks).use { smtp ->
                    credentials.authSmtp(smtp)
                    smtp.quit()
                }
                Result(caps, notes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MailErrors.classify(e, networkValidated(app))
            }
        }

    /** The Gmail special-use mailboxes ([ALL], [TRASH], [JUNK]) that LIST does not show ("Show in IMAP" off). */
    internal fun missingGmailMailboxes(list: List<MailboxInfo>): Set<String> {
        val seen = list.flatMap { it.attributes }.map { it.lowercase() }.toSet()
        return setOf(ALL, TRASH, JUNK).filterTo(LinkedHashSet()) { it.lowercase() !in seen }
    }

    private fun networkValidated(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
