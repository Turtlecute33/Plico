package app.plico.sync

import app.plico.account.MailError

/** State of the default network as the push engine sees it. */
data class NetState(
    /** android.net.Network.getNetworkHandle(). */
    val handle: Long,
    /** push.policy.NetworkKey value. */
    val key: String,
    val validated: Boolean,
    val unmeteredWifi: Boolean,
    val charging: Boolean,
)

/** Keeps the CPU awake while a session works. Non-refcounted; [release] is idempotent. */
interface WakeHolder {
    /** Acquires or extends the lock for [timeoutMs]. Call on every server response and progress step. */
    fun renew(timeoutMs: Long = 60_000)
    fun release()

    /** True until [release] or the timeout. */
    val isHeld: Boolean
}

/** What the push engine provides to every [AccountSession] (DESIGN.md §7.1, §8.3). */
interface SessionHost {
    /** The current default network, or null when there is none. Debounced: changes show 2 s late. */
    fun network(): NetState?

    /** A direct reading of the default network, without the debounce: the network of a new connection. */
    fun currentNetwork(): NetState?

    /** One wake-lock holder per session; [tag] never contains an address. */
    fun wakeHolder(tag: String): WakeHolder

    /** A session's [AccountSession.nextDueAt] changed: recompute the single alarm. */
    fun reschedule()

    /** A server byte arrived on a session: a coalescing point (engine runs due refreshes/polls of all sessions). */
    fun onServerActivity()

    /** New mail was committed; [messageIds] are the rows that passed NotifyRule. */
    fun onNewMail(accountId: Long, messageIds: List<Long>)

    /** Messages of the account became seen, archived or deleted (by sync or locally): refresh notifications. */
    fun onMessagesChanged(accountId: Long)

    /** The account entered (error != null) or left (null) a problem state the user must see. */
    fun onAccountProblem(accountId: Long, error: MailError?)
}
