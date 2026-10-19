package app.plico.sync

import app.plico.account.MailError
import app.plico.account.MailErrors
import app.plico.mail.imap.ImapBadException
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapNoException
import java.io.IOException

/** How the op runner treats a failure (DESIGN.md §7.4, protocol PS14). */
internal enum class Failure {
    /** Try again later with backoff, for at most 24 h. */
    TRANSIENT,

    /** Drop the op and undo its local change. */
    PERMANENT,

    /** The target mailbox is missing: create it once and retry. */
    TRYCREATE,
}

/** A refusal decided by the app (size limit, a Gmail rule); [message] is the text for the user. */
internal class OpRefused(message: String) : Exception(message)

/** The SELECT returned another UIDVALIDITY than the folder row: the stored UIDs mean nothing any more. */
internal class UidValidityChanged : IOException("UIDVALIDITY changed")

internal object OpErrors {
    private val TRANSIENT_CODES = setOf("UNAVAILABLE", "INUSE", "SERVERBUG", "THROTTLED")
    private val BACKOFF_MS = longArrayOf(5_000, 30_000, 120_000, 600_000, 3_600_000)

    /** An op that still fails after this much backoff is dropped (see [givesUp]). */
    const val GIVE_UP_MS = 24 * 3_600_000L

    /** A send that failed this many times becomes failed (DESIGN.md §7.5). */
    const val MAX_SEND_ATTEMPTS = 6

    fun classify(t: Throwable): Failure = when (t) {
        is ImapNoException -> when {
            t.code?.name == "TRYCREATE" -> Failure.TRYCREATE
            // A bare [OVERQUOTA] (Riseup: the mailbox is full) stays permanent (DESIGN §7.4, protocol PS14).
            t.code?.name in TRANSIENT_CODES || MailErrors.isThrottleText(t.message.orEmpty()) -> Failure.TRANSIENT
            else -> Failure.PERMANENT
        }
        is ImapBadException, is OpRefused -> Failure.PERMANENT
        is MailError.Server, is MailError.Setup -> Failure.PERMANENT
        is MailError -> Failure.TRANSIENT
        // BYE, protocol errors, timeouts and resets: the connection is gone, the op is not wrong.
        is IOException -> Failure.TRANSIENT
        else -> Failure.PERMANENT
    }

    /**
     * True when [t] ends the connection (or the session must handle it), so the work cycle stops after the
     * op is rescheduled. A tagged NO or BAD refuses one command and keeps the connection. Other I/O errors
     * depend on the state of [client]: `:mail` closes it on every wire or protocol error, but an app error
     * such as a missing local file leaves it open.
     */
    fun endsConnection(t: Throwable, client: ImapClient): Boolean = when (t) {
        is ImapNoException, is ImapBadException, is UidValidityChanged -> false
        is MailError -> true
        is IOException -> !client.conn.isOpen
        else -> false
    }

    /** Delay before the next try, after [attempts] failed tries (>= 1): 5 s, 30 s, 2 min, 10 min, then 1 h. */
    fun backoffMs(attempts: Int): Long = BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /**
     * True when an op that failed [attempts] times has waited [GIVE_UP_MS] in backoff. Time without a try
     * (offline, sign-in needed) does not count, so a long wait alone never drops an op on its first failure.
     */
    fun givesUp(attempts: Int): Boolean = (1 until attempts).sumOf(::backoffMs) >= GIVE_UP_MS

    /** The server's text for a refusal, else a short name of the problem. */
    fun text(t: Throwable): String = t.message?.trim()?.takeIf { it.isNotEmpty() } ?: t.javaClass.simpleName
}
