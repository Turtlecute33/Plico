package app.plico.sync

import app.plico.data.FolderRole
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Server search (DESIGN.md §7.7) on a scripted server. */
class SearchTest : OpFixture() {
    private val riseup = account()
    private val inbox = mailbox(riseup, "INBOX", FolderRole.INBOX).also { folders.setSyncState(it, uidValidity = 7, uidNext = 10) }

    @Test
    fun theHitsAreStoredAndOnlyNewMailMayNotify() {
        val known = insert(header(riseup, inbox, 3)).single()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID SEARCH RETURN (ALL) TEXT \"plan\"", "* ESEARCH UID ALL 3,5,12")
            .expect(
                Regex("""UID FETCH 5,12 \(UID FLAGS .*\)"""),
                "* 2 FETCH (UID 5 FLAGS (\\Seen) BODY[HEADER.FIELDS (SUBJECT)] \"Subject: Old plan\")",
                "* 4 FETCH (UID 12 FLAGS () BODY[HEADER.FIELDS (SUBJECT)] \"Subject: New plan\")",
            )
            .expect("UID FETCH 5,12 (UID BODYSTRUCTURE)")
        session(riseup, imap)
        val ids = runBlocking { actions.searchServerIds("plan", riseup, inbox) }
        imap.finish()
        val old = messages.byUid(inbox, 5)!!
        val new = messages.byUid(inbox, 12)!!
        assertEquals(listOf(new.id, old.id, known), ids, "every hit, newest first, for the list")
        assertTrue(old.notified, "a hit below UIDNEXT is old mail")
        assertFalse(new.notified, "above UIDNEXT the new-UID fetch has not run yet: the notification rule decides")
        assertEquals("New plan", new.subject)
    }
}
