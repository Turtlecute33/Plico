package app.plico.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDaoTest : DbFixture() {
    private val riseup = account()
    private val inbox = mailbox(riseup, "INBOX", FolderRole.INBOX)
    private val archive = mailbox(riseup, "Archive", FolderRole.ARCHIVE)
    private val trash = mailbox(riseup, "Trash", FolderRole.TRASH)

    private fun <T> Flow<T>.now(): T = runBlocking { withTimeout(5_000) { first() } }
    private fun list(view: MailView, limit: Int = 50) = messages.observeList(view, limit).now().map { it.id }

    @Test
    fun upsertKeepsBodyAttachmentsAndNotifiedOfExistingRows() {
        val id = insert(header(riseup, inbox, 7, notified = false)).single()
        messages.saveBody(
            id, "<p>hi</p>", "hi", "hi there", "hi",
            listOf(NewAttachment("2", "a.pdf", "application/pdf", 10, "base64", null, false)),
        )
        messages.markNotified(listOf(id))

        // UID n:* returns the last message again; the row must survive with its body.
        val again = insert(header(riseup, inbox, 7, seen = true, notified = false).copy(modSeq = 9)).single()
        assertEquals(id, again)
        val m = messages.get(id)!!
        assertTrue(m.hasBody)
        assertTrue(m.seen)
        assertTrue(m.notified)
        assertEquals(9, m.modSeq)
        assertEquals(1, m.attachments)
        assertEquals("hi", messages.body(id)?.text)
        assertEquals(1, messages.attachments(id).size)
        assertEquals(1, count("SELECT count(*) FROM message_folder WHERE message_id = ?", id))
        assertEquals(1, count("SELECT count(*) FROM message_fts WHERE message_fts MATCH 'there'"))
    }

    @Test
    fun upsertWritesSourceMembershipAndSearchIndex() {
        val ids = insert(header(riseup, inbox, 1, subject = "Quarterly invoice"), header(riseup, inbox, 2))
        assertEquals(2, count("SELECT count(*) FROM message_folder WHERE folder_id = ?", inbox))
        val hits = messages.observeList(MailView.Search("invoi", null), 10).now()
        assertEquals(listOf(ids[0]), hits.map { it.id })
        val bySender = messages.observeList(MailView.Search("sender2@example", riseup), 10).now()
        assertEquals(listOf(ids[1]), bySender.map { it.id })
    }

    @Test
    fun gmailLabelsReplaceButKeepSource() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val gInbox = label(gmail, "\\Inbox", FolderRole.INBOX)
        val work = label(gmail, "Work")
        val id = insert(header(gmail, all, 5, labels = setOf(gInbox, work))).single()
        assertEquals(setOf(all, gInbox, work), memberships(id))

        messages.syncFlags(all, listOf(ServerFlags(5, seen = true, flagged = false, answered = false, draft = false, labels = setOf(work))))
        assertEquals(setOf(all, work), memberships(id))
        assertTrue(messages.get(id)!!.seen)

        messages.syncFlags(all, listOf(ServerFlags(5, seen = true, flagged = false, answered = false, draft = false, labels = emptySet())))
        assertEquals(setOf(all), memberships(id))
    }

    @Test
    fun pendingOpsProtectRowsFromSync() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val gInbox = label(gmail, "\\Inbox", FolderRole.INBOX)
        val (a, b) = insert(header(gmail, all, 1, labels = setOf(gInbox)), header(gmail, all, 2, labels = setOf(gInbox)))

        // The user archives and reads a; the op is still in its undo window.
        messages.removeFromFolder(listOf(a), gInbox)
        messages.setSeen(listOf(a), true)
        ops.insert(NewOp(gmail, OpKind.LABELS, listOf(a), runAt = Long.MAX_VALUE))
        assertEquals(setOf(a), ops.referencedMessageIds(gmail))

        val server = { uid: Long -> ServerFlags(uid, seen = false, flagged = true, answered = false, draft = false, labels = setOf(gInbox)) }
        messages.syncFlags(all, listOf(server(1), server(2)))
        insert(header(gmail, all, 1, labels = setOf(gInbox)))

        val ma = messages.get(a)!!
        assertTrue(ma.seen)
        assertFalse(ma.flagged)
        assertEquals(setOf(all), memberships(a))
        assertTrue(messages.get(b)!!.flagged)
        assertEquals(setOf(all, gInbox), memberships(b))

        ops.delete(ops.byAccount(gmail).single().id)
        messages.syncFlags(all, listOf(server(1)))
        assertFalse(messages.get(a)!!.seen)
        assertEquals(setOf(all, gInbox), memberships(a))
    }

    @Test
    fun syncFlagsIgnoresUnknownUids() {
        insert(header(riseup, inbox, 1))
        messages.syncFlags(inbox, listOf(ServerFlags(99, seen = true, flagged = true, answered = true, draft = true)))
        assertEquals(1, count("SELECT count(*) FROM message"))
    }

    @Test
    fun listsExcludeHiddenAndLocalDraftRows() {
        val (a, b, c) = insert(
            header(riseup, inbox, 1, flagged = true),
            header(riseup, inbox, 2, flagged = true),
            header(riseup, inbox, 3, flagged = true, messageId = "<draft@riseup.net>"),
        )
        assertEquals(listOf(c, b, a), list(MailView.InFolder(inbox)))

        messages.setHidden(listOf(b), true)
        val draft = drafts.create(NewDraft(riseup, "user@riseup.net", messageId = "<draft@riseup.net>"))
        for (view in listOf(MailView.Unified, MailView.InFolder(inbox), MailView.Starred(null), MailView.Starred(riseup))) {
            assertEquals(listOf(a), list(view), view.toString())
        }
        assertEquals(listOf(a), list(MailView.Search("Sender", null)))

        // The same Message-ID in another account is not hidden by this account's draft.
        val other = account("other@riseup.net")
        val otherInbox = mailbox(other, "INBOX", FolderRole.INBOX)
        val copy = insert(header(other, otherInbox, 1, messageId = "<draft@riseup.net>")).single()
        assertEquals(listOf(copy), list(MailView.InFolder(otherInbox)))

        drafts.delete(draft)
        messages.setHidden(listOf(b), false)
        assertEquals(listOf(c, b, a), list(MailView.InFolder(inbox)))
    }

    @Test
    fun listsOrderByDateWithLimitAndScope() {
        val ids = insert(header(riseup, inbox, 1, date = 300), header(riseup, inbox, 2, date = 100), header(riseup, inbox, 3, date = 200))
        assertEquals(listOf(ids[0], ids[2]), list(MailView.InFolder(inbox), limit = 2))

        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val gInbox = label(gmail, "\\Inbox", FolderRole.INBOX)
        val inInbox = insert(header(gmail, all, 1, date = 250, labels = setOf(gInbox))).single()
        val archived = insert(header(gmail, all, 2, date = 400, flagged = true, labels = emptySet())).single()
        assertEquals(listOf(ids[0], inInbox, ids[2], ids[1]), list(MailView.Unified))
        assertEquals(listOf(archived, inInbox), list(MailView.InFolder(all)))
        assertEquals(listOf(archived), list(MailView.Starred(gmail)))
        assertEquals(emptyList(), list(MailView.Starred(riseup)))
    }

    @Test
    fun unifiedShowsAndCountsAMessageOnce() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val gInbox = label(gmail, "\\Inbox", FolderRole.INBOX)
        val alsoInbox = label(gmail, "Inbox copy", FolderRole.INBOX)
        val id = insert(header(gmail, all, 1, labels = setOf(gInbox, alsoInbox))).single()
        assertEquals(listOf(id), list(MailView.Unified))
        assertEquals(1, folders.observeUnifiedUnread().now())
        assertEquals(mapOf(all to 1, gInbox to 1, alsoInbox to 1), folders.observeUnread().now())
    }

    @Test
    fun starredLeavesOutTrashAndJunk() {
        val (a, b) = insert(header(riseup, inbox, 1, flagged = true), header(riseup, trash, 1, flagged = true))
        assertEquals(listOf(a), list(MailView.Starred(null)))
        assertTrue(b > a)
    }

    @Test
    fun emptySearchGivesNothing() {
        insert(header(riseup, inbox, 1))
        assertEquals(emptyList(), list(MailView.Search(" \"* ", null)))
    }

    @Test
    fun unreadCountsPerFolderAndUnified() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val gInbox = label(gmail, "\\Inbox", FolderRole.INBOX)
        val (r1, _, r3) = insert(header(riseup, inbox, 1), header(riseup, inbox, 2, seen = true), header(riseup, inbox, 3))
        insert(header(gmail, all, 1, labels = setOf(gInbox)), header(gmail, all, 2, labels = emptySet()))
        insert(header(riseup, archive, 1))

        assertEquals(mapOf(inbox to 2, archive to 1, all to 2, gInbox to 1), folders.observeUnread().now())
        assertEquals(3, folders.observeUnifiedUnread().now())

        messages.setSeen(listOf(r1), true)
        messages.setHidden(listOf(r3), true)
        assertEquals(mapOf(archive to 1, all to 2, gInbox to 1), folders.observeUnread().now())
        assertEquals(1, folders.observeUnifiedUnread().now())
    }

    @Test
    fun moveRowKeepsBodyAndDropsLabels() {
        val id = insert(header(riseup, inbox, 4)).single()
        messages.saveBody(id, null, "text", "text", "text", emptyList())
        messages.setHidden(listOf(id), true)
        val stale = insert(header(riseup, archive, 40)).single()

        messages.moveRow(id, archive, 40)
        val m = messages.get(id)!!
        assertEquals(archive, m.folderId)
        assertEquals(40, m.uid)
        assertFalse(m.hidden)
        assertTrue(m.hasBody)
        assertEquals(setOf(archive), memberships(id))
        assertNull(messages.get(stale))
        assertEquals(listOf(id), list(MailView.InFolder(archive)))
        assertEquals(emptyList(), list(MailView.InFolder(inbox)))
    }

    @Test
    fun deletesByIdAndUid() {
        val ids = insert(header(riseup, inbox, 1), header(riseup, inbox, 2), header(riseup, inbox, 3), header(riseup, archive, 2))
        assertEquals(2, messages.deleteUids(inbox, listOf(2, 3, 99)))
        assertEquals(listOf(1L), messages.uids(inbox))
        assertEquals(1, messages.delete(listOf(ids[0])))
        assertEquals(listOf(2L), messages.uids(archive))
    }

    @Test
    fun uidWindowsAndLookups() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val rows = (1L..10L).map { header(gmail, all, it * 10).copy(gmMsgId = 1000 + it) }
        val ids = messages.upsert(rows)
        messages.setHidden(listOf(ids[0]), true)

        assertEquals((1L..10L).map { it * 10 }, messages.uids(all))
        assertEquals(listOf(30L, 40L, 50L), messages.uids(all, 25, 55))
        assertEquals(listOf(90L, 100L), messages.newestUids(all, 2))
        assertEquals(10, messages.count(all))
        assertEquals(3, messages.count(all, 25, 55))
        assertEquals(mapOf(10L to ids[0], 20L to ids[1]), messages.idsByUid(all, listOf(10, 20, 15)))
        assertEquals(mapOf(1003L to ids[2]), messages.idsByGmMsgId(gmail, listOf(1003, 5)))
        assertEquals(ids[4], messages.byUid(all, 50)?.id)
        assertEquals(listOf(ids[4]), messages.byMessageId(gmail, "<m50@example.org>").map { it.id })
        assertEquals(listOf(MessageRef(ids[1], all, 20)), messages.refs(listOf(ids[1], 123456)))
    }

    @Test
    fun bodiesNeededInWindow() {
        val ids = insert(
            header(riseup, inbox, 1, date = 100), header(riseup, inbox, 2, date = 200),
            header(riseup, inbox, 3, date = 300), header(riseup, inbox, 4, date = 400),
        )
        insert(header(riseup, archive, 1, date = 500))
        messages.saveBody(ids[2], null, "x", "x", "x", emptyList())
        messages.setHidden(listOf(ids[3]), true)
        assertEquals(listOf(MessageRef(ids[1], inbox, 2), MessageRef(ids[0], inbox, 1)), messages.needingBodies(inbox, 0, 10))
        assertEquals(listOf(ids[1]), messages.needingBodies(inbox, 150, 10).map { it.id })
        assertEquals(listOf(ids[1]), messages.needingBodies(inbox, 0, 1).map { it.id })
    }

    @Test
    fun folderMembersAndLabelViews() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val gInbox = label(gmail, "\\Inbox", FolderRole.INBOX)
        val work = label(gmail, "Work")
        val (a, b, c) = insert(
            header(gmail, all, 1, labels = setOf(gInbox, work)),
            header(gmail, all, 2, labels = setOf(work)),
            header(gmail, all, 3, labels = emptySet()),
        )
        assertEquals(setOf(a), messages.memberIds(gInbox, listOf(a, b, c, 999)))
        assertEquals(setOf(a, b, c), messages.memberIds(all, listOf(a, b, c)))
        assertEquals(emptySet(), messages.memberIds(work, emptyList()))

        // Only label views; the source mailbox is not a label.
        assertEquals(
            mapOf(a to setOf(gInbox, work), b to setOf(work), c to emptySet(), 999L to emptySet()),
            messages.labelIds(listOf(a, b, c, 999)),
        )
        assertEquals(emptyMap(), messages.labelIds(emptyList()))

        val many = insert(*Array(1_200) { header(gmail, all, 100 + it.toLong(), labels = setOf(work)) })
        assertEquals(many.toSet(), messages.memberIds(work, many))
        val labels = messages.labelIds(many)
        assertEquals(1_200, labels.size)
        assertEquals(setOf(work), labels.values.toSet().single())
    }

    @Test
    fun earlierMailFromASender() {
        val (first, second) = insert(
            header(riseup, inbox, 1, date = 100).copy(fromEmail = "ann@example.org"),
            header(riseup, archive, 1, date = 200).copy(fromEmail = "Ann@Example.org"),
        )
        assertTrue(messages.hasEarlierFrom("ANN@example.org", 200, second))
        assertTrue(messages.hasEarlierFrom("ann@example.org", 150, second))
        assertFalse(messages.hasEarlierFrom("ann@example.org", 150, first), "the message itself does not count")
        assertFalse(messages.hasEarlierFrom("ann@example.org", 100, second), "not earlier")
        assertFalse(messages.hasEarlierFrom("bob@example.org", 300, 0))
    }

    @Test
    fun saveBodyIgnoresGoneRowsAndReplacesAttachments() {
        messages.saveBody(424242, null, "x", "x", "x", emptyList())
        assertEquals(0, count("SELECT count(*) FROM body"))

        val id = insert(header(riseup, inbox, 1)).single()
        val inline = NewAttachment("1.2", "logo.png", "image/png", 5, "base64", "logo@x", true)
        val file = NewAttachment("2", "a.zip", "application/zip", 9, "base64", null, false)
        messages.saveBody(id, "<img src=cid:logo@x>", null, "", "", listOf(inline, file))
        messages.saveBody(id, "<img src=cid:logo@x>", null, "", "", listOf(inline))
        assertEquals(listOf("logo.png"), messages.attachments(id).map { it.name })
        assertEquals(0, messages.get(id)!!.attachments)
        val detail = messages.observeDetail(id).now()
        assertNotNull(detail)
        assertEquals(listOf(inbox), detail.folders.map { it.id })
        assertEquals("<img src=cid:logo@x>", detail.body?.html)
        assertEquals(detail.attachments.single().id, messages.attachment(detail.attachments.single().id)?.id)
    }

    @Test
    fun claimNotificationsFollowsTheRule() {
        val now = 1_800_000_000_000L
        val (fresh, old, read, other, notifyOn) = insert(
            header(riseup, inbox, 1, date = now, notified = false),
            header(riseup, inbox, 2, date = 1, notified = false),
            header(riseup, inbox, 3, date = now, seen = true, notified = false),
            header(riseup, archive, 1, date = now, notified = false),
            header(riseup, trash, 1, date = now, notified = false),
        )
        insert(header(riseup, inbox, 4, date = now))
        folders.setNotify(trash, true)
        val since = now - 7 * 86_400_000L

        assertEquals(
            listOf(MessageRef(fresh, inbox, 1), MessageRef(notifyOn, trash, 1)).toSet(),
            messages.notifyCandidates(riseup, since).toSet(),
        )
        // The Gmail category filter drops a candidate before the claim.
        messages.markNotified(listOf(notifyOn))
        assertEquals(listOf(fresh), messages.claimNotifications(riseup, since).map { it.id })
        assertEquals(0, count("SELECT count(*) FROM message WHERE notified = 0"))
        assertEquals(emptyList(), messages.notifyCandidates(riseup, since))
        assertEquals(emptyList(), messages.claimNotifications(riseup, 0))
        assertTrue(old > 0 && read > 0 && other > 0)

        assertEquals(setOf(fresh, notifyOn), messages.stillNotifiable(listOf(fresh, notifyOn, other)))
        messages.setSeen(listOf(fresh), true)
        messages.setHidden(listOf(notifyOn), true)
        assertEquals(emptySet(), messages.stillNotifiable(listOf(fresh, notifyOn)))
    }

    @Test
    fun claimNotificationsReturnsEveryRowThatPassesTheRule() {
        val now = 1_800_000_000_000L
        val (fresh, notifyOn) = insert(
            header(riseup, inbox, 1, date = now, notified = false),
            header(riseup, trash, 1, date = now - 1, notified = false),
        )
        folders.setNotify(trash, true)
        val claimed = messages.claimNotifications(riseup, now - 7 * 86_400_000L).map { it.id }
        assertEquals(listOf(fresh, notifyOn), claimed)
        assertEquals(0, count("SELECT count(*) FROM message WHERE notified = 0"))
    }

    @Test
    fun claimNotificationsTakesSevenDaysAndOnlyVisibleRows() {
        val now = 1_800_000_000_000L
        val since = now - 7 * 86_400_000L
        val (edge, future, tooOld, hidden) = insert(
            header(riseup, inbox, 1, date = since, notified = false),
            header(riseup, inbox, 2, date = now + 3_600_000L, notified = false),
            header(riseup, inbox, 3, date = since - 1, notified = false),
            header(riseup, inbox, 4, date = now, notified = false),
            header(riseup, inbox, 5, date = now, notified = false, messageId = "<draft@riseup.net>"),
        )
        messages.setHidden(listOf(hidden), true)
        drafts.create(NewDraft(riseup, "user@riseup.net", messageId = "<draft@riseup.net>"))

        // A future date still notifies; a hidden row and the server copy of a local draft never do.
        assertEquals(listOf(future, edge), messages.claimNotifications(riseup, since).map { it.id })
        assertTrue(messages.get(tooOld)!!.notified)
        assertEquals(0, count("SELECT count(*) FROM message WHERE notified = 0"))
    }

    @Test
    fun claimNotificationsUsesGmailInboxLabel() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val gInbox = label(gmail, "\\Inbox", FolderRole.INBOX)
        val (inInbox, skipped) = insert(
            header(gmail, all, 1, notified = false, labels = setOf(gInbox)),
            header(gmail, all, 2, notified = false, labels = emptySet()),
        )
        assertEquals(listOf(inInbox), messages.claimNotifications(gmail, 0).map { it.id })
        assertTrue(messages.get(skipped)!!.notified)
    }

    @Test
    fun localEditsAndFolderMembership() {
        val gmail = account("me@gmail.com", Provider.GMAIL)
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val work = label(gmail, "Work")
        val id = insert(header(gmail, all, 1, labels = emptySet())).single()
        messages.setFlagged(listOf(id), true)
        messages.setAnswered(listOf(id), true)
        messages.addToFolder(listOf(id), work)
        assertEquals(setOf(all, work), memberships(id))
        messages.removeFromFolder(listOf(id), work)
        messages.removeFromFolder(listOf(id), all)
        assertEquals(setOf(all), memberships(id))
        val m = messages.get(id)!!
        assertTrue(m.flagged && m.answered)
    }

    private fun memberships(id: Long) =
        db.sql.list("SELECT folder_id FROM message_folder WHERE message_id = ?", id) { it.getLong(0) }.toSet()
}
