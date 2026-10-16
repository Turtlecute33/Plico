package app.plico.ui.nav

import android.content.Intent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntryTest {
    private val main = "app.plico.MainActivity"

    private fun entry(action: String?, data: String? = null, className: String? = main, flags: Int = 0) =
        Entry.of(action, className, data, flags)

    @Test
    fun messageLinkRoundTrip() {
        assertEquals("plico:message/42", MessageLink.uri(42))
        assertEquals(42L, MessageLink.parse(MessageLink.uri(42)))
        assertEquals(Long.MAX_VALUE / 10, MessageLink.parse(MessageLink.uri(Long.MAX_VALUE / 10)))
    }

    @Test
    fun messageLinkRejectsEverythingElse() {
        listOf(
            null, "", "plico:message/", "plico:message/0", "plico:message/-1", "plico:message/+1",
            "plico:message/12a", "plico:message/1/2", "plico:message/1?x=2", "plico:message/ 1",
            "plico:message/١٢", "plico:message/1234567890123456789", "PLICO:message/1", "plico://message/1",
            "xplico:message/1", "plico:messages/1", "https://example.org/plico:message/1",
        ).forEach { assertNull(MessageLink.parse(it), "$it") }
    }

    @Test
    fun aliasOpensAMessage() {
        assertEquals(Entry.OpenMessage(7), entry(Intent.ACTION_VIEW, "plico:message/7", MessageLink.ALIAS))
        assertEquals(Entry.OpenMessage(7), entry(null, "plico:message/7", MessageLink.ALIAS))
    }

    @Test
    fun aliasWithBadDataDoesNothing() {
        assertNull(entry(Intent.ACTION_VIEW, "plico:message/x", MessageLink.ALIAS))
        assertNull(entry(Intent.ACTION_SEND, null, MessageLink.ALIAS))
    }

    @Test
    fun otherAppsCannotOpenAMessage() {
        assertNull(entry(Intent.ACTION_VIEW, "plico:message/7"))
        assertNull(entry(Intent.ACTION_VIEW, "plico:message/7", className = null))
    }

    @Test
    fun sharesAndMailtoOpenCompose() {
        assertEquals(Entry.Compose, entry(Intent.ACTION_SEND))
        assertEquals(Entry.Compose, entry(Intent.ACTION_SEND_MULTIPLE))
        assertEquals(Entry.Compose, entry(Intent.ACTION_SENDTO, "mailto:a@example.org"))
        assertEquals(Entry.Compose, entry(Intent.ACTION_VIEW, "mailto:a@example.org?subject=Hi"))
        assertEquals(Entry.Compose, entry(Intent.ACTION_VIEW, "MAILTO:a@example.org"))
    }

    @Test
    fun otherSchemesAreIgnored() {
        assertNull(entry(Intent.ACTION_SENDTO, "smsto:123"))
        assertNull(entry(Intent.ACTION_VIEW, "https://example.org"))
        assertNull(entry(Intent.ACTION_VIEW, null))
        assertNull(entry(Intent.ACTION_VIEW, "xmailto:a@example.org"))
    }

    @Test
    fun oauthDone() {
        assertEquals(Entry.OAuthDone, entry(Entry.ACTION_OAUTH_DONE))
    }

    @Test
    fun launcherAndUnknownActionsDoNothing() {
        assertNull(entry(Intent.ACTION_MAIN))
        assertNull(entry(null))
        assertNull(entry(Intent.ACTION_EDIT, "mailto:a@example.org"))
    }

    @Test
    fun relaunchFromRecentsIsIgnored() {
        val history = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertNull(entry(Intent.ACTION_SEND, flags = history))
        assertNull(entry(Intent.ACTION_VIEW, "plico:message/7", MessageLink.ALIAS, history))
        assertNull(entry(Entry.ACTION_OAUTH_DONE, flags = history))
    }
}
