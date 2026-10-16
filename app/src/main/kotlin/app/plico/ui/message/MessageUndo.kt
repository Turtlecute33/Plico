package app.plico.ui.message

import androidx.annotation.StringRes
import app.plico.sync.Undo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** An undoable action that closed the message screen, e.g. "Archived · Undo". */
data class UndoNotice(@StringRes val text: Int, val undo: Undo)

/**
 * Hands the Undo of Archive, Delete, Move and Spam from the message screen to the screen below it, which
 * shows the snackbar after Back (DESIGN §7.4, §13).
 */
object MessageUndo {
    private val pending = MutableStateFlow<UndoNotice?>(null)

    /** The notice waiting for a snackbar; collect it and call [take]. */
    val notice: StateFlow<UndoNotice?> = pending.asStateFlow()

    fun post(notice: UndoNotice) {
        pending.value = notice
    }

    /** The waiting notice, at most once. */
    fun take(): UndoNotice? = pending.getAndUpdate { null }
}
