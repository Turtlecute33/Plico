package app.plico.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailActionsTest {
    @Test
    fun unsubscribeUrlTakesTheHttpsLink() {
        assertEquals("https://example.org/u?id=1", unsubscribeUrl("<mailto:u@example.org>, <https://example.org/u?id=1>"))
        assertEquals("https://example.org/u", unsubscribeUrl("< https://example.org/u >"))
        assertEquals("https://example.org/u", unsubscribeUrl("https://example.org/u"))
        assertEquals("HTTPS://example.org/u", unsubscribeUrl("<HTTPS://example.org/u>"))
    }

    @Test
    fun unsubscribeUrlRefusesEverythingElse() {
        assertNull(unsubscribeUrl(null))
        assertNull(unsubscribeUrl(" "))
        assertNull(unsubscribeUrl("<mailto:u@example.org>"))
        assertNull(unsubscribeUrl("<http://example.org/u>"))
        assertNull(unsubscribeUrl("https://example.org/a b"))
    }
}
