package app.plico.push.policy

import app.plico.push.policy.NetworkRules.Action
import app.plico.push.policy.NetworkRules.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallRulesTest {

    @Test
    fun networkLostParksEverySession() {
        assertEquals(Action.PARK, NetworkRules.action(Event.LOST, 1, null, wantsConnection = true))
        assertEquals(Action.PARK, NetworkRules.action(Event.LOST, null, null, wantsConnection = false))
    }

    @Test
    fun newDefaultNetworkReconnectsOnlySessionsOnAnotherNetwork() {
        assertEquals(Action.RECONNECT, NetworkRules.action(Event.DEFAULT_CHANGED, 1, 2, wantsConnection = true))
        assertEquals(Action.NONE, NetworkRules.action(Event.DEFAULT_CHANGED, 2, 2, wantsConnection = true))
        assertEquals(Action.RECONNECT, NetworkRules.action(Event.DEFAULT_CHANGED, null, 2, wantsConnection = true))
        assertEquals(Action.NONE, NetworkRules.action(Event.DEFAULT_CHANGED, null, 2, wantsConnection = false))
        assertEquals(Action.RECONNECT, NetworkRules.action(Event.DEFAULT_CHANGED, 1, 2, wantsConnection = false))
    }

    @Test
    fun validatedReconnectsOnlySessionsThatAreNotConnected() {
        assertEquals(Action.NONE, NetworkRules.action(Event.VALIDATED, 2, 2, wantsConnection = true))
        assertEquals(Action.RECONNECT, NetworkRules.action(Event.VALIDATED, null, 2, wantsConnection = true))
        assertEquals(Action.NONE, NetworkRules.action(Event.VALIDATED, null, 2, wantsConnection = false))
    }

    @Test
    fun uidNextCheck() {
        assertTrue(UidNextCheck.missedMail(serverUidNext = 101, expected = 100))
        assertFalse(UidNextCheck.missedMail(serverUidNext = 100, expected = 100))
        assertFalse(UidNextCheck.missedMail(serverUidNext = 500, expected = 0), "no baseline yet")
        assertEquals(53, UidNextCheck.gmailExpected(lastInboxUidNext = 50, newInboxMessages = 3))
        assertEquals(0, UidNextCheck.gmailExpected(lastInboxUidNext = 0, newInboxMessages = 3))
        assertFalse(UidNextCheck.missedMail(53, UidNextCheck.gmailExpected(50, 3)))
        assertTrue(UidNextCheck.missedMail(54, UidNextCheck.gmailExpected(50, 3)))
    }

    @Test
    fun dueTimesMin() {
        assertNull(DueTimes.min())
        assertNull(DueTimes.min(null, null))
        assertEquals(3, DueTimes.min(null, 7, 3, null, 9))
        assertEquals(4, DueTimes.min(listOf(4L, null)))
        assertNull(DueTimes.min(emptyList()))
    }

    @Test
    fun alarmAtAddsTheNoNetworkBackstop() {
        val now = 10 * HOUR
        val dues = listOf(null, now + 5 * MIN, now + 40 * MIN)
        assertEquals(now + 5 * MIN, DueTimes.alarmAt(now, dues, hasNetwork = true))
        assertNull(DueTimes.alarmAt(now, listOf(null), hasNetwork = true))
        assertEquals(now + 30 * MIN, DueTimes.alarmAt(now, emptyList(), hasNetwork = false))
        assertEquals(now + 30 * MIN, DueTimes.alarmAt(now, listOf(now + 2 * HOUR), hasNetwork = false))
        assertEquals(now + 5 * MIN, DueTimes.alarmAt(now, listOf(now + 5 * MIN), hasNetwork = false))
    }
}
