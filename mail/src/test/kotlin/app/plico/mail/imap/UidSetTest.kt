package app.plico.mail.imap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UidSetTest {

    @Test
    fun parseAndFormat() {
        assertEquals("1:5,8,10:12", UidSet.parse("1:5,8,10:12").toString())
        assertEquals("1:3,7", UidSet.parse("1,2,3,7").toString())
        assertEquals("1:8", UidSet.parse("1:5,3:8").toString())
        assertEquals("3:5", UidSet.parse("5:3").toString())
        assertEquals("1:*", UidSet.parse("1:*").toString())
        assertEquals("*", UidSet.parse("*").toString())
        assertEquals("10:*", UidSet.parse("10:*,20").toString())
        assertEquals(UidSet.EMPTY, UidSet.parse(""))
        assertEquals(UidSet.EMPTY, UidSet.parse("  "))
    }

    @Test
    fun buildFromUids() {
        assertEquals("1:3", UidSet.of(3, 1, 2).toString())
        assertEquals("1,3", UidSet.of(1, 3).toString())
        assertEquals("4", UidSet.of(4, 4).toString())
        assertEquals("1:2,9", UidSet.of(listOf(9L, 2L, 1L)).toString())
        assertTrue(UidSet.of(emptyList()).isEmpty)
        assertEquals("5:*", UidSet.range(5).toString())
        assertEquals("2:9", UidSet.range(9, 2).toString())
    }

    @Test
    fun queries() {
        val set = UidSet.parse("1:5,8,10:12")
        assertEquals(9L, set.count)
        assertEquals(1L, set.min)
        assertEquals(12L, set.max)
        assertTrue(3 in set)
        assertTrue(8 in set)
        assertTrue(12 in set)
        assertFalse(6 in set)
        assertFalse(13 in set)
        assertFalse(0 in set)
        assertEquals(listOf(1L, 2, 3, 4, 5, 8, 10, 11, 12), set.toList())
        assertNull(UidSet.EMPTY.min)
        assertTrue(UidSet.MAX in UidSet.range(3))
    }

    @Test
    fun openRangeHasNoList() {
        assertFailsWith<IllegalArgumentException> { UidSet.range(3).toList() }
    }

    @Test
    fun malformedInputThrows() {
        assertFailsWith<NumberFormatException> { UidSet.parse("1,,2") }
        assertFailsWith<NumberFormatException> { UidSet.parse("a:b") }
    }

    @Test
    fun chunks() {
        val set = UidSet.of((1L..1001L step 2).toList())
        assertEquals(501, set.ranges.size)
        val chunks = set.chunks()
        assertEquals(listOf(500, 1), chunks.map { it.ranges.size })
        assertEquals(set.toList(), chunks.flatMap { it.toList() })
        val small = UidSet.parse("1:5")
        assertEquals(listOf(small), small.chunks())
        assertEquals(listOf(3, 2), UidSet.parse("1,3,5,7,9").chunks(3).map { it.ranges.size })
    }

    @Test
    fun equality() {
        assertEquals(UidSet.parse("1:3"), UidSet.of(1, 2, 3))
        assertEquals(UidSet.parse("1:3").hashCode(), UidSet.of(1, 2, 3).hashCode())
    }
}
