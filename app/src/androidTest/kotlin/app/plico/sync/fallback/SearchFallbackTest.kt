package app.plico.sync.fallback

import app.plico.data.FolderRole
import app.plico.sync.FakeImap
import app.plico.sync.OpFixture
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Server TEXT search without ESEARCH (DESIGN §7.7): windows of 50,000 UIDs from UIDNEXT down, with the
 * plain `UID SEARCH` of `:mail` (DESIGN §4.4).
 */
class SearchFallbackTest : OpFixture() {
    private val other = otherAccount()
    private val inbox = mailbox(other, "INBOX", FolderRole.INBOX).also { folders.setSyncState(it, uidValidity = 7, uidNext = 10) }

    private fun FakeImap.selectInbox() = expect(
        "SELECT \"INBOX\"",
        "* 9 EXISTS", "* OK [UIDVALIDITY 7] ok", "* OK [UIDNEXT 120001] ok", "OK [READ-WRITE] done",
    )

    @Test
    fun textSearchGoesDownInWindows() {
        val known = insert(header(other, inbox, 3)).single()
        val imap = FakeImap("IMAP4rev1 IDLE MOVE UIDPLUS")
            .selectInbox()
            .expect("UID SEARCH UID 70001:* TEXT \"plan\"", "* SEARCH 70005")
            .expect("UID SEARCH UID 20001:70000 TEXT \"plan\"", "* SEARCH")
            .expect("UID SEARCH UID 1:20000 TEXT \"plan\"", "* SEARCH 3 5")
            .expect(
                Regex("""UID FETCH 5,70005 \(UID FLAGS .*\)"""),
                "* 2 FETCH (UID 5 FLAGS (\\Seen) BODY[HEADER.FIELDS (SUBJECT)] \"Subject: Old plan\")",
                "* 9 FETCH (UID 70005 FLAGS () BODY[HEADER.FIELDS (SUBJECT)] \"Subject: New plan\")",
            )
            .expect("UID FETCH 5,70005 (UID BODYSTRUCTURE)")
        session(other, imap)
        val ids = runBlocking { actions.searchServerIds("plan", other, inbox) }
        imap.finish()
        val old = messages.byUid(inbox, 5)!!
        val new = messages.byUid(inbox, 70005)!!
        assertEquals(listOf(new.id, old.id, known), ids, "newest first")
    }

    @Test
    fun theSearchStopsAt200UnknownHits() {
        val hits = (70_001L..70_200L).joinToString(" ")
        val imap = FakeImap("IMAP4rev1 IDLE MOVE UIDPLUS")
            .selectInbox()
            .expect("UID SEARCH UID 70001:* TEXT \"plan\"", "* SEARCH $hits")
            .expect(Regex("""UID FETCH 70001:70200 \(UID FLAGS .*\)"""))
        session(other, imap)
        val ids = runBlocking { actions.searchServerIds("plan", other, inbox) }
        imap.finish() // no second window
        assertTrue(ids.isEmpty(), "the fetch found none of them")
    }
}
