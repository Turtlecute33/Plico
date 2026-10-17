package app.plico.account.discovery

import app.plico.account.discovery.AutoconfigSamples.config
import app.plico.account.discovery.AutoconfigSamples.imap
import app.plico.account.discovery.AutoconfigSamples.smtp
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoconfigTest {
    private val email = "me@example.org"

    private fun parse(xml: String, address: String = email) = Autoconfig.parse(xml, address)

    @Test
    fun `URLs are https, on the domain, without the address`() {
        val urls = Autoconfig.urls("example.org")
        assertEquals(
            listOf(
                "https://autoconfig.example.org/mail/config-v1.1.xml",
                "https://example.org/.well-known/autoconfig/mail/config-v1.1.xml",
            ),
            urls,
        )
        assertTrue(urls.all { it.startsWith("https://") && '@' !in it && '?' !in it })
    }

    @Test
    fun `a provider file gives its hosts`() {
        assertEquals(
            Servers(Endpoint("imap.fastmail.com", 993, TlsMode.IMPLICIT), Endpoint("smtp.fastmail.com", 465, TlsMode.IMPLICIT)),
            parse(AutoconfigSamples.FASTMAIL_LIKE, "me@example.com"),
        )
    }

    @Test
    fun `the local part as user name is kept`() {
        val servers = parse(AutoconfigSamples.DISROOT_LIKE, "me@disroot.org")
        assertEquals(Endpoint("disroot.org", 993, TlsMode.IMPLICIT), servers?.imap)
        assertEquals(Endpoint("disroot.org", 465, TlsMode.IMPLICIT), servers?.smtp, "implicit TLS before STARTTLS")
        assertEquals("me", servers?.login)
    }

    @Test
    fun `implicit TLS wins over an earlier STARTTLS entry`() {
        // The real file lists IMAP 143 and SMTP 587 with STARTTLS first, and POP3 entries.
        val servers = parse(AutoconfigSamples.ESILIATI, "me@esiliati.org")
        assertEquals(
            Servers(Endpoint("imap.esiliati.org", 993, TlsMode.IMPLICIT), Endpoint("mail.esiliati.org", 465, TlsMode.IMPLICIT)),
            servers,
        )
        assertNull(servers?.login)
        // Another hosted domain of the same file gets its own hosts.
        assertEquals("imap.porcod.io", parse(AutoconfigSamples.ESILIATI, "me@porcod.io")?.imap?.host)
    }

    @Test
    fun `STARTTLS is used when no implicit-TLS entry passes`() {
        val xml = config(imap("imap.example.org", 143, "STARTTLS"), smtp("smtp.example.org", 587, "STARTTLS"))
        assertEquals(
            Servers(Endpoint("imap.example.org", 143, TlsMode.STARTTLS), Endpoint("smtp.example.org", 587, TlsMode.STARTTLS)),
            parse(xml),
        )
    }

    @Test
    fun `socket type TLS takes the mode from the port`() {
        val starttls = parse(config(imap("imap.example.org", 143, "TLS"), smtp("smtp.example.org", 587, "TLS")))
        assertEquals(TlsMode.STARTTLS, starttls?.imap?.tls)
        assertEquals(TlsMode.STARTTLS, starttls?.smtp?.tls)
        val implicit = parse(config(imap("imap.example.org", 993, "TLS"), smtp("smtp.example.org", 465, "tls")))
        assertEquals(TlsMode.IMPLICIT, implicit?.imap?.tls)
        assertEquals(TlsMode.IMPLICIT, implicit?.smtp?.tls)
        assertEquals(TlsMode.IMPLICIT, Autoconfig.tlsOf("TLS", 2525))
    }

    @Test
    fun `plain sections are skipped`() {
        val xml = config(
            imap("imap.example.org", 143, "plain") + imap("imap2.example.org", 993, "SSL"),
            smtp("smtp.example.org", 25, "plain") + smtp("smtp2.example.org", 465, "SSL"),
        )
        assertEquals("imap2.example.org", parse(xml)?.imap?.host)
        assertEquals("smtp2.example.org", parse(xml)?.smtp?.host)
        assertNull(parse(config(imap("imap.example.org", 143, "plain"), smtp("smtp.example.org", 465, "SSL"))))
        assertNull(parse(config(imap("imap.example.org", 993, "unknown"), smtp("smtp.example.org", 465, "SSL"))))
    }

    @Test
    fun `POP3 and other types are not IMAP`() {
        val pop = """<incomingServer type="pop3"><hostname>pop.example.org</hostname><port>995</port>""" +
            """<socketType>SSL</socketType><authentication>password-cleartext</authentication></incomingServer>"""
        assertNull(parse(config(pop, smtp("smtp.example.org", 465, "SSL"))))
        assertNull(parse(config(imap("imap.example.org", 993, "SSL"), "")), "no SMTP")
    }

    @Test
    fun `OAuth2 only counts for Google and Microsoft hosts`() {
        val unknown = config(
            imap("imap.example.org", 993, "SSL", auth = "OAuth2") + imap("imap2.example.org", 993, "SSL"),
            smtp("smtp.example.org", 465, "SSL", auth = "OAuth2"),
        )
        assertNull(parse(unknown), "no SMTP section with a usable method")
        val fallback = config(
            imap("imap.example.org", 993, "SSL", auth = "OAuth2") + imap("imap2.example.org", 993, "SSL"),
            smtp("smtp.example.org", 465, "SSL", auth = "password-encrypted"),
        )
        assertEquals("imap2.example.org", parse(fallback)?.imap?.host)
        val microsoft = config(
            imap("outlook.office365.com", 993, "SSL", auth = "OAuth2"),
            smtp("smtp.office365.com", 587, "STARTTLS", auth = "OAuth2"),
        )
        assertEquals(Endpoint("smtp.office365.com", 587, TlsMode.STARTTLS), parse(microsoft)?.smtp)
        assertNull(parse(config(imap("imap.example.org", 993, "SSL", auth = "NTLM"), smtp("smtp.example.org", 465, "SSL"))))
        val secure = config(imap("imap.example.org", 993, "SSL", auth = "secure"), smtp("smtp.example.org", 465, "SSL", auth = "plain"))
        assertEquals("imap.example.org", parse(secure)?.imap?.host)
    }

    @Test
    fun `a section may list several methods`() {
        val two = """<incomingServer type="imap"><hostname>imap.example.org</hostname><port>993</port>""" +
            """<socketType>SSL</socketType><authentication>OAuth2</authentication>""" +
            """<authentication>password-cleartext</authentication></incomingServer>"""
        assertEquals("imap.example.org", parse(config(two, smtp("smtp.example.org", 465, "SSL")))?.imap?.host)
    }

    @Test
    fun `placeholders are replaced`() {
        val xml = config(
            imap("imap.%EMAILDOMAIN%", 993, "SSL", user = "%EMAILLOCALPART%"),
            smtp("smtp.%EMAILDOMAIN%", 465, "SSL", user = "%EMAILLOCALPART%@%EMAILDOMAIN%"),
        )
        val servers = parse(xml, "Me.Name@Example.ORG")
        assertEquals("imap.example.org", servers?.imap?.host)
        assertEquals("smtp.example.org", servers?.smtp?.host)
        assertEquals("Me.Name", servers?.login)
        val address = config(imap("imap.%EMAILDOMAIN%", 993, "SSL", user = "%EMAILLOCALPART%@%EMAILDOMAIN%"), smtp("s.example.org", 465, "SSL"))
        assertNull(parse(address)?.login, "the address as user name is no separate login")
        val other = config(imap("imap.example.org", 993, "SSL", user = "account-42"), smtp("s.example.org", 465, "SSL"))
        assertEquals("account-42", parse(other)?.login)
    }

    @Test
    fun `a host name with the address in it is refused`() {
        val xml = config(
            imap("%EMAILLOCALPART%.imap.example.org", 993, "SSL") + imap("imap.example.org", 143, "STARTTLS"),
            smtp("%EMAILADDRESS%", 465, "SSL") + smtp("smtp.example.org", 587, "STARTTLS"),
        )
        val servers = parse(xml)
        assertEquals(Endpoint("imap.example.org", 143, TlsMode.STARTTLS), servers?.imap)
        assertEquals(Endpoint("smtp.example.org", 587, TlsMode.STARTTLS), servers?.smtp)
    }

    @Test
    fun `local names, IP addresses and bad ports are refused`() {
        val smtp = smtp("smtp.example.org", 465, "SSL")
        for (host in listOf("mail.local", "192.168.1.10", "[::1]", "localhost", "mail", "mail.home.arpa", "bad host.org")) {
            assertNull(parse(config(imap(host, 993, "SSL"), smtp)), host)
        }
        for (port in listOf("0", "65536", "-1", "abc", "")) {
            val xml = config(imap("imap.example.org", 993, "SSL").replace("<port>993</port>", "<port>$port</port>"), smtp)
            assertNull(parse(xml), port)
        }
        val idn = parse(config(imap("imap.bücher.example", 993, "SSL"), smtp))
        assertEquals("imap.xn--bcher-kva.example", idn?.imap?.host, "hosts are ASCII")
    }

    @Test
    fun `DOCTYPE and ENTITY are refused before parsing`() {
        val body = AutoconfigSamples.FASTMAIL_LIKE.substringAfter("?>")
        assertNull(parse("""<?xml version="1.0"?><!DOCTYPE clientConfig>$body"""))
        assertNull(parse("""<?xml version="1.0"?><!doctype clientConfig [<!entity x "y">]>$body"""))
        assertNull(parse("""<?xml version="1.0"?>$body<!ENTITY lol "lol">"""))
        val bomb = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]>""" +
            """<clientConfig>&lol2;</clientConfig>"""
        assertNull(parse(bomb))
    }

    @Test
    fun `a UTF-16 document with a DOCTYPE is refused`() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>""" +
            AutoconfigSamples.FASTMAIL_LIKE.substringAfter("?>")
        for (charset in listOf(Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            // The fetcher decodes the bytes once as UTF-8; the parser reads that same text.
            val bytes = (if (charset == Charsets.UTF_16) "" else "\uFEFF").toByteArray(charset) + xml.toByteArray(charset)
            assertNull(parse(bytes.toString(Charsets.UTF_8), "me@example.com"), charset.name())
        }
    }

    @Test
    fun `a UTF-8 byte order mark is allowed`() {
        assertEquals("imap.fastmail.com", parse("\uFEFF" + AutoconfigSamples.FASTMAIL_LIKE, "me@example.com")?.imap?.host)
    }

    @Test
    fun `size cap and broken XML`() {
        val padding = "<!-- " + "x".repeat(Autoconfig.MAX_CHARS) + " -->"
        assertNull(parse(AutoconfigSamples.FASTMAIL_LIKE.replace("<clientConfig", "$padding<clientConfig")))
        assertNull(parse(AutoconfigSamples.FASTMAIL_LIKE.dropLast(30)))
        assertNull(parse(""))
        assertNull(parse("not xml"))
        assertNull(parse("<html><body>404</body></html>"))
    }

    @Test
    fun `unknown elements are ignored`() {
        val xml = AutoconfigSamples.FASTMAIL_LIKE.replace(
            "<displayName>",
            "<webMail><loginPage url=\"https://example.com\"/></webMail><future attr=\"1\">x</future><displayName>",
        )
        assertFalse(parse(xml, "me@example.com") == null)
    }
}
