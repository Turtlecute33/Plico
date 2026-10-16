package app.plico.push.policy

import kotlin.random.Random

/**
 * Reconnect backoff of one account session (DESIGN.md §8.3; push PB9, PB10). Thread-safe; times are elapsedRealtime.
 *
 * Steps: 0 s, 5 s, 20 s in process (the session waits under its wake lock), then alarms at 2, 5, 15, 30, 60 min
 * with ±20 % jitter. [Kind.THROTTLED] starts at the 2-min step, [Kind.TLS] at the 15-min step, [Kind.AUTH] stops
 * retries until [reset]. Only a healthy connection resets the step: IDLE `+` received and up for 2 min
 * ([onIdle]), or one successful refresh or poll ([markHealthy]). A new network resets it once ([onNetworkChanged]).
 *
 * MailError mapping: `AuthRequired` → AUTH, `Tls` → TLS, `Throttled` → THROTTLED, everything else → NETWORK.
 * A lost default network parks the session without calling [onFailure].
 */
class Backoff(private val random: Random = Random.Default) {
    enum class Kind { NETWORK, THROTTLED, TLS, AUTH }

    /** Try again at [at]: in the session when [inProcess], else from the alarm ([retryAt]). */
    data class Retry(val at: Long, val inProcess: Boolean)

    /** Failures since the last healthy connection, network change or [reset]. */
    var step = 0
        @Synchronized get
        private set

    /** Pending alarm retry; part of the session's nextDueAt(). */
    var retryAt: Long? = null
        @Synchronized get
        private set

    /** True after [Kind.AUTH]: no attempts until [reset]. */
    var stopped = false
        @Synchronized get
        private set

    private var idleSince: Long? = null
    private var network: Long? = null
    private var userAttempt = false
    private var savedRetryAt: Long? = null

    @Synchronized
    fun isDue(now: Long): Boolean = !stopped && retryAt?.let { now >= it } == true

    /**
     * The session starts a connection attempt. [byUser] (pull to refresh, app opened) makes one attempt now; if it
     * fails while an alarm retry is pending, the step and that alarm stay as they were. Returns false while [stopped].
     */
    @Synchronized
    fun onAttempt(byUser: Boolean = false): Boolean {
        if (stopped) return false
        userAttempt = byUser
        savedRetryAt = if (byUser) retryAt else null
        retryAt = null
        idleSince = null
        return true
    }

    /** IDLE `+` received on the current connection. */
    @Synchronized
    fun onIdle(now: Long) {
        if (idleSince == null) idleSince = now
    }

    /** A refresh or a poll succeeded. */
    @Synchronized
    fun markHealthy() {
        step = 0
    }

    /** The connection attempt or the connection failed. Returns null when there is no automatic retry. */
    @Synchronized
    fun onFailure(now: Long, kind: Kind): Retry? {
        val since = idleSince
        if (since != null && now - since >= HEALTHY_AFTER_MS) step = 0
        idleSince = null
        val saved = savedRetryAt.takeIf { userAttempt }
        userAttempt = false
        savedRetryAt = null
        if (kind == Kind.AUTH) {
            stopped = true
            retryAt = null
            return null
        }
        if (saved != null && step > 0) {
            retryAt = saved
            return Retry(saved, inProcess = false)
        }
        val index = when (kind) {
            Kind.THROTTLED -> maxOf(step, FIRST_ALARM)
            Kind.TLS -> maxOf(step, FIRST_TLS)
            else -> step
        }.coerceAtMost(STEPS_MS.lastIndex)
        step = index + 1
        if (index < FIRST_ALARM) {
            retryAt = null
            return Retry(now + STEPS_MS[index], inProcess = true)
        }
        val delay = (STEPS_MS[index] * (1 - JITTER + 2 * JITTER * random.nextDouble())).toLong()
        return Retry(now + delay, inProcess = false).also { retryAt = it.at }
    }

    /**
     * The default network is now [networkHandle] (`Network.getNetworkHandle()`). Only the first call for each new
     * network resets the step and the pending alarm, and returns true. AUTH stays stopped.
     */
    @Synchronized
    fun onNetworkChanged(networkHandle: Long): Boolean {
        if (networkHandle == network) return false
        network = networkHandle
        step = 0
        retryAt = null
        return true
    }

    /** The user signed in again or changed the account. */
    @Synchronized
    fun reset() {
        step = 0
        retryAt = null
        stopped = false
        idleSince = null
        userAttempt = false
        savedRetryAt = null
    }

    private companion object {
        val STEPS_MS = longArrayOf(
            0, 5 * SECOND_MS, 20 * SECOND_MS,
            2 * MINUTE_MS, 5 * MINUTE_MS, 15 * MINUTE_MS, 30 * MINUTE_MS, 60 * MINUTE_MS,
        )
        const val FIRST_ALARM = 3
        const val FIRST_TLS = 5
        const val JITTER = 0.2
        const val HEALTHY_AFTER_MS = 2 * MINUTE_MS
    }
}
