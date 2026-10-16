package app.plico.push.policy

/**
 * The new-mail notification rule (DESIGN.md §9; simplicity EM2, push PB21). Rows are inserted with
 * `notified = 1`, except rows from the step-4 new-UID fetch of a folder that was synced before (its `uidnext`
 * was above 0 before this sync, so not a first sync and not after a UIDVALIDITY reset). The rest of the rule
 * (unseen, not older than [MAX_AGE_MS], in a notify scope) is the SQL of `MessageDao.claimNotifications`.
 */
object NotifyRule {
    const val MAX_AGE_MS = 7 * 24 * HOUR_MS

    /** The `notified` value of a new row. */
    fun insertNotified(fromNewUidFetch: Boolean, uidNextBefore: Long): Boolean =
        !(fromNewUidFetch && uidNextBefore > 0)
}
