package app.plico.account

import app.plico.account.ProviderTable.Login
import app.plico.account.ProviderTable.Note
import app.plico.account.ProviderTable.Refusal
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderTableTest {
    private fun servers(imap: String, smtp: String) =
        Servers(Endpoint(imap, 993, TlsMode.IMPLICIT), Endpoint(smtp, 465, TlsMode.IMPLICIT))

    @Test
    fun `domains find their entry`() {
        assertEquals(Provider.RISEUP, ProviderTable.byDomain("riseup.net")?.provider)
        assertEquals(Provider.GMAIL, ProviderTable.byDomain("googlemail.com")?.provider)
        assertEquals("Outlook", ProviderTable.byDomain("hotmail.co.uk")?.name)
        assertEquals("Yahoo", ProviderTable.byDomain("Yahoo.COM.")?.name, "case and a trailing dot do not matter")
        assertEquals("iCloud", ProviderTable.byDomain("me.com")?.name)
        assertEquals("Posteo", ProviderTable.byDomain("posteo.net")?.name)
        assertEquals("Zoho CA", ProviderTable.byDomain("zohomailcloud.ca")?.name)
        assertNull(ProviderTable.byDomain("example.org"))
        assertNull(ProviderTable.byDomain("mail.gmail.com"), "exact match only")
    }

    @Test
    fun `every onmicrosoft domain is Microsoft 365`() {
        val entry = ProviderTable.byDomain("contoso.onmicrosoft.com")
        assertEquals("Microsoft 365", entry?.name)
        assertEquals(Provider.MICROSOFT, entry?.provider)
        assertEquals("smtp.office365.com", entry?.servers?.smtp?.host)
        assertNull(ProviderTable.byDomain("onmicrosoft.com"))
    }

    @Test
    fun `MX hosts match by suffix`() {
        assertEquals("Outlook", ProviderTable.byMx("eur.olc.protection.outlook.com")?.name)
        assertEquals("Microsoft 365", ProviderTable.byMx("contoso-com.mail.protection.outlook.com")?.name)
        assertEquals("Microsoft 365", ProviderTable.byMx("contoso-com.d-v1.mx.microsoft")?.name)
        assertEquals("Gmail", ProviderTable.byMx("aspmx.l.google.com")?.name)
        assertEquals("Gmail", ProviderTable.byMx("ALT1.ASPMX.L.GOOGLE.COM.")?.name)
        assertEquals("iCloud", ProviderTable.byMx("mx01.mail.icloud.com")?.name)
        assertEquals("Fastmail", ProviderTable.byMx("in1-smtp.messagingengine.com")?.name)
        assertEquals("Proton Mail", ProviderTable.byMx("mail.protonmail.ch")?.name)
        assertNull(ProviderTable.byMx("notgoogle.com"), "a suffix matches only at a label boundary")
        assertNull(ProviderTable.byMx("mx.example.org"))
    }

    @Test
    fun `Zoho custom domains find their region`() {
        assertEquals("Zoho (custom domain)", ProviderTable.byMx("mx.zoho.com")?.name)
        assertEquals("Zoho EU (custom domain)", ProviderTable.byMx("mx2.zoho.eu")?.name)
        assertEquals("Zoho IN (custom domain)", ProviderTable.byMx("mx.zoho.in")?.name)
        val au = ProviderTable.byMx("mx.zoho.com.au")
        assertEquals("Zoho AU (custom domain)", au?.name)
        assertEquals("imappro.zoho.com.au", au?.servers?.imap?.host)
        assertEquals(setOf(Note.ENABLE_IMAP), au?.notes)
    }

    @Test
    fun `ports give the TLS mode`() {
        val tim = ProviderTable.byDomain("tim.it")?.servers
        assertEquals(Endpoint("imap.tim.it", 143, TlsMode.STARTTLS), tim?.imap)
        assertEquals(Endpoint("smtp.tim.it", 587, TlsMode.STARTTLS), tim?.smtp)
        assertEquals(TlsMode.STARTTLS, ProviderTable.byDomain("web.de")?.servers?.smtp?.tls)
        assertEquals(TlsMode.STARTTLS, ProviderTable.byDomain("icloud.com")?.servers?.smtp?.tls)
        assertEquals(TlsMode.IMPLICIT, ProviderTable.byDomain("icloud.com")?.servers?.imap?.tls)
        for (entry in ProviderTable.entries) {
            val servers = entry.servers ?: continue
            for (endpoint in listOf(servers.imap, servers.smtp)) {
                val expected = if (endpoint.port == 143 || endpoint.port == 587) TlsMode.STARTTLS else TlsMode.IMPLICIT
                assertEquals(expected, endpoint.tls, "$entry $endpoint")
                assertEquals(endpoint.host.lowercase(), endpoint.host)
            }
        }
    }

    @Test
    fun `entries have notes and login rules`() {
        assertEquals(Login.LOCAL_PART, ProviderTable.byDomain("disroot.org")?.login)
        assertEquals(setOf(Note.APP_PASSWORD, Note.ENABLE_IMAP), ProviderTable.byDomain("yandex.ru")?.notes)
        assertEquals(setOf(Note.ICLOUD_LOGIN), ProviderTable.byDomain("icloud.com")?.mxNotes)
        assertEquals(setOf(Note.ENABLE_IMAP), ProviderTable.byDomain("outlook.com")?.notes)
        assertEquals(Provider.OTHER, ProviderTable.byDomain("fastmail.com")?.provider)
    }

    @Test
    fun `refusals have no servers`() {
        assertEquals(Refusal.BRIDGE, ProviderTable.byDomain("proton.me")?.refusal)
        assertEquals(Refusal.NO_IMAP, ProviderTable.byDomain("tutanota.de")?.refusal)
        assertEquals(Refusal.NO_IMAP, ProviderTable.byDomain("hey.com")?.refusal)
        for (entry in ProviderTable.entries) {
            assertEquals(entry.refusal != null, entry.servers == null, entry.toString())
        }
    }

    @Test
    fun `providerOf pins Google, Microsoft and Riseup hosts`() {
        assertEquals(Provider.GMAIL, ProviderTable.providerOf(servers("imap.gmail.com", "smtp.gmail.com")))
        assertEquals(Provider.GMAIL, ProviderTable.providerOf(servers("imap.googlemail.com", "smtp.googlemail.com")))
        assertEquals(
            Provider.MICROSOFT,
            ProviderTable.providerOf(servers("imap-mail.outlook.com", "smtp.office365.com")),
        )
        assertEquals(Provider.MICROSOFT, ProviderTable.providerOf(servers("outlook.office.com", "smtp-mail.outlook.com")))
        assertEquals(Provider.RISEUP, ProviderTable.providerOf(servers("MAIL.riseup.net", "mail.riseup.net.")))
        assertEquals(Provider.OTHER, ProviderTable.providerOf(servers("imap.example.org", "smtp.example.org")))
        assertEquals(Provider.OTHER, ProviderTable.providerOf(servers("imap.mail.yahoo.com", "smtp.mail.yahoo.com")))
    }

    @Test
    fun `a mixed pair has no provider`() {
        assertNull(ProviderTable.providerOf(servers("imap.gmail.com", "smtp.evil.net")))
        assertNull(ProviderTable.providerOf(servers("imap.example.org", "smtp.office365.com")))
        assertNull(ProviderTable.providerOf(servers("imap.gmail.com", "smtp.office365.com")))
        assertNull(ProviderTable.providerOf(servers("mail.riseup.net", "smtp.gmail.com")))
    }

    @Test
    fun `pinned servers are the built-in ones`() {
        assertEquals(Providers.GMAIL_SERVERS, ProviderTable.pinned(Provider.GMAIL, "me@example.org"))
        assertEquals(Providers.RISEUP_SERVERS, ProviderTable.pinned(Provider.RISEUP, "me@riseup.net"))
        assertEquals(Providers.OUTLOOK_SERVERS, ProviderTable.pinned(Provider.MICROSOFT, "me@hotmail.it"))
        assertEquals(Providers.M365_SERVERS, ProviderTable.pinned(Provider.MICROSOFT, "me@contoso.com"))
        assertNull(ProviderTable.pinned(Provider.OTHER, "me@example.org"))
    }

    @Test
    fun `known hosts and mailbox domains`() {
        assertTrue(ProviderTable.isKnownHost("imap.fastmail.com"))
        assertTrue(ProviderTable.isKnownHost("imap-mail.outlook.com"), "a pinned alias")
        assertTrue(ProviderTable.isKnownHost("smtp.centrum.cz"))
        assertFalse(ProviderTable.isKnownHost("imap.example.org"))
        assertTrue(ProviderTable.isMailboxDomain("gmail.com"))
        assertTrue(ProviderTable.isMailboxDomain("web.de"))
        assertFalse(ProviderTable.isMailboxDomain("example.org"))
        assertFalse(ProviderTable.isMailboxDomain("google.com"), "an MX suffix is not a mailbox domain")
    }

    @Test
    fun `servers that save Sent themselves and servers that need ID`() {
        assertTrue(ProviderTable.serverSavesSent("imap.mail.yahoo.com"))
        assertTrue(ProviderTable.serverSavesSent("imap.aol.com"))
        assertFalse(ProviderTable.serverSavesSent("imap.fastmail.com"))
        assertFalse(ProviderTable.serverSavesSent("imap.example.org"))
        for (host in listOf("imap.163.com", "imap.126.com", "imap.yeah.net")) assertTrue(ProviderTable.needsId(host), host)
        assertFalse(ProviderTable.needsId("imap.gmail.com"))
        assertFalse(ProviderTable.needsId("imap.example.org"))
    }

    @Test
    fun `typos of table domains are found`() {
        assertEquals("gmail.com", ProviderTable.suggest("gmial.com"))
        assertEquals("gmail.com", ProviderTable.suggest("gmail.co"))
        assertEquals("gmail.com", ProviderTable.suggest("gmaill.com"))
        assertEquals("hotmail.com", ProviderTable.suggest("hotmial.com"))
        assertEquals("outlook.com", ProviderTable.suggest("outlook.cm"))
        assertEquals("yahoo.com", ProviderTable.suggest("yahoo.cmo"))
        assertEquals("riseup.net", ProviderTable.suggest("riseup.org"))
    }

    @Test
    fun `correct and unrelated domains get no suggestion`() {
        assertNull(ProviderTable.suggest("gmail.com"))
        assertNull(ProviderTable.suggest("hotmail.de"))
        assertNull(ProviderTable.suggest("contoso.onmicrosoft.com"))
        assertNull(ProviderTable.suggest("example.org"))
        assertNull(ProviderTable.suggest(""))
        // Short table domains are not compared: many real domains are one letter away from them.
        assertNull(ProviderTable.suggest("mf.com"))
        assertNull(ProviderTable.suggest("me.org"))
        assertNull(ProviderTable.suggest("mac.co"))
        assertNull(ProviderTable.suggest("bk.ua"))
        assertNull(ProviderTable.suggest("ya.ro"))
        assertNull(ProviderTable.suggest("aol.co"))
        assertNull(ProviderTable.suggest("gmx.co"))
        assertNull(ProviderTable.suggest("web.com"))
    }

    @Test
    fun `edit distance counts a swap of neighbours once`() {
        assertEquals(0, ProviderTable.editDistance("gmail.com", "gmail.com", 1))
        assertEquals(1, ProviderTable.editDistance("gmial.com", "gmail.com", 1))
        assertEquals(1, ProviderTable.editDistance("gmal.com", "gmail.com", 1))
        assertEquals(2, ProviderTable.editDistance("gmxyz.com", "gmail.com", 1), "capped at limit + 1")
        assertEquals(2, ProviderTable.editDistance("g.com", "gmail.com", 1))
    }

    @Test
    fun `every table domain is unique and lower case`() {
        val domains = ProviderTable.entries.flatMap { it.domains }
        assertEquals(domains.size, domains.toSet().size)
        assertTrue(domains.all { it == it.lowercase() && !it.endsWith('.') })
        assertNotNull(ProviderTable.entries.firstOrNull { it.needsId })
    }
}
