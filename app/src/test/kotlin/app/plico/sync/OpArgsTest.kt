package app.plico.sync

import app.plico.data.FolderRole
import app.plico.data.NewOp
import app.plico.data.OpKind
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpArgsTest {
    private fun roundTrip(arg: OpArg) = assertEquals(arg, OpArg.decode(arg.kind, OpArg.encode(arg)))

    @Test
    fun everyKindRoundTrips() {
        roundTrip(OpArg.Flags("\\Seen", true))
        roundTrip(OpArg.Flags("\\Flagged", false))
        roundTrip(OpArg.Labels(listOf("\\Important", "Work/Ünïcode"), listOf("\\Inbox")))
        roundTrip(OpArg.Labels(emptyList(), emptyList()))
        roundTrip(OpArg.Move("Archive", FolderRole.ARCHIVE))
        roundTrip(OpArg.Move("[Gmail]/Trash", null))
        roundTrip(OpArg.Expunge())
        roundTrip(OpArg.Expunge(folderId = 7, upTo = 4_000_000_000))
        roundTrip(OpArg.Append(3, FolderRole.DRAFTS))
        roundTrip(OpArg.Append(3, FolderRole.SENT, "sent.eml"))
        roundTrip(OpArg.DeleteDraft("<A-1@riseup.net>"))
        roundTrip(OpArg.DeleteDraft("<A-1@riseup.net>", keep = 42))
    }

    @Test
    fun kindsMatchTheSchema() {
        assertEquals(OpKind.FLAGS, OpArg.Flags("\\Seen", true).kind)
        assertEquals(OpKind.LABELS, OpArg.Labels(emptyList(), emptyList()).kind)
        assertEquals(OpKind.MOVE, OpArg.Move("x", null).kind)
        assertEquals(OpKind.EXPUNGE, OpArg.Expunge().kind)
        assertEquals(OpKind.APPEND, OpArg.Append(1, FolderRole.SENT).kind)
        assertEquals(OpKind.DELETE_DRAFT, OpArg.DeleteDraft("<a@b>").kind)
    }

    @Test
    fun optionalFieldsAreLeftOut() {
        val move = JSONObject(OpArg.encode(OpArg.Move("Work", null)))
        assertEquals(setOf("path"), move.keys().asSequence().toSet())
        val expunge = JSONObject(OpArg.encode(OpArg.Expunge()))
        assertEquals(setOf("upTo"), expunge.keys().asSequence().toSet())
    }

    @Test
    fun badRowsDecodeToNull() {
        assertNull(OpArg.decode("send", "{}"))
        assertNull(OpArg.decode(OpKind.FLAGS, "not json"))
        assertNull(OpArg.decode(OpKind.FLAGS, "{\"flag\":\"\\\\Seen\"}"))
        assertNull(OpArg.decode(OpKind.MOVE, "{}"))
        assertNull(OpArg.decode(OpKind.APPEND, "{\"draft\":1,\"role\":\"nope\"}"))
        assertNull(OpArg.decode(OpKind.LABELS, "{\"add\":[]}"))
    }

    @Test
    fun anArgumentBecomesItsOpRow() {
        val arg = OpArg.Flags("\\Answered", true)
        val op = arg.toNewOp(3, runAt = 99, ids = listOf(7))
        assertEquals(NewOp(3, OpKind.FLAGS, listOf(7), OpArg.encode(arg), 99), op)
        assertEquals(emptyList(), OpArg.DeleteDraft("<m@x>").toNewOp(3, 0).messageIds)
    }

    @Test
    fun unknownRoleOfAMoveIsNoRole() {
        assertEquals(OpArg.Move("X", null), OpArg.decode(OpKind.MOVE, "{\"path\":\"X\",\"role\":\"future\"}"))
    }
}
