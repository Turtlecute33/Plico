package app.plico.ui.lock

import android.os.SystemClock
import app.plico.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App lock state (DESIGN §12). When the user turned the lock on, the UI is locked at process start and
 * after it was in the background for at least the timeout. It hides only the UI; sync goes on.
 * MainActivity reports [onForeground] and [onBackground]; [LockScreen] calls [unlock].
 */
class AppLock(
    private val isEnabled: () -> Boolean,
    private val timeoutMinutes: () -> Int,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(prefs: Prefs) : this({ prefs.appLock }, { prefs.appLockTimeoutMinutes })

    private val state = MutableStateFlow(isEnabled())

    /** elapsedRealtime when the unlocked UI left the screen; null while visible or locked. */
    private var backgroundAt: Long? = null

    /** True while the UI must stay covered. */
    val locked: StateFlow<Boolean> = state.asStateFlow()

    /** The user turned the app lock on; notifications then hide their content (DESIGN §9). */
    val enabled: Boolean get() = isEnabled()

    /** The UI left the screen (Activity.onStop, but not for a configuration change). */
    fun onBackground() {
        if (!state.value) backgroundAt = clock()
    }

    /** The UI comes back (Activity.onStart): lock when the timeout has passed. */
    fun onForeground() {
        val since = backgroundAt
        backgroundAt = null
        state.value = when {
            !isEnabled() -> false
            since == null -> state.value
            else -> isDue(timeoutMinutes(), since, clock())
        }
    }

    /** The user passed the system prompt. */
    fun unlock() {
        backgroundAt = null
        state.value = false
    }

    companion object {
        /** The timing rule: lock after at least [timeoutMinutes] away (0 = at once). Times are elapsedRealtime. */
        fun isDue(timeoutMinutes: Int, backgroundAt: Long, now: Long): Boolean =
            now - backgroundAt >= timeoutMinutes.coerceAtLeast(0) * 60_000L
    }
}
