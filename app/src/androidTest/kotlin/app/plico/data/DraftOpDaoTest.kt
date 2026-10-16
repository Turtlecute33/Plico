package app.plico.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftOpDaoTest : DbFixture() {
    private val acc = account()

    @Test
    fun createAssignsStableMessageId() {
        val id = drafts.create(NewDraft(acc, "user@riseup.net", toList = "a@b.c", subject = "Hi"))
        val d = drafts.get(id)!!
        assertTrue(Regex("<[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}@riseup\\.net>").matches(d.messageId), d.messageId)
        assertEquals(DraftState.EDITING, d.state)
        assertEquals("a@b.c", d.toList)

        val attachments = listOf(DraftAttachment("photo.jpg", "Photo \"1\".jpg", "image/jpeg", 1234))
        assertTrue(drafts.update(d.copy(body = "text", attachments = attachments)))
        val saved = drafts.get(id)!!
        assertEquals(d.messageId, saved.messageId)
        assertEquals("text", saved.body)
        assertEquals(attachments, saved.attachments)
        assertEquals(id, drafts.byMessageId(acc, d.messageId)?.id)

        val copy = drafts.create(NewDraft(acc, "user@riseup.net", messageId = "<server@riseup.net>"))
        assertEquals("<server@riseup.net>", drafts.get(copy)!!.messageId)
    }

    @Test
    fun sendStateMachine() {
        val id = drafts.create(NewDraft(acc, "user@riseup.net"))
        assertFalse(drafts.markSending(id))
        assertTrue(drafts.queue(id, 5_000))
        assertEquals(listOf(id), drafts.dueSends(acc, 5_000).map { it.id })
        assertEquals(emptyList(), drafts.dueSends(acc, 4_999))
        assertEquals(5_000, drafts.nextSendAt(acc))

        // Undo wins before the sender claims it.
        assertTrue(drafts.unqueue(id))
        assertFalse(drafts.markSending(id))
        assertNull(drafts.nextSendAt(acc))

        assertTrue(drafts.queue(id, 0))
        assertTrue(drafts.markSending(id))
        assertFalse(drafts.unqueue(id))
        assertFalse(drafts.update(drafts.get(id)!!.copy(body = "changed while sending")))
        assertTrue(drafts.retryLater(id, 1, 9_000, "421 try later"))
        drafts.get(id)!!.let { assertEquals(listOf(DraftState.QUEUED, 1, 9_000L), listOf(it.state, it.attempts, it.sendAt)) }

        assertTrue(drafts.markSending(id))
        assertTrue(drafts.markSent(id))
        assertEquals(DraftState.SENT, drafts.get(id)!!.state)
        assertFalse(drafts.queue(id, 0))

        val failed = drafts.create(NewDraft(acc, "user@riseup.net"))
        drafts.queue(failed, 0)
        assertTrue(drafts.markFailed(failed, "550 rejected"))
        assertTrue(drafts.queue(failed, 1))
        drafts.get(failed)!!.let { assertEquals(listOf(DraftState.QUEUED, 0, null), listOf(it.state, it.attempts, it.error)) }

        drafts.setOnServer(id, true)
        assertTrue(drafts.get(id)!!.onServer)
        drafts.delete(id)
        assertNull(drafts.get(id))
    }

    @Test
    fun startupRuleFailsInterruptedSends() {
        val sending = drafts.create(NewDraft(acc, "user@riseup.net"))
        val queued = drafts.create(NewDraft(acc, "user@riseup.net"))
        drafts.queue(sending, 0)
        drafts.markSending(sending)
        drafts.queue(queued, 0)

        val text = context.getString(app.plico.R.string.data_error_may_have_been_sent)
        assertEquals(1, drafts.failInterrupted(text))
        drafts.get(sending)!!.let {
            assertEquals(DraftState.FAILED, it.state)
            assertEquals(text, it.error)
        }
        assertEquals(DraftState.QUEUED, drafts.get(queued)!!.state)
        assertEquals(0, drafts.failInterrupted(text))
    }

    @Test
    fun observesDrafts() = runBlocking {
        val other = account("other@riseup.net")
        val a = drafts.create(NewDraft(acc, "user@riseup.net"))
        Thread.sleep(5)
        val b = drafts.create(NewDraft(other, "other@riseup.net"))
        assertEquals(listOf(b, a), withTimeout(5_000) { drafts.observeAll().first() }.map { it.id })
        assertEquals(listOf(a), withTimeout(5_000) { drafts.observeByAccount(acc).first() }.map { it.id })
        assertEquals(b, withTimeout(5_000) { drafts.observe(b).first() }?.id)
    }

    @Test
    fun opsQueueInIdOrder() {
        val inbox = mailbox(acc, "INBOX", FolderRole.INBOX)
        val (m1, m2) = insert(header(acc, inbox, 1), header(acc, inbox, 2))
        val later = ops.insert(NewOp(acc, OpKind.MOVE, listOf(m1), """{"to":3}""", runAt = 4_000))
        val now = ops.insert(NewOp(acc, OpKind.FLAGS, listOf(m1, m2), runAt = 1_000))
        val other = account("other@riseup.net")
        ops.insert(NewOp(other, OpKind.FLAGS, listOf(999), runAt = 0))

        assertEquals(listOf(now), ops.due(acc, 1_000).map { it.id })
        assertEquals(listOf(later, now), ops.due(acc, 4_000).map { it.id })
        assertEquals(1_000, ops.nextRunAt(acc))
        assertEquals(setOf(m1, m2), ops.referencedMessageIds(acc))

        val op = ops.get(later)!!
        assertEquals(listOf(m1), op.messageIds)
        assertEquals("""{"to":3}""", op.arg)
        assertEquals(OpKind.MOVE, op.kind)

        ops.retryLater(now, 2, 30_000, "[UNAVAILABLE]")
        ops.get(now)!!.let { assertEquals(listOf(2, 30_000L, "[UNAVAILABLE]"), listOf(it.attempts, it.runAt, it.error)) }
        assertEquals(4_000, ops.nextRunAt(acc))
        ops.delete(later)
        ops.delete(now)
        assertNull(ops.nextRunAt(acc))
        assertEquals(emptySet(), ops.referencedMessageIds(acc))
    }
}
