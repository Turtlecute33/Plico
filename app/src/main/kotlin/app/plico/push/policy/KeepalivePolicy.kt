package app.plico.push.policy

import app.plico.data.Provider
import app.plico.push.policy.Coalescing.Kind.REFRESH
import app.plico.push.policy.Coalescing.Timing.EARLY
import app.plico.push.policy.Coalescing.Timing.LATER
import java.util.Locale

/**
 * Keepalive decisions for one account session (DESIGN.md §8.3; push PB11, PB12, PB13, PB17).
 * Thread-safe; every time is a SystemClock.elapsedRealtime() value.
 *
 * - Server mode: the server sends keepalives (`* OK Still here`) at cadence c, the median of the last 5 gaps
 *   between consecutive keepalives of one IDLE, and c is below the client interval. A dead-peer check runs only
 *   when a keepalive is missing; a re-IDLE runs in a keepalive wake once IDLE is 25 min old. Two dead-peer checks
 *   in a row that find the socket alive switch to client mode and start a new measurement.
 * - Client mode: refresh at `idleStart + interval`. The interval is learned per network key in [learned]:
 *   +2 min after 3 alive refreshes in a row that were not early (max 28), −2 min (min 5) and locked on a failure.
 *   A locked interval never grows. Each network key has its own lock, which ends after 24 h. A failure counts
 *   only at the next IDLE on the same network: a socket that died with its network (Wi-Fi gone, airplane mode)
 *   says nothing about the NAT timeout, and the network event comes only after the 2 s debounce.
 * - OAuth/device sessions: the session expiry is a due time, and a refresh less than 5 min before it becomes a
 *   reconnect.
 *
 * The session calls [onIdleStarted] at each IDLE `+`, [onKeepalive] for each keepalive, [onIdleEnded] when it
 * leaves IDLE itself (DONE, or it closes the socket), [onServerClosed] when the server ends the connection during
 * IDLE, [decide] at every wake, [onChecked] with the result of each check that [decide] or [onKeepalive]
 * asked for, and [onNetworkChanged] for every network event and for a connection on another network.
 */
class KeepalivePolicy(
    /** First client interval in minutes: `Providers.Traits.idleStartMinutes` (Gmail 9, other servers 15). */
    private val startMinutes: Int,
    /** Keeps the learned intervals of different servers apart: [scopeOf]. */
    val scope: String,
    private val learned: LearnedIntervals,
) {
    enum class Mode { SERVER, CLIENT }

    /** What the session does now. Every check is DONE → tagged OK (20 s) → IDLE → `+`. */
    enum class Action {
        NONE,

        /** Client mode: the refresh is due. */
        REFRESH,

        /** Client mode: the refresh is due within the coalescing window. Never counts toward growth. */
        EARLY_REFRESH,

        /** Server mode: a keepalive is missing. */
        DEAD_PEER_CHECK,

        /** Server mode: IDLE is 25 min old; re-IDLE in this keepalive wake. */
        RE_IDLE,

        /** LOGOUT and connect again with a fresh token. */
        RECONNECT,
    }

    private var networkKey: String? = null
    private var pendingFailure: PendingFailure? = null
    private var idleStart: Long? = null
    private var expiresAt: Long? = null
    private var lastKeepalive: Long? = null
    private val gaps = ArrayDeque<Long>()
    private var aliveStreak = 0
    private var aliveDeadPeerChecks = 0

    /** Measured server keepalive cadence; null until measured. */
    val cadenceMs: Long?
        @Synchronized get() = cadence()

    /** Client interval for the current network. */
    val intervalMs: Long
        @Synchronized get() = interval()

    val mode: Mode
        @Synchronized get() = mode(interval())

    /** IDLE `+` received. [expiresAt] = `Credentials.expiresAt` of this connection. */
    @Synchronized
    fun onIdleStarted(now: Long, networkKey: String, expiresAt: Long?) {
        pendingFailure?.let { if (it.key == networkKey) fail(it.at, it.capMs) }
        pendingFailure = null
        if (networkKey != this.networkKey) {
            this.networkKey = networkKey
            aliveStreak = 0
        }
        idleStart = now
        this.expiresAt = expiresAt
    }

    @Synchronized
    fun onIdleEnded() {
        idleStart = null
    }

    /** The network changed or lost its connectivity: a failure that is not counted yet does not count. */
    @Synchronized
    fun onNetworkChanged() {
        pendingFailure = null
    }

    /** A server keepalive arrived. Returns [Action.RE_IDLE] when the session should re-IDLE now. */
    @Synchronized
    fun onKeepalive(now: Long): Action {
        val start = idleStart
        val previous = lastKeepalive
        if (start != null && previous != null && previous >= start) {
            gaps.addLast(now - previous)
            if (gaps.size > GAPS) gaps.removeFirst()
        }
        lastKeepalive = now
        aliveDeadPeerChecks = 0
        return if (start != null && mode(interval()) == Mode.SERVER && now - start >= RE_IDLE_AFTER_MS) {
            Action.RE_IDLE
        } else {
            Action.NONE
        }
    }

    /**
     * The server sent BYE or closed the socket during IDLE. Not for closes the app caused, nor for the BYE that
     * ends an expired OAuth session. In client mode, a close at IDLE age ≥ 5 min without a network change is a
     * failure, and the interval is also capped at age − 1 min (counted at the next IDLE on this network).
     */
    @Synchronized
    fun onServerClosed(now: Long, networkChanged: Boolean) {
        val start = idleStart ?: return
        idleStart = null
        val age = now - start
        if (!networkChanged && age >= SERVER_CLOSE_MIN_AGE_MS && mode(interval()) == Mode.CLIENT) {
            failLater(now, capMs = age - MINUTE_MS)
        }
    }

    /**
     * Result of a check the session ran for [action]: [alive] = the tagged OK came within 20 s. A dead refresh
     * counts at the next IDLE on this network.
     */
    @Synchronized
    fun onChecked(action: Action, now: Long, alive: Boolean) {
        when (action) {
            Action.REFRESH -> if (alive) grow(now) else failLater(now)
            Action.EARLY_REFRESH -> if (!alive) failLater(now)
            Action.DEAD_PEER_CHECK -> when {
                !alive -> aliveDeadPeerChecks = 0
                ++aliveDeadPeerChecks >= LEAVE_SERVER_AFTER -> {
                    aliveDeadPeerChecks = 0
                    gaps.clear()
                }
            }
            Action.NONE, Action.RE_IDLE, Action.RECONNECT -> Unit
        }
        if (!alive) idleStart = null
    }

    /** The next time this session needs a wake: refresh or dead-peer check, or session expiry. Null when not idle. */
    @Synchronized
    fun nextDueAt(): Long? {
        val start = idleStart ?: return null
        val interval = interval()
        val check = serverCadence(interval)?.let { deadPeerDueAt(start, it) } ?: (start + interval)
        return DueTimes.min(check, expiresAt)
    }

    /** What to do at a wake at [now]. Refreshes and the expiry reconnect coalesce (see [Coalescing]). */
    @Synchronized
    fun decide(now: Long): Action {
        val start = idleStart ?: return Action.NONE
        val interval = interval()
        val expiry = expiresAt
        if (expiry != null && Coalescing.timing(now, expiry, interval, REFRESH) != LATER) return Action.RECONNECT
        serverCadence(interval)?.let { c ->
            return if (now >= deadPeerDueAt(start, c)) Action.DEAD_PEER_CHECK else Action.NONE
        }
        val timing = Coalescing.timing(now, start + interval, interval, REFRESH)
        return when {
            timing == LATER -> Action.NONE
            expiry != null && expiry - now < EXPIRY_MARGIN_MS -> Action.RECONNECT
            timing == EARLY -> Action.EARLY_REFRESH
            else -> Action.REFRESH
        }
    }

    private fun mode(interval: Long): Mode = if (serverCadence(interval) != null) Mode.SERVER else Mode.CLIENT

    /** The cadence when it selects server mode. */
    private fun serverCadence(interval: Long): Long? = cadence()?.takeIf { it < interval }

    // Upper median: with an even count the larger middle gap, so a false dead-peer alarm is less likely.
    private fun cadence(): Long? = if (gaps.size < MIN_GAPS) null else gaps.sorted()[gaps.size / 2]

    // Some servers (Dovecot, for example) align keepalives to the wall clock: the next one is due c after the later
    // of these two.
    private fun deadPeerDueAt(start: Long, c: Long): Long =
        maxOf(lastKeepalive ?: start, start) + c + maxOf(DEAD_PEER_MIN_MARGIN_MS, c / 4)

    private fun key(): String? = networkKey?.let { "$scope|$it" }

    private fun minutes(): Int = key()?.let { learned[it] }?.minutes?.coerceIn(MIN_MINUTES, MAX_MINUTES) ?: startMinutes

    private fun interval(): Long = minutes() * MINUTE_MS

    private fun grow(now: Long) {
        val key = key() ?: return
        if (learned[key]?.isLocked(now) == true) {
            aliveStreak = 0
            return
        }
        if (++aliveStreak < GROW_AFTER) return
        aliveStreak = 0
        val minutes = minutes()
        if (minutes < MAX_MINUTES) learned[key] = LearnedInterval(minOf(minutes + STEP_MINUTES, MAX_MINUTES))
    }

    private fun failLater(now: Long, capMs: Long? = null) {
        aliveStreak = 0
        pendingFailure = networkKey?.let { PendingFailure(it, now, capMs) }
    }

    private fun fail(now: Long, capMs: Long?) {
        aliveStreak = 0
        val key = key() ?: return
        var minutes = minutes() - STEP_MINUTES
        if (capMs != null) minutes = minOf(minutes, (capMs / MINUTE_MS).toInt())
        learned[key] = LearnedInterval(maxOf(minutes, MIN_MINUTES), lockedAt = now)
    }

    /** A failure on network [key] at [at], counted at the next IDLE on that network. */
    private class PendingFailure(val key: String, val at: Long, val capMs: Long?)

    companion object {
        private const val MIN_MINUTES = 5
        private const val MAX_MINUTES = 28
        private const val STEP_MINUTES = 2
        private const val GROW_AFTER = 3
        private const val GAPS = 5
        private const val MIN_GAPS = 2
        private const val LEAVE_SERVER_AFTER = 2
        private const val RE_IDLE_AFTER_MS = 25 * MINUTE_MS
        private const val DEAD_PEER_MIN_MARGIN_MS = 30 * SECOND_MS
        private const val SERVER_CLOSE_MIN_AGE_MS = 5 * MINUTE_MS
        private const val EXPIRY_MARGIN_MS = 5 * MINUTE_MS

        /** Characters that the scope must not contain (the separators of [LearnedIntervals] and of the key). */
        private const val NOT_IN_SCOPE = "=@;|"

        /**
         * The scope of an account's learned intervals: the provider id for Riseup and Gmail (their learned intervals
         * stay), else the IMAP host in lower case, so that two other servers never share one. Never the address.
         */
        fun scopeOf(provider: Provider, imapHost: String): String {
            if (provider == Provider.RISEUP || provider == Provider.GMAIL) return provider.id
            val host = imapHost.trim().lowercase(Locale.ROOT).filterNot { it in NOT_IN_SCOPE || it.isWhitespace() }
            return host.ifEmpty { provider.id }
        }
    }
}
