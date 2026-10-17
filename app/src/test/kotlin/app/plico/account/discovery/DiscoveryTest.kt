package app.plico.account.discovery

import app.plico.account.ProviderTable.Note
import app.plico.account.ProviderTable.Refusal
import app.plico.account.Providers
import app.plico.account.discovery.AutoconfigSamples.config
import app.plico.account.discovery.AutoconfigSamples.imap
import app.plico.account.discovery.AutoconfigSamples.smtp
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryTest {
    private val email = "secret.name@example.org"
    private val domain = "example.org"
    private val autoconfigUrl = "https://autoconfig.example.org/mail/config-v1.1.xml"
    private val wellKnownUrl = "https://example.org/.well-known/autoconfig/mail/config-v1.1.xml"

    private val pages = HashMap<String, String>()
    private val records = HashMap<String, List<DnsRecord>>()
    private val fetched = ArrayList<String>()
    private val asked = ArrayList<String>()
    private var hang = false

    /** Registrable domain = the last two labels; enough for these names. */
    private val sameSite = { host: String, d: String -> host.split('.').takeLast(2) == d.split('.').takeLast(2) }

    private val discovery = Discovery(
        fetch = { url, _ ->
            fetched += url
            if (hang) awaitCancellation()
            pages[url]
        },
        dns = { name, type ->
            asked += "$name/$type"
            if (hang) awaitCancellation()
            records["$name/$type"]
        },
        sameSite = sameSite,
    )

    private fun srv(label: String, vararg list: DnsRecord.Srv) {
        records["$label.$domain/${DnsWire.TYPE_SRV}"] = list.toList()
    }

    private fun mx(vararg hosts: String) {
        records["$domain/${DnsWire.TYPE_MX}"] = hosts.mapIndexed { i, host -> DnsRecord.Mx(10 * (i + 1), host) }
    }

    private fun ssl(host: String, port: Int) = Endpoint(host, port, TlsMode.IMPLICIT)

    private fun starttls(host: String, port: Int) = Endpoint(host, port, TlsMode.STARTTLS)

    private val guess = Servers(ssl("imap.example.org", 993), ssl("smtp.example.org", 465))

    private val ownConfig = config(imap("mail.example.org", 993, "SSL"), smtp("mail.example.org", 465, "SSL"))

    private fun discover(address: String = email) = runTest { result = discovery.discover(address) }

    private var result: Outcome? = null

    @Test
    fun `table domains need no request`() {
        discover("me@gmail.com")
        assertEquals(Outcome.Found(Provider.GMAIL, Providers.GMAIL_SERVERS, Source.TABLE, emptySet()), result)
        discover("me@Hotmail.IT")
        assertEquals(Outcome.Found(Provider.MICROSOFT, Providers.OUTLOOK_SERVERS, Source.TABLE, setOf(Note.ENABLE_IMAP)), result)
        discover("me@proton.me")
        assertEquals(Outcome.Refused("Proton Mail", Refusal.BRIDGE), result)
        discover("me@fastmail.com")
        assertEquals(Source.TABLE, (result as Outcome.Found).source)
        assertEquals(setOf(Note.APP_PASSWORD), (result as Outcome.Found).notes)
        assertEquals(emptyList(), fetched)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a table entry with the local part as user name`() {
        discover("me.name@disroot.org")
        assertEquals("me.name", ((result as Outcome.Found).servers.login))
    }

    @Test
    fun `an unusable domain gives empty servers`() {
        for (address in listOf("me@localhost", "me@192.168.1.1", "me@printer", "me@nas.local", "me")) {
            discover(address)
            assertEquals(Outcome.NotFound(Servers.NONE), result, address)
        }
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun `nothing found gives the guess`() {
        discover()
        assertEquals(Outcome.NotFound(guess), result)
        assertEquals(setOf(autoconfigUrl, wellKnownUrl), fetched.toSet())
        assertEquals(
            setOf("_imaps._tcp", "_imap._tcp", "_submissions._tcp", "_submission._tcp").map { "$it.$domain/33" }.toSet() +
                "$domain/15",
            asked.toSet(),
        )
    }

    @Test
    fun `the local part never leaves the phone`() {
        pages[autoconfigUrl] = config(
            imap("imap.%EMAILDOMAIN%", 993, "SSL", user = "%EMAILLOCALPART%"),
            smtp("smtp.%EMAILDOMAIN%", 465, "SSL"),
        )
        discover()
        assertEquals("secret.name", (result as Outcome.Found).servers.login, "used on the phone only")
        assertTrue((fetched + asked).none { "secret" in it || '@' in it }, (fetched + asked).toString())
    }

    @Test
    fun `autoconfig of the domain comes first`() {
        pages[autoconfigUrl] = ownConfig
        srv("_imaps._tcp", DnsRecord.Srv(0, 1, 993, "imap.example.org"))
        srv("_submissions._tcp", DnsRecord.Srv(0, 1, 465, "smtp.example.org"))
        mx("mx.zoho.com")
        discover()
        assertEquals(
            Outcome.Found(Provider.OTHER, Servers(ssl("mail.example.org", 993), ssl("mail.example.org", 465)), Source.AUTOCONFIG, emptySet()),
            result,
        )
    }

    @Test
    fun `the second autoconfig URL is used when the first has nothing usable`() {
        pages[autoconfigUrl] = "<html>Not found</html>"
        pages[wellKnownUrl] = ownConfig
        discover()
        assertEquals(Source.AUTOCONFIG, (result as Outcome.Found).source)
        pages.remove(autoconfigUrl)
        discover()
        assertEquals(Source.AUTOCONFIG, (result as Outcome.Found).source)
    }

    @Test
    fun `autoconfig naming Microsoft hosts gets the built-in servers`() {
        pages[autoconfigUrl] = config(
            imap("imap-mail.outlook.com", 993, "SSL", auth = "OAuth2"),
            smtp("smtp-mail.outlook.com", 587, "STARTTLS", auth = "OAuth2"),
        )
        discover()
        assertEquals(Outcome.Found(Provider.MICROSOFT, Providers.M365_SERVERS, Source.AUTOCONFIG, emptySet()), result)
    }

    @Test
    fun `a mixed autoconfig is dropped`() {
        pages[autoconfigUrl] = config(imap("imap.gmail.com", 993, "SSL"), smtp("smtp.evil.net", 465, "SSL"))
        discover()
        assertEquals(Outcome.NotFound(guess), result)
    }

    @Test
    fun `a stale autoconfig loses to a Google or Microsoft MX`() {
        pages[autoconfigUrl] = ownConfig
        mx("aspmx.l.google.com", "alt1.aspmx.l.google.com")
        discover()
        assertEquals(Outcome.Found(Provider.GMAIL, Providers.GMAIL_SERVERS, Source.MX, emptySet()), result)
        mx("example-org.mail.protection.outlook.com")
        discover()
        assertEquals(Outcome.Found(Provider.MICROSOFT, Providers.M365_SERVERS, Source.MX, emptySet()), result)
    }

    @Test
    fun `autoconfig and MX of the same provider agree`() {
        pages[autoconfigUrl] = config(imap("imap.gmail.com", 993, "SSL"), smtp("smtp.gmail.com", 465, "SSL"))
        mx("aspmx.l.google.com")
        discover()
        assertEquals(Outcome.Found(Provider.GMAIL, Providers.GMAIL_SERVERS, Source.AUTOCONFIG, emptySet()), result)
    }

    @Test
    fun `SRV inside the domain is found`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 1, 993, "imap.example.org"))
        srv("_imap._tcp", DnsRecord.Srv(0, 1, 143, "other.example.org"))
        srv("_submissions._tcp")
        srv("_submission._tcp", DnsRecord.Srv(0, 1, 587, "smtp.example.org"))
        mx("mx.zoho.com")
        discover()
        assertEquals(
            Outcome.Found(Provider.OTHER, Servers(ssl("imap.example.org", 993), starttls("smtp.example.org", 587)), Source.SRV, emptySet()),
            result,
        )
    }

    @Test
    fun `ports 993 and 465 are implicit TLS for every label`() {
        srv("_imap._tcp", DnsRecord.Srv(0, 0, 993, "imap.example.org"))
        srv("_submission._tcp", DnsRecord.Srv(0, 0, 465, "smtp.example.org"))
        discover()
        assertEquals(Servers(ssl("imap.example.org", 993), ssl("smtp.example.org", 465)), (result as Outcome.Found).servers)
    }

    @Test
    fun `the implicit-TLS label wins only with a usable record`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 0, ""))
        srv("_imap._tcp", DnsRecord.Srv(0, 0, 143, "imap.example.org"))
        srv("_submissions._tcp", DnsRecord.Srv(0, 0, 465, "localhost"))
        srv("_submission._tcp", DnsRecord.Srv(0, 0, 587, "smtp.example.org"))
        discover()
        assertEquals(Servers(starttls("imap.example.org", 143), starttls("smtp.example.org", 587)), (result as Outcome.Found).servers)
    }

    @Test
    fun `MX of a table provider`() {
        mx("aspmx.l.google.com")
        discover()
        assertEquals(Outcome.Found(Provider.GMAIL, Providers.GMAIL_SERVERS, Source.MX, emptySet()), result)
        mx("example-org.mail.protection.outlook.com")
        discover()
        assertEquals(Outcome.Found(Provider.MICROSOFT, Providers.M365_SERVERS, Source.MX, emptySet()), result)
        mx("mail.protonmail.ch")
        discover()
        assertEquals(Outcome.Refused("Proton Mail", Refusal.BRIDGE), result)
    }

    @Test
    fun `MX of another table provider goes to the server page`() {
        mx("mx.zoho.com")
        discover()
        assertEquals(
            Outcome.NotFound(Servers(ssl("imappro.zoho.com", 993), ssl("smtppro.zoho.com", 465)), Source.MX, setOf(Note.ENABLE_IMAP)),
            result,
        )
        mx("mx01.mail.icloud.com")
        discover()
        val icloud = result as Outcome.NotFound
        assertEquals(Source.MX, icloud.source)
        assertEquals("imap.mail.me.com", icloud.guess.imap.host)
        assertEquals(setOf(Note.APP_PASSWORD, Note.ICLOUD_LOGIN), icloud.notes)
    }

    @Test
    fun `only the lowest usable MX counts`() {
        records["$domain/${DnsWire.TYPE_MX}"] = listOf(
            DnsRecord.Mx(20, "mx.zoho.com"),
            DnsRecord.Mx(1, "mx.local"),
            DnsRecord.Mx(5, ""),
            DnsRecord.Mx(10, "aspmx.l.google.com"),
        )
        discover()
        assertEquals(Provider.GMAIL, (result as Outcome.Found).provider)
    }

    @Test
    fun `MX wins over SRV outside the domain`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 993, "imap.hoster.net"))
        srv("_submissions._tcp", DnsRecord.Srv(0, 0, 465, "smtp.hoster.net"))
        mx("mx.zoho.eu")
        discover()
        assertEquals(Source.MX, (result as Outcome.NotFound).source)
        assertEquals("imappro.zoho.eu", (result as Outcome.NotFound).guess.imap.host)
    }

    @Test
    fun `SRV outside the domain goes to the server page`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 993, "imap.hoster.net"))
        srv("_submissions._tcp", DnsRecord.Srv(0, 0, 465, "smtp.example.org"))
        discover()
        assertEquals(
            Outcome.NotFound(Servers(ssl("imap.hoster.net", 993), ssl("smtp.example.org", 465)), Source.SRV),
            result,
        )
    }

    @Test
    fun `SRV with a Google and another host is never Gmail`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 993, "imap.gmail.com"))
        srv("_submissions._tcp", DnsRecord.Srv(0, 0, 465, "smtp.evil.net"))
        discover()
        assertEquals(Outcome.NotFound(guess), result)
    }

    @Test
    fun `SRV with Google hosts gets the built-in servers`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 993, "imap.googlemail.com"))
        srv("_submission._tcp", DnsRecord.Srv(0, 0, 587, "smtp.gmail.com"))
        discover()
        assertEquals(Outcome.Found(Provider.GMAIL, Providers.GMAIL_SERVERS, Source.SRV, emptySet()), result)
    }

    @Test
    fun `unusable SRV targets are dropped`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 993, "10.0.0.1"))
        srv("_submissions._tcp", DnsRecord.Srv(0, 0, 465, "smtp.lan"))
        discover()
        assertEquals(Outcome.NotFound(guess), result)
    }

    @Test
    fun `SRV for one protocol fills in the guess only inside the domain`() {
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 993, "mail.example.org"))
        discover()
        assertEquals(Outcome.NotFound(Servers(ssl("mail.example.org", 993), guess.smtp)), result)
        srv("_imaps._tcp", DnsRecord.Srv(0, 0, 993, "mail.hoster.net"))
        discover()
        assertEquals(Outcome.NotFound(guess), result)
    }

    @Test
    fun `failed DNS and fetches give the guess`() {
        discover()
        assertEquals(Outcome.NotFound(guess), result)
    }

    @Test
    fun `the whole lookup ends after 10 seconds`() {
        hang = true
        discover()
        assertEquals(Outcome.NotFound(guess), result)
    }

    @Test
    fun `autoconfig hops stay inside the site or go to table hosts`() {
        val request = Discovery.autoconfigRequest(autoconfigUrl, domain, sameSite)
        fun hop(url: String) = request.hop(URI(url).toURL())
        assertTrue(hop(autoconfigUrl))
        assertTrue(hop("https://www.example.org/autoconfig.xml"))
        assertTrue(hop("https://imap.fastmail.com/config"))
        assertFalse(hop("https://evil.net/mail/config-v1.1.xml"))
        assertFalse(hop("https://example.org.evil.net/x"))
        assertEquals(3, request.maxRedirects)
        assertEquals(65_536, request.maxBytes)
        assertEquals(8_000, request.totalMs)
        assertFalse(request.blockTrackers)
        assertTrue(request.type("text/html"), "the parser decides")
    }
}
