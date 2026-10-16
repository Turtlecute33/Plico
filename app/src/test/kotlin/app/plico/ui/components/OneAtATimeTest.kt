package app.plico.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneAtATimeTest {
    @Test
    fun aSecondTapWhileADraftIsPreparedDoesNothing() = runTest {
        val gate = OneAtATime(this)
        val copied = CompletableDeferred<Unit>()
        var opened = 0
        assertTrue(gate.run { copied.await(); opened++ })
        runCurrent()
        assertTrue(gate.busy.value)
        // The double tap on a server draft: no second copy, no second Compose screen.
        assertFalse(gate.run { opened++ })
        copied.complete(Unit)
        runCurrent()
        assertFalse(gate.busy.value)
        assertEquals(1, opened)
        assertTrue(gate.run { opened++ })
        runCurrent()
        assertEquals(2, opened)
    }
}
