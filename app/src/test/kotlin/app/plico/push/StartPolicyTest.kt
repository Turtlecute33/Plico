package app.plico.push

import app.plico.push.StartPolicy.Decision
import app.plico.push.StartPolicy.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class StartPolicyTest {
    private fun decide(
        source: Source,
        paused: Boolean = false,
        stopped: Boolean = false,
        firstStart: Boolean = true,
        userRequested: Boolean = true,
    ) = StartPolicy.decide(source, paused, stopped, firstStart) { userRequested }

    @Test
    fun taskManagerStopHoldsAgainstBackgroundStarts() {
        // An alarm or a sticky restart after the user closed the app from the Task Manager.
        assertEquals(Decision.STOPPED_BY_USER, decide(Source.BACKGROUND))
        assertEquals(Decision.RUN, decide(Source.BACKGROUND, userRequested = false))
    }

    @Test
    fun userAndBootStartsIgnoreTheLastExit() {
        for (source in listOf(Source.UI, Source.BOOT, Source.RESUME, Source.ACTION)) {
            assertEquals(Decision.RUN, StartPolicy.decide(source, false, false, true) { fail("exit reason read") })
        }
    }

    @Test
    fun onlyTheFirstStartOfAProcessReadsTheExitReason() {
        val decision = StartPolicy.decide(Source.BACKGROUND, false, false, firstStart = false) { fail("read") }
        assertEquals(Decision.RUN, decision)
    }

    @Test
    fun pausesWin() {
        for (source in Source.entries) {
            assertEquals(Decision.STOP, decide(source, paused = true))
            assertEquals(Decision.STOP, decide(source, stopped = true, userRequested = false))
        }
    }
}
