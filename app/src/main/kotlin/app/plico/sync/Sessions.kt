package app.plico.sync

import app.plico.PlicoApp
import java.util.concurrent.ConcurrentHashMap

/** Registry of [AccountSession]s; the push engine is their host. */
class Sessions(private val app: PlicoApp) {
    private val sessions = ConcurrentHashMap<Long, AccountSession>()

    /**
     * True while the UI is visible; set by the push engine. Sessions then stay connected for 2 min after work,
     * run the backfill on any network, and the change to visible syncs every account (a user action). The change
     * to hidden ends those 2 min.
     */
    @Volatile
    var appVisible: Boolean = false
        set(value) {
            val shown = value && !field
            val hidden = !value && field
            field = value
            if (shown) all().forEach { it.kick(AccountSession.Reason.USER_REFRESH) }
            if (hidden) all().forEach(AccountSession::onHidden)
        }

    /** The session of [accountId], created on first use. */
    operator fun get(accountId: Long): AccountSession {
        sessions[accountId]?.let { return it }
        val host = app.push // outside computeIfAbsent: the engine may create sessions while it starts
        return sessions.computeIfAbsent(accountId) { AccountSession(app, it, host) }
    }

    fun all(): List<AccountSession> = sessions.values.toList()

    /** Closes and forgets the session (account removed). */
    fun remove(accountId: Long) {
        sessions.remove(accountId)?.close()
    }
}
