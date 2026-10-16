package app.plico.push.policy

/**
 * The new-mail notification rule (DESIGN.md §9; simplicity EM2, push PB21). Rows are inserted with
 * `notified = 1`, except rows from the step-4 new-UID fetch of a folder that was synced before (its `uidnext`
 * was above 0 before this sync, so not a first sync and not after a UIDVALIDITY reset). After [shouldNotify],
 * the caller sets `notified = 1` on every evaluated row.
 */
object NotifyRule {
    const val MAX_AGE_MS = 7 * 24 * HOUR_MS

    data class Candidate(
        /** The row was inserted by the step-4 new-UID fetch. */
        val fromNewUidFetch: Boolean,
        /** `folder.uidnext` before this sync (Gmail: All Mail). */
        val uidNextBefore: Long,
        val seen: Boolean,
        /** INTERNALDATE, epoch ms. */
        val date: Long,
        /** Riseup: INBOX or `folder.notify = 1`. Gmail: the `\Inbox` label or a label view with `notify = 1`. */
        val inNotifyScope: Boolean,
        /** Gmail category filter passed; true when every category is enabled, and for Riseup. */
        val categoryPasses: Boolean = true,
    )

    /** The `notified` value of a new row. */
    fun insertNotified(fromNewUidFetch: Boolean, uidNextBefore: Long): Boolean =
        !(fromNewUidFetch && uidNextBefore > 0)

    /** [nowMs] is wall-clock time (System.currentTimeMillis), like [Candidate.date]. */
    fun shouldNotify(candidate: Candidate, nowMs: Long): Boolean = with(candidate) {
        !insertNotified(fromNewUidFetch, uidNextBefore) &&
            !seen &&
            date >= nowMs - MAX_AGE_MS &&
            inNotifyScope &&
            categoryPasses
    }
}
