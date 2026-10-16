package app.plico.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListUnsubscribeTest {
    private fun oneClick(header: String?) = ListUnsubscribe.parse(header)?.oneClick

    @Test
    fun oneClickTakesTheHttpsLink() {
        assertEquals("https://example.org/u?id=1", oneClick("<mailto:u@example.org>, <https://example.org/u?id=1>"))
        assertEquals("https://example.org/u", oneClick("< https://example.org/u >"))
        assertEquals("https://example.org/u", oneClick("https://example.org/u"))
        assertEquals("HTTPS://example.org/u", oneClick("<HTTPS://example.org/u>"))
        assertEquals("https://example.org/s", oneClick("<http://example.org/p>, <https://example.org/s>"))
    }

    @Test
    fun oneClickRefusesEverythingElse() {
        assertNull(oneClick(null))
        assertNull(oneClick(" "))
        assertNull(oneClick("<mailto:u@example.org>"))
        assertNull(oneClick("<http://example.org/u>"))
        assertNull(oneClick("https://example.org/a b"))
    }

    @Test
    fun webAndMail() {
        val methods = ListUnsubscribe.parse("<http://example.org/p>, <mailto:leave@example.org?subject=x>")!!
        assertEquals("http://example.org/p", methods.web)
        assertEquals("leave@example.org", methods.mail?.to?.single()?.email)
        assertNull(ListUnsubscribe.parse("<ftp://example.org/u>"))
        assertNull(ListUnsubscribe.parse("mailto:?subject=x"), "a mailto without an address is no method")
    }
}
