package app.plico.ui.components

import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import app.plico.sync.MailActions
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long Undo snackbars stay (DESIGN §7.4, §7.5). The change or the send runs when its delay ends, so the
 * snackbar ends [MARGIN_MS] before: an Undo the user can tap always still works. The accessibility timeout
 * of Short and Long snackbars does not apply; it could keep Undo on screen after the change ran.
 */
object UndoWindow {
    const val MARGIN_MS = 1_000L

    /** What is left of the Undo of an archive, delete, move or spam that started at [since] (elapsedRealtime). */
    fun actionLeft(since: Long, now: Long = SystemClock.elapsedRealtime()): Long =
        MailActions.UNDO_MS - MARGIN_MS - (now - since)

    /** The Undo time of a send that waits [undoSeconds] (Prefs.undoSendSeconds). */
    fun sendMs(undoSeconds: Int): Long = undoSeconds * 1_000L - MARGIN_MS
}

/**
 * Shows [message] with [undoLabel] for at most [visibleMs], then dismisses it; the wait for an earlier
 * snackbar counts. Nothing shows when [visibleMs] is not positive. True when the user tapped Undo.
 */
suspend fun SnackbarHostState.showUndo(message: String, undoLabel: String, visibleMs: Long): Boolean {
    if (visibleMs <= 0) return false
    val result = withTimeoutOrNull(visibleMs) {
        showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
    }
    return result == SnackbarResult.ActionPerformed
}
