package app.plico.ui.setup

import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupInputTest {
    @Test
    fun `addresses are trimmed and lower-case`() {
        assertEquals("jane@gmail.com", SetupInput.normalizeEmail("  Jane@GMail.com "))
        assertEquals("joe@riseup.net", SetupInput.normalizeEmail("Joe@Riseup.NET"))
    }

    @Test
    fun `a bare name is not an address, for every provider`() {
        assertEquals("joe_hill", SetupInput.normalizeEmail(" Joe_Hill "))
        assertEquals(InputError.INVALID, SetupInput.checkEmail("joe_hill"))
        assertEquals("", SetupInput.normalizeEmail("   "))
    }

    @Test
    fun `address checks`() {
        assertEquals(InputError.REQUIRED, SetupInput.checkEmail(""))
        assertEquals(InputError.INVALID, SetupInput.checkEmail("jane@gmail"))
        assertEquals(InputError.INVALID, SetupInput.checkEmail("jane doe@gmail.com"))
        assertEquals(InputError.INVALID, SetupInput.checkEmail("jane@gmail.com\r\nbcc:x@y.z"))
        // The domain must be a usable host name: no IP literal, no local name.
        assertEquals(InputError.INVALID, SetupInput.checkEmail("jane@[192.0.2.1]"))
        assertEquals(InputError.INVALID, SetupInput.checkEmail("jane@printer.local"))
        assertNull(SetupInput.checkEmail("jane@gmail.com"))
        assertNull(SetupInput.checkEmail("jane@example.org"))
        assertNull(SetupInput.checkEmail("joe@mail.riseup.net"))
    }

    @Test
    fun `password checks`() {
        assertEquals(InputError.REQUIRED, SetupInput.checkPassword(Provider.RISEUP, ""))
        assertNull(SetupInput.checkPassword(Provider.RISEUP, " "))
        assertNull(SetupInput.checkPassword(Provider.OTHER, " "))
        assertEquals(InputError.REQUIRED, SetupInput.checkPassword(Provider.GMAIL, "    "))
        assertNull(SetupInput.checkPassword(Provider.GMAIL, "abcd efgh ijkl mnop"))
    }

    @Test
    fun `hosts are ASCII lower case`() {
        assertEquals("mail.example.org" to null, SetupInput.checkHost("  Mail.Example.ORG. "))
        // An IDN host is shown and stored in its punycode form, so that a lookalike name shows.
        assertEquals("imap.xn--bcher-kva.de" to null, SetupInput.checkHost("imap.bücher.de"))
        assertEquals("xn--80ak6aa92e.com" to null, SetupInput.checkHost("аррӏе.com"))
    }

    @Test
    fun `host errors`() {
        assertEquals(null to InputError.REQUIRED, SetupInput.checkHost("   "))
        assertEquals(null to InputError.IP_ADDRESS, SetupInput.checkHost("192.0.2.1"))
        assertEquals(null to InputError.IP_ADDRESS, SetupInput.checkHost("127.1"))
        assertEquals(null to InputError.IP_ADDRESS, SetupInput.checkHost("::1"))
        assertEquals(null to InputError.IP_ADDRESS, SetupInput.checkHost("[2001:db8::1]"))
        assertEquals(null to InputError.IP_ADDRESS, SetupInput.checkHost("0x7f.1"))
        assertEquals(null to InputError.INVALID, SetupInput.checkHost("mail.example.org:993"))
        assertEquals(null to InputError.INVALID, SetupInput.checkHost("nas.local"))
        assertEquals(null to InputError.INVALID, SetupInput.checkHost("mail.home.arpa"))
        assertEquals(null to InputError.INVALID, SetupInput.checkHost("localhost"))
        assertEquals(null to InputError.INVALID, SetupInput.checkHost("mailserver"))
        assertEquals(null to InputError.INVALID, SetupInput.checkHost("mail example.org"))
        assertEquals(null to InputError.INVALID, SetupInput.checkHost("https://mail.example.org"))
    }

    @Test
    fun `port rules`() {
        assertEquals(993 to null, SetupInput.checkPort(" 993 "))
        assertEquals(1 to null, SetupInput.checkPort("1"))
        assertEquals(65_535 to null, SetupInput.checkPort("65535"))
        for (bad in listOf("", "0", "65536", "-1", "99999999999", "143a", "1 43", "+25")) {
            assertEquals(null to InputError.PORT, SetupInput.checkPort(bad), bad)
        }
    }

    @Test
    fun `the security switches the port only from the usual port of the other mode`() {
        assertEquals("143", SetupInput.switchPort("993", imap = true, to = TlsMode.STARTTLS))
        assertEquals("993", SetupInput.switchPort("143", imap = true, to = TlsMode.IMPLICIT))
        assertEquals("587", SetupInput.switchPort("465", imap = false, to = TlsMode.STARTTLS))
        assertEquals("465", SetupInput.switchPort(" 587 ", imap = false, to = TlsMode.IMPLICIT))
        assertEquals("2525", SetupInput.switchPort("2525", imap = false, to = TlsMode.STARTTLS))
        assertEquals("993", SetupInput.switchPort("993", imap = true, to = TlsMode.IMPLICIT))
    }

    @Test
    fun `server fields become servers or field errors`() {
        val (servers, errors) = SetupInput.servers(
            "IMAP.Example.org", "993", TlsMode.IMPLICIT, "smtp.example.org", "587", TlsMode.STARTTLS, "  ",
        )
        assertNull(errors)
        assertEquals(
            Servers(
                Endpoint("imap.example.org", 993, TlsMode.IMPLICIT),
                Endpoint("smtp.example.org", 587, TlsMode.STARTTLS),
            ),
            servers,
        )
        val (none, fieldErrors) = SetupInput.servers(
            "10.0.0.1", "993", TlsMode.IMPLICIT, "smtp.example.org", "0", TlsMode.STARTTLS, "jo",
        )
        assertNull(none)
        assertEquals(ServerErrors(imapHost = InputError.IP_ADDRESS, smtpPort = InputError.PORT), fieldErrors)
    }

    @Test
    fun `the user name has no control characters, and empty means the address`() {
        assertNull(SetupInput.cleanUser("   "))
        assertEquals("jo", SetupInput.cleanUser(" jo "))
        assertEquals("jo x", SetupInput.cleanUser("jo\r\n x"))
        assertEquals("joDELETE", SetupInput.cleanUser("jo\u0000DELETE"))
    }

    @Test
    fun `same mailbox`() {
        assertTrue(SetupInput.sameMailbox("Jane@Example.org", "jane@example.org"))
        assertFalse(SetupInput.sameMailbox("jane@example.org", "jane@example.net"))
        // Other providers: dots count.
        assertFalse(SetupInput.sameMailbox("j.ane@example.org", "jane@example.org"))
        assertFalse(SetupInput.sameMailbox("j.ane@outlook.com", "jane@outlook.com"))
        // Gmail: dots do not count, and googlemail.com is gmail.com.
        assertTrue(SetupInput.sameMailbox("J.ane.Doe@gmail.com", "janedoe@gmail.com"))
        assertTrue(SetupInput.sameMailbox("jane.doe@googlemail.com", "janedoe@gmail.com"))
        assertFalse(SetupInput.sameMailbox("janedoe@gmail.com", "janedoe2@gmail.com"))
    }

    @Test
    fun `certificate fingerprints use the console format`() {
        assertEquals("00:0A:FF:7F", fingerprint(byteArrayOf(0, 10, -1, 127)))
    }
}
