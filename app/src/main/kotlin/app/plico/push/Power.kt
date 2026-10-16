package app.plico.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.plico.sync.WakeHolder
import java.util.concurrent.ConcurrentHashMap

/**
 * Partial wake locks (DESIGN §8.3, push PB4, apis AF17). Each holder owns one non-refcounted lock, so
 * [WakeHolder.renew] extends it and [WakeHolder.release] is idempotent. Every acquire has a timeout, and
 * no call throws.
 */
class Power(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val holders = ConcurrentHashMap<String, Holder>()

    /** The holder for [tag], e.g. `sync:3`, `alarm` or `net`. Tags never contain an address. */
    fun holder(tag: String): WakeHolder = holders.computeIfAbsent(tag) { Holder(it, pm) }

    internal class Holder(private val tag: String, pm: PowerManager) : WakeHolder {
        private val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plico:$tag").apply {
            setReferenceCounted(false)
        }

        internal val isHeld: Boolean get() = lock.isHeld

        // A non-refcounted acquire replaces the pending timeout, so this is also the renewal.
        override fun renew(timeoutMs: Long) {
            try {
                lock.acquire(timeoutMs)
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "wake lock $tag: ${e.javaClass.simpleName}")
            }
        }

        override fun release() {
            try {
                if (lock.isHeld) lock.release()
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "wake lock $tag: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** Log tag of the push and notification code. Logs carry class names only (DESIGN §12). */
internal const val LOG_TAG = "Plico"
