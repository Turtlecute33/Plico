package app.plico.push

import app.plico.PlicoApp
import app.plico.account.MailError
import app.plico.sync.NetState
import app.plico.sync.SessionHost
import app.plico.sync.WakeHolder
import kotlinx.coroutines.flow.StateFlow

/** Grants and states that decide whether push works (DESIGN §8.2). */
data class PushStatus(
    val running: Boolean,
    val pausedByUser: Boolean,
    val stoppedBySystem: Boolean,
    val hasNetwork: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val exactAlarms: Boolean,
    val backgroundRestricted: Boolean,
)

/** Owns the foreground service, the alarm, the network watcher and the sessions' modes. Owner: push agent. */
class PushEngine(private val app: PlicoApp) : SessionHost {
    val status: StateFlow<PushStatus> get() = TODO()

    /** Starts the service when accounts exist and push is not paused (call from the foreground UI). */
    fun ensureStarted(): Unit = TODO()

    /** Accounts added/removed or push settings changed. */
    fun onAccountsChanged(): Unit = TODO()

    fun pauseByUser(): Unit = TODO()
    fun resume(): Unit = TODO()

    /** The app UI became visible or hidden (coalescing point; backfill policy). */
    fun onAppVisible(visible: Boolean): Unit = TODO()

    /** Re-reads grants (after returning from a settings screen). */
    fun refreshStatus(): Unit = TODO()

    override fun network(): NetState? = TODO()
    override fun wakeHolder(tag: String): WakeHolder = TODO()
    override fun reschedule(): Unit = TODO()
    override fun onServerActivity(): Unit = TODO()
    override fun onNewMail(accountId: Long, messageIds: List<Long>): Unit = TODO()
    override fun onMessagesChanged(accountId: Long): Unit = TODO()
    override fun onAccountProblem(accountId: Long, error: MailError?): Unit = TODO()
}
