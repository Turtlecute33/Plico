package app.plico.ui.mailbox

import app.plico.data.Prefs
import app.plico.ui.components.SenderColors
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RowLookTest {
    private val colorsOff = RowLook(markUnread = true, avatarColors = false, logos = null)
    private val colorsOn = RowLook(markUnread = true, avatarColors = true, logos = Prefs.LogoSource.DUCKDUCKGO)
    private val key = "alice@example.org"

    @Test
    fun colorsOffKeepTheAccountColourOnTheAvatar() {
        for (seen in listOf(true, false)) {
            assertEquals(AvatarStyle(colorIndex = null, badge = null), avatarStyle(colorsOff, seen, key, null))
            assertEquals(AvatarStyle(colorIndex = 3, badge = null), avatarStyle(colorsOff, seen, key, 3))
        }
    }

    @Test
    fun colorsOnShowTheReadState() {
        // Unread: the colour of the sender; read: the neutral colour.
        assertEquals(AvatarStyle(SenderColors.index(key), null), avatarStyle(colorsOn, seen = false, key, null))
        assertEquals(AvatarStyle(null, null), avatarStyle(colorsOn, seen = true, key, null))
    }

    @Test
    fun colorsOnMoveTheAccountColourToTheDot() {
        assertEquals(AvatarStyle(SenderColors.index(key), 5), avatarStyle(colorsOn, seen = false, key, 5))
        assertEquals(AvatarStyle(null, 5), avatarStyle(colorsOn, seen = true, key, 5))
    }

    @Test
    fun noKeyGivesTheNeutralColour() {
        assertEquals(AvatarStyle(null, null), avatarStyle(colorsOn, seen = false, senderKey = null, accountColor = null))
        assertEquals(AvatarStyle(null, 2), avatarStyle(colorsOn, seen = false, senderKey = null, accountColor = 2))
    }

    @Test
    fun theUnreadStyleChangesOnlyWhatTheSettingSays() {
        val off = RowLook(markUnread = false, avatarColors = true, logos = null)
        // Off: the look from before the setting (bold unread, no dot, no dimmed read rows).
        assertEquals(RowEmphasis(bold = true, dim = false, dot = false), rowEmphasis(off, seen = false))
        assertEquals(RowEmphasis(bold = false, dim = false, dot = false), rowEmphasis(off, seen = true))
        // On: a dot for unread rows, lighter text for read rows.
        assertEquals(RowEmphasis(bold = true, dim = false, dot = true), rowEmphasis(colorsOn, seen = false))
        assertEquals(RowEmphasis(bold = false, dim = true, dot = false), rowEmphasis(colorsOn, seen = true))
    }

    @Test
    fun senderKeyIsTheLowerCaseSender() {
        assertEquals("bob@example.org", senderKey(summary(1, 1, fromEmail = " Bob@Example.ORG "), sent = false, draft = false))
        assertNull(senderKey(summary(1, 1, fromEmail = ""), sent = false, draft = false))
    }

    @Test
    fun sentRowsUseTheFirstRecipient() {
        val row = summary(1, 1, fromEmail = "me@example.org", toList = "Carol <Carol@Example.net>, dave@example.com")
        assertEquals("carol@example.net", senderKey(row, sent = true, draft = false))
        assertNull(senderKey(summary(1, 1, toList = ""), sent = true, draft = false))
    }

    @Test
    fun draftsHaveNoKey() {
        assertNull(senderKey(summary(1, 1, toList = "x@example.org"), sent = true, draft = true))
        assertNull(senderKey(summary(1, 1), sent = false, draft = true))
    }

    @Test
    fun firstRecipientAddressSkipsTheName() {
        assertEquals("carol@example.net", firstRecipientAddress("\"Carol, C.\" <carol@example.net>"))
        assertNull(firstRecipientAddress(""))
    }
}
