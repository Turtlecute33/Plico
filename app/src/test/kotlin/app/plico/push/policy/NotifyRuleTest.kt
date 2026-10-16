package app.plico.push.policy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The rest of the rule is SQL; MessageDaoTest (instrumented) checks it.
class NotifyRuleTest {
    @Test
    fun insertedNotifiedExceptNewUidsOfASyncedFolder() {
        assertFalse(NotifyRule.insertNotified(fromNewUidFetch = true, uidNextBefore = 120))
        assertTrue(NotifyRule.insertNotified(fromNewUidFetch = true, uidNextBefore = 0), "first sync")
        assertTrue(NotifyRule.insertNotified(fromNewUidFetch = false, uidNextBefore = 120), "backfill, search, flags")
    }
}
