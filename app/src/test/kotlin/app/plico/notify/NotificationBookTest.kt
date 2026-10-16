package app.plico.notify

import app.plico.notify.NotificationBook.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationBookTest {
    private val book = NotificationBook()

    /** Messages of [account] with ids [ids]; a higher id is newer. */
    private fun items(account: Long, vararg ids: Long) = ids.map { Item(it, account, date = it * 1_000) }

    private fun ids(items: List<Item>) = items.map { it.id }

    @Test
    fun batchPostsFiveNewestChildrenAndAlerts() {
        val batch = book.add(1, items(1, 1, 2, 3, 4, 5, 6, 7), now = 0)
        assertTrue(batch.alert)
        assertEquals(listOf(7L, 6, 5, 4, 3), ids(batch.post))
        assertEquals(emptyList(), batch.cancel)
        assertEquals(NotificationBook.Summary(7, items(1, 7, 6, 5, 4, 3), more = 2), book.summary(1))
    }

    @Test
    fun knownMailDoesNotAlertAgain() {
        book.add(1, items(1, 1, 2), now = 0)
        val again = book.add(1, items(1, 2, 1), now = 10)
        assertFalse(again.alert)
        assertEquals(emptyList(), again.post)

        val next = book.add(1, items(1, 2, 3), now = 20)
        assertTrue(next.alert)
        assertEquals(listOf(3L), ids(next.post))
        assertEquals(listOf(3L, 2, 1), ids(book.pending(1)))
    }

    @Test
    fun childrenStayBelowTheLimitOverAllAccounts() {
        val small = NotificationBook(batchChildren = 5, maxChildren = 6)
        small.add(1, items(1, 1, 2, 3), now = 0)
        small.add(2, items(2, 4, 5, 6), now = 0)
        val batch = small.add(1, items(1, 7, 8), now = 0)
        // The two oldest children go; their mail stays in the summaries as "+N more".
        assertEquals(listOf(8L, 7), ids(batch.post))
        assertEquals(setOf(1L, 2), ids(batch.cancel).toSet())
        assertEquals(listOf(8L, 7, 3), ids(small.children(1)))
        assertEquals(listOf(8L, 7, 3, 2, 1), ids(small.pending(1)))
        assertEquals(3, small.children(2).size)
    }

    @Test
    fun retainDropsReadMailAndReturnsItsChildren() {
        book.add(1, items(1, 1, 2, 3, 4, 5, 6, 7), now = 0)
        val gone = book.retain(1, setOf(7, 3, 2))
        assertEquals(setOf(6L, 5, 4), ids(gone).toSet())
        assertEquals(listOf(7L, 3, 2), ids(book.pending(1)))
        assertEquals(listOf(7L, 3), ids(book.children(1)))
    }

    @Test
    fun dismissedSummaryForgetsTheAccountAfterTheGrace() {
        book.add(1, items(1, 1, 2), now = 1_000)
        // Just posted: the system may not list it yet.
        book.reconcile(1, summaryShown = false, shownChildren = emptySet(), now = 2_000, graceMs = 2_000)
        assertEquals(2, book.pending(1).size)
        book.reconcile(1, summaryShown = false, shownChildren = emptySet(), now = 4_000, graceMs = 2_000)
        assertEquals(emptyList(), book.pending(1))
        assertEquals(emptySet(), book.accounts())
    }

    @Test
    fun dismissedChildLeavesTheSummaryCount() {
        book.add(1, items(1, 1, 2, 3), now = 0)
        book.reconcile(1, summaryShown = true, shownChildren = setOf(1, 3), now = 5_000, graceMs = 2_000)
        assertEquals(listOf(3L, 1), ids(book.pending(1)))
        assertEquals(listOf(3L, 1), ids(book.children(1)))
    }

    @Test
    fun hiddenContentKeepsOnlyTheSummary() {
        book.add(1, items(1, 1, 2), now = 0)
        assertEquals(setOf(1L, 2), ids(book.dropChildren(1)).toSet())
        assertEquals(emptyList(), book.children(1))
        assertEquals(2, book.summary(1).count)
    }

    @Test
    fun contentShownAgainGetsTheNewestChildrenBack() {
        book.add(1, items(1, 1, 2, 3, 4, 5, 6, 7), now = 0)
        book.dropChildren(1)
        val shown = book.showChildren(1, now = 10)
        assertFalse(shown.alert)
        assertEquals(listOf(7L, 6, 5, 4, 3), ids(shown.post))
        assertEquals(emptyList(), book.showChildren(1, now = 20).post)
    }

    @Test
    fun restoreRebuildsAfterAProcessStart() {
        book.restore(1, pending = items(1, 1, 2, 3), children = items(1, 3, 4))
        assertEquals(listOf(4L, 3, 2, 1), ids(book.pending(1)))
        assertEquals(listOf(4L, 3), ids(book.children(1)))
        assertEquals(setOf(4L, 3), ids(book.clear(1)).toSet())
        assertEquals(emptyList(), book.pending(1))
    }
}
