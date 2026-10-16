package app.plico.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs one job at a time in [scope]: a second tap while a draft is being prepared does nothing, so that it
 * cannot open a second Compose screen or copy a server draft twice. [busy] drives the progress indicator.
 */
class OneAtATime(private val scope: CoroutineScope) {
    private val running = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = running.asStateFlow()

    /** Starts [block] unless a job runs; false when it was ignored. */
    fun run(block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(expect = false, update = true)) return false
        scope.launch {
            try {
                block()
            } finally {
                running.value = false
            }
        }
        return true
    }
}
