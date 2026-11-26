package app.plico.sync

import app.plico.data.Op
import app.plico.data.OpKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpOrderTest {
    private fun op(id: Long, kind: String, vararg messages: Long, account: Long = 1) =
        Op(id, account, kind, messages.toList(), "{}", 0, 0, 0, null)

    @Test
    fun laterOpWaitsForEarlierOpOnTheSameMessage() {
        val read = op(1, OpKind.FLAGS, 5)
        val unread = op(2, OpKind.FLAGS, 5, 6)
        assertTrue(Ops.blockedByEarlier(unread, listOf(read, unread)))
        assertFalse(Ops.blockedByEarlier(read, listOf(read, unread)))
    }

    @Test
    fun otherMessageKindOrAccountDoesNotBlock() {
        val later = op(3, OpKind.FLAGS, 5)
        assertFalse(Ops.blockedByEarlier(later, listOf(op(1, OpKind.FLAGS, 9))))
        assertFalse(Ops.blockedByEarlier(later, listOf(op(1, OpKind.LABELS, 5))))
        assertFalse(Ops.blockedByEarlier(later, listOf(op(1, OpKind.FLAGS, 5, account = 2))))
    }
}
