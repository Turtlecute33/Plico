package app.plico.ui.setup

import app.plico.data.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SetupInputTest {
    @Test
    fun `addresses are trimmed and lower-case`() {
        assertEquals("jane@gmail.com", SetupInput.normalizeEmail(Provider.GMAIL, "  Jane@GMail.com "))
        assertEquals("joe@riseup.net", SetupInput.normalizeEmail(Provider.RISEUP, "Joe@Riseup.NET"))
    }

    @Test
    fun `a bare Riseup user name gets the domain`() {
        assertEquals("joe_hill@riseup.net", SetupInput.normalizeEmail(Provider.RISEUP, " Joe_Hill "))
        assertEquals("jane", SetupInput.normalizeEmail(Provider.GMAIL, "jane"))
        assertEquals("", SetupInput.normalizeEmail(Provider.RISEUP, "   "))
    }

    @Test
    fun `address checks`() {
        assertEquals(InputError.REQUIRED, SetupInput.checkEmail(Provider.GMAIL, ""))
        assertEquals(InputError.INVALID, SetupInput.checkEmail(Provider.GMAIL, "jane"))
        assertEquals(InputError.INVALID, SetupInput.checkEmail(Provider.GMAIL, "jane@gmail"))
        assertEquals(InputError.INVALID, SetupInput.checkEmail(Provider.GMAIL, "jane doe@gmail.com"))
        assertEquals(InputError.INVALID, SetupInput.checkEmail(Provider.GMAIL, "jane@gmail.com\r\nbcc:x@y.z"))
        assertNull(SetupInput.checkEmail(Provider.GMAIL, "jane@gmail.com"))
        assertNull(SetupInput.checkEmail(Provider.GMAIL, "jane@example.org"))
    }

    @Test
    fun `Riseup logs in only with its own domain`() {
        assertNull(SetupInput.checkEmail(Provider.RISEUP, "joe@riseup.net"))
        assertEquals(InputError.NOT_RISEUP, SetupInput.checkEmail(Provider.RISEUP, "joe@example.org"))
        assertEquals(InputError.NOT_RISEUP, SetupInput.checkEmail(Provider.RISEUP, "joe@mail.riseup.net"))
    }

    @Test
    fun `password checks`() {
        assertEquals(InputError.REQUIRED, SetupInput.checkPassword(Provider.RISEUP, ""))
        assertNull(SetupInput.checkPassword(Provider.RISEUP, " "))
        assertEquals(InputError.REQUIRED, SetupInput.checkPassword(Provider.GMAIL, "    "))
        assertNull(SetupInput.checkPassword(Provider.GMAIL, "abcd efgh ijkl mnop"))
    }

    @Test
    fun `certificate fingerprints use the console format`() {
        assertEquals("00:0A:FF:7F", fingerprint(byteArrayOf(0, 10, -1, 127)))
    }
}
