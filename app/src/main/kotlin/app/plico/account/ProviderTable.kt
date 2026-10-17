package app.plico.account

import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import java.util.Locale

/**
 * The built-in table of mail providers (DESIGN.md §6): servers, notes and refusals by email domain or MX host.
 * The facts come from the documentation of each provider; no text is copied from other mail clients. Entries
 * whose [Entry.provider] is OTHER only supply servers; the account then behaves as any other server.
 *
 * Hosts of Google, Microsoft and Riseup are pinned: an account with such a host always uses the built-in servers
 * of that provider, and a pair with one such host and one other host is refused.
 */
object ProviderTable {
    /** The user name: the full address, or only its local part. */
    enum class Login { ADDRESS, LOCAL_PART }

    /** A note that setup shows before the password field. */
    enum class Note { APP_PASSWORD, ENABLE_IMAP, ICLOUD_LOGIN }

    /** Why setup cannot add an account of this provider. */
    enum class Refusal { NO_IMAP, BRIDGE }

    /** One provider. [servers] null = a refusal. [mx]: MX host suffixes of custom domains that this provider hosts. */
    class Entry(
        val name: String,
        val provider: Provider,
        val domains: Set<String>,
        val mx: List<String>,
        val servers: Servers?,
        val login: Login = Login.ADDRESS,
        val notes: Set<Note> = emptySet(),
        /** Notes that apply only to an MX match (a custom domain). */
        val mxNotes: Set<Note> = emptySet(),
        val refusal: Refusal? = null,
        /** The server saves SMTP-submitted mail in Sent itself: never APPEND. */
        val serverSavesSent: Boolean = false,
        /** The server refuses SELECT until the client sends IMAP ID (NetEase "Unsafe Login"). */
        val needsId: Boolean = false,
    ) {
        override fun toString() = "Entry($name)"
    }

    private val RISEUP_HOSTS = setOf("mail.riseup.net")
    private val GMAIL_HOSTS = setOf("imap.gmail.com", "smtp.gmail.com", "imap.googlemail.com", "smtp.googlemail.com")
    private val MICROSOFT_HOSTS = setOf(
        "outlook.office365.com", "outlook.office.com", "imap-mail.outlook.com", "smtp-mail.outlook.com",
        "smtp.office365.com",
    )

    /** The providers whose hosts are pinned, with their host sets. */
    private val PINNED_HOSTS = mapOf(
        Provider.RISEUP to RISEUP_HOSTS,
        Provider.GMAIL to GMAIL_HOSTS,
        Provider.MICROSOFT to MICROSOFT_HOSTS,
    )

    private val OUTLOOK = Entry(
        "Outlook", Provider.MICROSOFT,
        setOf(
            "outlook.com", "hotmail.com", "live.com", "msn.com", "windowslive.com", "passport.com", "outlook.it",
            "outlook.fr", "outlook.de", "outlook.es", "outlook.co.uk", "hotmail.it", "hotmail.fr", "hotmail.de",
            "hotmail.es", "hotmail.co.uk", "live.it", "live.fr", "live.de", "live.co.uk", "live.nl", "live.be", "live.ca",
        ),
        listOf("olc.protection.outlook.com"),
        Providers.OUTLOOK_SERVERS,
        notes = setOf(Note.ENABLE_IMAP),
    )

    /** Microsoft 365: custom domains by MX, and every `*.onmicrosoft.com` address. */
    private val M365 = Entry(
        "Microsoft 365", Provider.MICROSOFT, emptySet(),
        listOf("mail.protection.outlook.com", "mx.microsoft", "mail.eo.outlook.com"),
        Providers.M365_SERVERS,
    )

    val entries: List<Entry> = listOf(
        Entry("Riseup", Provider.RISEUP, setOf("riseup.net"), emptyList(), Providers.RISEUP_SERVERS),
        Entry(
            "Gmail", Provider.GMAIL, setOf("gmail.com", "googlemail.com"), listOf("google.com", "googlemail.com"),
            Providers.GMAIL_SERVERS,
        ),
        OUTLOOK,
        M365,
        Entry(
            "Yahoo", Provider.OTHER,
            setOf(
                "yahoo.com", "yahoo.co.uk", "yahoo.de", "yahoo.fr", "yahoo.it", "yahoo.es", "yahoo.ca", "yahoo.se",
                "yahoo.co.in", "yahoo.co.nz", "yahoo.com.au", "yahoo.com.ar", "yahoo.com.br", "yahoo.com.mx",
                "ymail.com", "rocketmail.com", "myyahoo.com",
            ),
            listOf("am0.yahoodns.net"),
            Servers(
                Endpoint("imap.mail.yahoo.com", 993, TlsMode.IMPLICIT),
                Endpoint("smtp.mail.yahoo.com", 465, TlsMode.IMPLICIT),
            ),
            notes = setOf(Note.APP_PASSWORD), serverSavesSent = true,
        ),
        Entry(
            "AOL", Provider.OTHER,
            setOf(
                "aol.com", "aim.com", "aol.de", "aol.fr", "aol.it", "aol.es", "aol.se", "aol.co.uk", "aol.co.nz",
                "aol.com.au", "aol.com.ar", "aol.com.br", "aol.com.mx", "netscape.net", "compuserve.com", "cs.com",
                "wmconnect.com",
            ),
            listOf("gm0.yahoodns.net"),
            Servers(Endpoint("imap.aol.com", 993, TlsMode.IMPLICIT), Endpoint("smtp.aol.com", 465, TlsMode.IMPLICIT)),
            notes = setOf(Note.APP_PASSWORD), serverSavesSent = true,
        ),
        Entry(
            "Proton Mail", Provider.OTHER, setOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me"),
            listOf("protonmail.ch"), null, refusal = Refusal.BRIDGE,
        ),
        Entry(
            "Tuta", Provider.OTHER,
            setOf("tuta.com", "tutanota.com", "tutanota.de", "tutamail.com", "tuta.io", "keemail.me"),
            listOf("tutanota.de"), null, refusal = Refusal.NO_IMAP,
        ),
        Entry("HEY", Provider.OTHER, setOf("hey.com"), listOf("app.hey.com"), null, refusal = Refusal.NO_IMAP),
    )

    /** The entry of [domain] (ASCII, lower case); every `*.onmicrosoft.com` domain is Microsoft 365. */
    fun byDomain(domain: String): Entry? {
        val d = norm(domain)
        return entries.firstOrNull { d in it.domains } ?: M365.takeIf { d.endsWith(".onmicrosoft.com") }
    }

    /** The first entry with an MX suffix that is [mxHost] or a parent domain of it. */
    fun byMx(mxHost: String): Entry? {
        val host = norm(mxHost)
        return entries.firstOrNull { entry -> entry.mx.any { host == it || host.endsWith(".$it") } }
    }

    /** The first entry whose IMAP or SMTP host is [host]. */
    fun entryForHost(host: String): Entry? {
        val h = norm(host)
        return entries.firstOrNull { it.servers?.imap?.host == h || it.servers?.smtp?.host == h }
    }

    /**
     * RISEUP, GMAIL or MICROSOFT when both hosts are in that provider's host set; OTHER when neither host is in
     * any of these sets; null when the pair is mixed (setup refuses it).
     */
    fun providerOf(servers: Servers): Provider? {
        val imap = pinnedProvider(servers.imap.host)
        val smtp = pinnedProvider(servers.smtp.host)
        return if (imap == smtp) imap ?: Provider.OTHER else null
    }

    /** The built-in servers of RISEUP, GMAIL and MICROSOFT; null for OTHER. */
    fun pinned(provider: Provider, email: String): Servers? = when (provider) {
        Provider.RISEUP -> Providers.RISEUP_SERVERS
        Provider.GMAIL -> Providers.GMAIL_SERVERS
        Provider.MICROSOFT -> microsoftServers(email)
        Provider.OTHER -> null
    }

    /** True for every IMAP or SMTP host of an entry or of a pinned host set. */
    fun isKnownHost(host: String): Boolean {
        val h = norm(host)
        return entryForHost(h) != null || PINNED_HOSTS.values.any { h in it }
    }

    /** True for the domains of the table: mailbox providers, not the sites of the senders. */
    fun isMailboxDomain(domain: String): Boolean {
        val d = norm(domain)
        return entries.any { d in it.domains }
    }

    /** Outlook.com servers for its consumer domains, else the Microsoft 365 servers. */
    fun microsoftServers(email: String): Servers =
        if (norm(email.substringAfterLast('@')) in OUTLOOK.domains) Providers.OUTLOOK_SERVERS else Providers.M365_SERVERS

    fun serverSavesSent(imapHost: String): Boolean = entryForHost(imapHost)?.serverSavesSent == true

    fun needsId(imapHost: String): Boolean = entryForHost(imapHost)?.needsId == true

    /** Typo check before discovery: a table domain that [domain] probably meant, else null. */
    fun suggest(domain: String): String? = null

    private fun pinnedProvider(host: String): Provider? {
        val h = norm(host)
        return PINNED_HOSTS.entries.firstOrNull { h in it.value }?.key
    }

    private fun norm(host: String) = host.trim().lowercase(Locale.ROOT).removeSuffix(".")
}
