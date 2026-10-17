package app.plico.push.policy

/**
 * The UIDNEXT check before IDLE (DESIGN.md §7.1 step 6; push PB2): mail that arrived after this cycle's new-mail
 * fetch gets no IDLE event, so a higher UIDNEXT runs the fast path again.
 */
object UidNextCheck {
    /**
     * [expected]: `folder.uidnext` of INBOX after this cycle (servers other than Gmail); Gmail [gmailExpected].
     * 0 = no baseline yet (first sync), which never triggers.
     */
    fun missedMail(serverUidNext: Long, expected: Long): Boolean = expected > 0 && serverUidNext > expected

    /** Gmail: INBOX UIDNEXT at the last check plus the new `\Inbox` messages of this cycle; 0 without a baseline. */
    fun gmailExpected(lastInboxUidNext: Long, newInboxMessages: Int): Long =
        if (lastInboxUidNext > 0) lastInboxUidNext + newInboxMessages else 0
}
