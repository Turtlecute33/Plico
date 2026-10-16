package app.plico.ui.components

import androidx.compose.material3.SnackbarHostState
import app.plico.sync.MailActions
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoTest {
    @Test
    fun undoEndsOneSecondBeforeTheChangeRuns() {
        val shown = MailActions.UNDO_MS - 1_000
        assertEquals(shown, UndoWindow.actionLeft(since = 10_000, now = 10_000))
        assertEquals(shown - 2_500, UndoWindow.actionLeft(since = 10_000, now = 12_500))
        // A notice that waited (the message screen closed over Settings): no Undo any more.
        assertTrue(UndoWindow.actionLeft(since = 10_000, now = 10_000 + shown) <= 0)
        assertEquals(4_000, UndoWindow.sendMs(5))
        assertTrue(UndoWindow.sendMs(0) <= 0)
    }

    @Test
    fun theSnackbarGoesWhenItsTimeIsOver() = runTest {
        val host = SnackbarHostState()
        val tapped = async { host.showUndo("Archived", "Undo", 3_000) }
        runCurrent()
        assertEquals("Archived", host.currentSnackbarData?.visuals?.message)
        // Indefinite: no accessibility timeout keeps it after the change ran.
        advanceTimeBy(2_999)
        runCurrent()
        assertNotNull(host.currentSnackbarData)
        advanceTimeBy(2)
        runCurrent()
        assertNull(host.currentSnackbarData)
        assertFalse(tapped.await())
    }

    @Test
    fun aTapOnUndoIsReported() = runTest {
        val host = SnackbarHostState()
        val tapped = async { host.showUndo("Archived", "Undo", 3_000) }
        runCurrent()
        host.currentSnackbarData!!.performAction()
        assertTrue(tapped.await())
    }

    @Test
    fun nothingShowsWhenTheTimeIsOverAlready() = runTest {
        val host = SnackbarHostState()
        assertFalse(host.showUndo("Archived", "Undo", 0))
        assertNull(host.currentSnackbarData)
    }
}
