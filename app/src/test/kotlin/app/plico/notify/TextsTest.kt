package app.plico.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextsTest {
    @Test
    fun publicVersionsShowOnlyALabelTheUserSet() {
        assertNull(Texts.publicLabel("Riseup"))
        assertNull(Texts.publicLabel("Gmail"))
        assertNull(Texts.publicLabel("Gmail 2"))
        assertNull(Texts.publicLabel("Outlook"))
        assertNull(Texts.publicLabel("Outlook 3"))
        assertNull(Texts.publicLabel("Mail"))
        assertNull(Texts.publicLabel("Mail 2"))
        assertNull(Texts.publicLabel("  "))
        assertEquals("Mailbox", Texts.publicLabel("Mailbox"))
        assertEquals("Outlook work", Texts.publicLabel("Outlook work"))
        assertEquals("Work", Texts.publicLabel(" Work "))
        assertEquals("Riseup old", Texts.publicLabel("Riseup old"))
    }

    @Test
    fun publicVersionsNeverShowAnAddress() {
        assertNull(Texts.publicLabel("me@riseup.net"))
        assertNull(Texts.publicLabel("Work (a@b.org)"))
    }

    @Test
    fun contentIsHiddenByTheAppLockOrTheSetting() {
        assertFalse(Texts.hideContent(appLock = false, hideNotificationContent = false))
        assertTrue(Texts.hideContent(appLock = true, hideNotificationContent = false))
        assertTrue(Texts.hideContent(appLock = false, hideNotificationContent = true))
    }

    @Test
    fun senderFallsBackToTheAddress() {
        assertEquals("Ana", Texts.sender("Ana", "ana@x.org"))
        assertEquals("ana@x.org", Texts.sender(" ", "ana@x.org"))
        assertNull(Texts.sender("", ""))
    }

    @Test
    fun bigTextAddsTheSnippetWhenKnown() {
        assertEquals("Hello", Texts.bigText("Hello", " "))
        assertEquals("Hello\nSee you", Texts.bigText("Hello", "See you "))
    }

    @Test
    fun bidiControlsAreRevealed() {
        assertEquals("Ana\uFFFDgro.x", Texts.sender("Ana\u202Egro.x", "ana@x.org"))
        assertEquals("Hi\uFFFDfdp.exe\nSee\uFFFDyou", Texts.bigText("Hi\u202Efdp.exe", "See\u202Eyou"))
        assertEquals("Hi\uFFFDthere", Texts.bigText("Hi\u202Ethere", ""))
        // Plain text stays the same.
        assertEquals("Ana", Texts.sender("Ana", "ana@x.org"))
        assertEquals("Hello\nSee you", Texts.bigText("Hello", "See you"))
    }

    @Test
    fun tagsRoundTrip() {
        assertEquals("msg:42", Texts.childTag(42))
        assertEquals(42L, Texts.childId("msg:42"))
        assertNull(Texts.childId("acct:42"))
        assertNull(Texts.childId("42"))
        assertNull(Texts.childId(null))
        assertEquals("acct:7", Texts.summaryTag(7))
    }

    @Test
    fun reauthLinkAcceptsOnlyItsExactForm() {
        assertEquals("plico:reauth/3", ReauthLink.uri(3))
        assertEquals(3L, ReauthLink.parse("plico:reauth/3"))
        assertNull(ReauthLink.parse("plico:reauth/0"))
        assertNull(ReauthLink.parse("plico:reauth/-3"))
        assertNull(ReauthLink.parse("plico:reauth/3/x"))
        assertNull(ReauthLink.parse("plico:message/3"))
        assertNull(ReauthLink.parse(null))
    }
}
