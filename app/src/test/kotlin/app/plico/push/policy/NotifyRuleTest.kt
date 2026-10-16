package app.plico.push.policy

import app.plico.push.policy.NotifyRule.Candidate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifyRuleTest {
    private val now = 1_800_000_000_000L
    private val base = Candidate(
        fromNewUidFetch = true,
        uidNextBefore = 120,
        seen = false,
        date = now - HOUR,
        inNotifyScope = true,
        categoryPasses = true,
    )

    @Test
    fun insertedNotifiedExceptNewUidsOfASyncedFolder() {
        assertFalse(NotifyRule.insertNotified(fromNewUidFetch = true, uidNextBefore = 120))
        assertTrue(NotifyRule.insertNotified(fromNewUidFetch = true, uidNextBefore = 0), "first sync")
        assertTrue(NotifyRule.insertNotified(fromNewUidFetch = false, uidNextBefore = 120), "backfill, search, flags")
    }

    @Test
    fun newUnseenRecentMailInScopeNotifies() {
        assertTrue(NotifyRule.shouldNotify(base, now))
        assertTrue(NotifyRule.shouldNotify(base.copy(date = now + HOUR), now), "a future date still notifies")
    }

    @Test
    fun everyConditionIsRequired() {
        for (candidate in listOf(
            base.copy(fromNewUidFetch = false),
            base.copy(uidNextBefore = 0),
            base.copy(seen = true),
            base.copy(inNotifyScope = false),
            base.copy(categoryPasses = false),
        )) {
            assertFalse(NotifyRule.shouldNotify(candidate, now), candidate.toString())
        }
    }

    @Test
    fun sevenDayLimit() {
        assertTrue(NotifyRule.shouldNotify(base.copy(date = now - 7 * 24 * HOUR), now))
        assertFalse(NotifyRule.shouldNotify(base.copy(date = now - 7 * 24 * HOUR - 1), now))
    }

    @Test
    fun riseupCandidateDefaultsToCategoryPass() {
        val riseup = Candidate(
            fromNewUidFetch = true, uidNextBefore = 5, seen = false, date = now, inNotifyScope = true,
        )
        assertTrue(NotifyRule.shouldNotify(riseup, now))
    }
}
