package app.plico.push.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearnedIntervalsTest {
    private val store = MemoryStore()

    @Test
    fun roundTripThroughTheStore() {
        val first = LearnedIntervals(store)
        first["gmail|wifi:0a1b2c3d4e5f"] = LearnedInterval(11)
        first["gmail|cellular"] = LearnedInterval(7, lockedAt = 123_456_789)
        first["riseup|other"] = LearnedInterval(15, lockedAt = 0)
        assertEquals(
            "gmail|wifi:0a1b2c3d4e5f=11;gmail|cellular=7@123456789;riseup|other=15@0",
            store.values[LearnedIntervals.STORE_KEY],
        )

        val second = LearnedIntervals(store)
        assertEquals(LearnedInterval(11), second["gmail|wifi:0a1b2c3d4e5f"])
        assertEquals(LearnedInterval(7, 123_456_789), second["gmail|cellular"])
        assertEquals(LearnedInterval(15, 0), second["riseup|other"])
        assertNull(second["gmail|other"])
    }

    @Test
    fun overwriteKeepsOneEntry() {
        val learned = LearnedIntervals(store)
        learned["a"] = LearnedInterval(9)
        learned["a"] = LearnedInterval(11)
        assertEquals("a=11", store.values[LearnedIntervals.STORE_KEY])
    }

    @Test
    fun leastRecentlyUsedKeyIsDropped() {
        val learned = LearnedIntervals(store)
        repeat(16) { learned["k$it"] = LearnedInterval(9) }
        learned["k0"]
        learned["k16"] = LearnedInterval(9)
        assertEquals(LearnedInterval(9), learned["k0"], "k0 was read, so k1 is the oldest")
        assertNull(learned["k1"])

        val persisted = store.values.getValue(LearnedIntervals.STORE_KEY).split(';').map { it.substringBefore('=') }
        assertEquals(16, persisted.size)
        assertFalse("k1" in persisted)
        assertEquals("k16", persisted.last())
    }

    @Test
    fun readsAloneDoNotWrite() {
        val learned = LearnedIntervals(store)
        learned["a"] = LearnedInterval(9)
        val writes = store.writes
        repeat(10) { learned["a"] }
        learned["missing"]
        assertEquals(writes, store.writes)
    }

    @Test
    fun loadsLazily() {
        store.values[LearnedIntervals.STORE_KEY] = "a=9"
        val learned = LearnedIntervals(store)
        store.values[LearnedIntervals.STORE_KEY] = "a=13"
        assertEquals(13, learned["a"]?.minutes)
    }

    @Test
    fun malformedEntriesAreSkipped() {
        val map = LearnedIntervals.decode("a=;=5;b=x;c=7@;d=9@12;e=11;f=0;g=-3;h;i=13@x;j=15@-4")
        assertEquals(
            mapOf("d" to LearnedInterval(9, 12), "e" to LearnedInterval(11), "j" to LearnedInterval(15, -4)),
            map,
        )
        assertTrue(LearnedIntervals.decode(null).isEmpty())
        assertTrue(LearnedIntervals.decode("").isEmpty())
    }

    @Test
    fun decodeKeepsAtMostSixteenNewest() {
        val text = (0 until 20).joinToString(";") { "k$it=9" }
        val map = LearnedIntervals.decode(text)
        assertEquals((4 until 20).map { "k$it" }, map.keys.toList())
    }

    @Test
    fun keysWithSeparatorsAreRejected() {
        val learned = LearnedIntervals(store)
        for (key in listOf("", "a=b", "a;b", "a@b")) {
            assertFailsWith<IllegalArgumentException> { learned[key] = LearnedInterval(9) }
        }
        assertNull(store.values[LearnedIntervals.STORE_KEY])
    }

    @Test
    fun lockLastsTwentyFourHours() {
        val interval = LearnedInterval(7, lockedAt = 10 * HOUR)
        assertTrue(interval.isLocked(10 * HOUR))
        assertTrue(interval.isLocked(34 * HOUR - 1))
        assertFalse(interval.isLocked(34 * HOUR))
        assertFalse(interval.isLocked(HOUR), "the clock restarted: a reboot ends the lock")
        assertFalse(LearnedInterval(7).isLocked(0))
    }
}
