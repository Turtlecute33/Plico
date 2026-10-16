package app.plico.notify

import android.app.Notification
import android.content.Intent
import app.plico.PlicoApp
import app.plico.account.MailError
import app.plico.data.Account

/** All notifications (DESIGN §9). Owner: push agent. */
class Notifier(private val app: PlicoApp) {
    /** Creates/renames the per-account channels and the shared ones. */
    fun ensureChannels(accounts: List<Account>): Unit = TODO()
    fun deleteChannel(accountId: Long): Unit = TODO()

    /** New mail (already filtered by NotifyRule): summary first, then up to 5 children. */
    fun postNew(accountId: Long, messageIds: List<Long>): Unit = TODO()

    /** Re-evaluates shown notifications of the account (seen/archived/deleted mail is cancelled). */
    fun refresh(accountId: Long): Unit = TODO()

    fun cancelAccount(accountId: Long): Unit = TODO()
    fun signInNeeded(account: Account, intent: Intent?): Unit = TODO()
    fun problem(account: Account, error: MailError): Unit = TODO()
    fun clearProblem(accountId: Long): Unit = TODO()

    /** The foreground-service notification with a privacy-neutral [text]. */
    fun serviceNotification(text: String): Notification = TODO()

    /** "Push stopped · Resume" after a Task Manager stop or a failed FGS start. */
    fun pushStopped(): Unit = TODO()
}
