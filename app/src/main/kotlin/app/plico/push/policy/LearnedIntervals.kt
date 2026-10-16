package app.plico.push.policy

/** Minimal string storage. The app backs it with `Prefs.getString` / `Prefs.putString`. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * A learned client keepalive interval. [lockedAt] is the elapsedRealtime of the last failure; a locked interval
 * never grows. The lock ends 24 h after [lockedAt], or at a reboot that puts the clock below [lockedAt].
 */
data class LearnedInterval(val minutes: Int, val lockedAt: Long? = null) {
    fun isLocked(now: Long): Boolean = lockedAt != null && now - lockedAt in 0 until LOCK_MS

    companion object {
        const val LOCK_MS = 24 * HOUR_MS
    }
}

/**
 * Learned client intervals by key (DESIGN.md §5 Prefs, §8.3): at most [MAX_KEYS], the least recently used is
 * dropped first. Persisted in one [KeyValueStore] entry as `key=minutes[@lockedAt];…`, oldest first.
 * Thread-safe; the sessions of all accounts share one instance.
 */
class LearnedIntervals(private val store: KeyValueStore) {
    private val map by lazy { decode(store.get(STORE_KEY)) }

    /** Reading marks the key as used. */
    @Synchronized
    operator fun get(key: String): LearnedInterval? = map[key]

    @Synchronized
    operator fun set(key: String, value: LearnedInterval) {
        require(key.isNotEmpty() && key.none { it in RESERVED }) { "Invalid key" }
        map[key] = value
        trim(map)
        store.put(STORE_KEY, encode(map))
    }

    companion object {
        const val STORE_KEY = "learned_keepalive"
        const val MAX_KEYS = 16
        private const val RESERVED = "=@;"

        internal fun encode(map: Map<String, LearnedInterval>): String =
            map.entries.joinToString(";") { (key, value) ->
                "$key=${value.minutes}" + (value.lockedAt?.let { "@$it" } ?: "")
            }

        /** Skips malformed entries, so a damaged or older value never breaks push. */
        internal fun decode(text: String?): LinkedHashMap<String, LearnedInterval> {
            val map = LinkedHashMap<String, LearnedInterval>(MAX_KEYS, 0.75f, true)
            text?.split(';')?.forEach { entry ->
                val key = entry.substringBefore('=', "")
                val value = entry.substringAfter('=', "")
                val minutes = value.substringBefore('@').toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val lockedAt = if ('@' in value) value.substringAfter('@').toLongOrNull() ?: return@forEach else null
                if (key.isNotEmpty()) map[key] = LearnedInterval(minutes, lockedAt)
            }
            trim(map)
            return map
        }

        // Access-ordered map: the first key is the least recently used.
        private fun trim(map: MutableMap<String, LearnedInterval>) {
            while (map.size > MAX_KEYS) map.remove(map.keys.first())
        }
    }
}
