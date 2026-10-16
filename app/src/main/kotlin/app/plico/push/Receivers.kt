package app.plico.push

import android.content.BroadcastReceiver
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [block] off the main thread (goAsync). The broadcast finishes when [block] is done, or after 9 s
 * even if it is not, so that the receiver never runs into the broadcast timeout.
 */
internal fun BroadcastReceiver.runAsync(engine: PushEngine, block: suspend () -> Unit) {
    val pending = goAsync()
    val work = engine.launch { block() }
    engine.launch {
        try {
            withTimeoutOrNull(FINISH_MS) { work.join() }
        } finally {
            pending.finish()
        }
    }
}

private const val FINISH_MS = 9_000L
