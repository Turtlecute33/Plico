package app.plico.push.policy

import kotlin.random.Random

const val SEC = 1_000L
const val MIN = 60 * SEC
const val HOUR = 60 * MIN

class MemoryStore : KeyValueStore {
    val values = HashMap<String, String>()
    var writes = 0

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String?) {
        writes++
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** A Random whose nextDouble() is always [value]. */
class FixedRandom(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextDouble(): Double = value
}
