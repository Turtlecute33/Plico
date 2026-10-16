package app.plico.sync

import app.plico.PlicoApp

/** Registry of [AccountSession]s; the push engine is their host. Owner: sync-engine agent. */
class Sessions(private val app: PlicoApp) {
    /** The session of [accountId], created on first use. */
    operator fun get(accountId: Long): AccountSession = TODO()

    fun all(): List<AccountSession> = TODO()

    /** Closes and forgets the session (account removed). */
    fun remove(accountId: Long): Unit = TODO()
}
