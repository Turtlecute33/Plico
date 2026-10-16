package app.plico.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The back stack (DESIGN §2, §13). A ViewModel, so it survives configuration changes but not process
 * death; then MainActivity starts again from the mailbox. Every route is in the stack at most once.
 */
class Navigator : ViewModel() {
    private val stack = mutableStateListOf<Route>()
    private var start: Job? = null

    /** Bottom first. Empty only until [startWith] has set the first route. */
    val backStack: List<Route> get() = stack

    val current: Route? get() = stack.lastOrNull()

    /** True after [back] on the last route: the activity should finish. */
    var finished by mutableStateOf(false)
        private set

    /** Sets the first route from [first] once per Navigator; later calls do nothing. */
    fun startWith(first: suspend () -> Route) {
        if (start == null && stack.isEmpty()) start = viewModelScope.launch { reset(first()) }
    }

    /** Opens [route]. When it is in the stack already, goes back to it instead. */
    fun go(route: Route) {
        val index = stack.indexOf(route)
        if (index < 0) stack += route else stack.removeRange(index + 1, stack.size)
    }

    /** Closes the top screen; on the last one, sets [finished]. */
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finished = true
    }

    /** Makes [route] the only screen, e.g. the mailbox after setup. */
    fun reset(route: Route) {
        stack.clear()
        stack += route
    }

    /** Replaces the top screen with [route], e.g. another folder of the mailbox. */
    fun replaceTop(route: Route) {
        stack.removeLastOrNull()
        go(route)
    }
}
