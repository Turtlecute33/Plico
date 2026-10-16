package app.plico.sync

import app.plico.PlicoApp
import app.plico.account.MailError
import app.plico.push.policy.NetworkRules
import kotlinx.coroutines.flow.StateFlow

/**
 * One per account: owns at most one IMAP connection and one worker loop (DESIGN.md §7.1).
 * All IMAP work of the account runs on the loop, in order. Owner: sync-engine agent.
 */
class AccountSession(val app: PlicoApp, val accountId: Long, private val host: SessionHost) {

    enum class Mode {
        /** Keep a connection and IDLE on the IDLE mailbox. */
        IDLE,

        /** Connect at each poll, sync, log out. */
        POLL,

        /** No background work; connect only for [request] and queued work, then log out. */
        OFF,
    }

    enum class Reason { WORK, USER_REFRESH, ALARM, NETWORK, OPEN_FOLDER }

    sealed interface Status {
        data object Offline : Status
        data object Connecting : Status
        data object Syncing : Status
        data object Idle : Status
        data class Waiting(val retryAt: Long) : Status
        data class Problem(val error: MailError) : Status
        data object NoNetwork : Status
    }

    val status: StateFlow<Status> get() = TODO()

    /** Set by the push engine. Changing it starts, reconfigures or winds down the loop. */
    var mode: Mode
        get() = TODO()
        set(value) = TODO()

    /** Marks work pending and ends IDLE soon. Never writes to a socket on the caller's thread. */
    fun kick(reason: Reason = Reason.WORK): Unit = TODO()

    /** Engine wake (alarm, server byte on any session, network, app visible): runs due refresh/poll/reconnect. */
    fun onWake(now: Long): Unit = TODO()

    fun onNetworkEvent(event: NetworkRules.Event, network: NetState?): Unit = TODO()

    /** Earliest elapsedRealtime this session needs a wake: keepalive, expiry, backoff, poll, op run_at, send_at. */
    fun nextDueAt(): Long? = TODO()

    /** Runs [block] on the loop with a connected, authenticated client (connects on demand). */
    suspend fun <T> request(block: suspend SyncContext.() -> T): T = TODO()

    /** The user fixed the sign-in: clear AuthRequired and reconnect. */
    fun signedIn(): Unit = TODO()

    fun close(): Unit = TODO()
}
