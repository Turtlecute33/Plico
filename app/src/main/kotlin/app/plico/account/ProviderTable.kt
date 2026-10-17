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
        // Live-check before release: Yahoo and AOL save SMTP-submitted mail in Sent.
        Entry(
            "Yahoo", Provider.OTHER,
            setOf(
                "yahoo.com", "yahoo.co.uk", "yahoo.de", "yahoo.fr", "yahoo.it", "yahoo.es", "yahoo.ca", "yahoo.se",
                "yahoo.co.in", "yahoo.co.nz", "yahoo.com.au", "yahoo.com.ar", "yahoo.com.br", "yahoo.com.mx",
                "ymail.com", "rocketmail.com", "myyahoo.com",
            ),
            listOf("am0.yahoodns.net"),
            servers("imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465),
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
            servers("imap.aol.com", 993, "smtp.aol.com", 465),
            notes = setOf(Note.APP_PASSWORD), serverSavesSent = true,
        ),
        // A custom domain on iCloud signs in with the @icloud.com address, not with the custom address.
        Entry(
            "iCloud", Provider.OTHER, setOf("icloud.com", "me.com", "mac.com"), listOf("mail.icloud.com"),
            servers("imap.mail.me.com", 993, "smtp.mail.me.com", 587),
            notes = setOf(Note.APP_PASSWORD), mxNotes = setOf(Note.ICLOUD_LOGIN),
        ),
        Entry(
            "Fastmail", Provider.OTHER, setOf("fastmail.com", "fastmail.fm"), listOf("messagingengine.com"),
            servers("imap.fastmail.com", 993, "smtp.fastmail.com", 465),
            notes = setOf(Note.APP_PASSWORD),
        ),
        zoho("Zoho", setOf("zoho.com", "zohomail.com"), "zoho.com"),
        zoho("Zoho EU", setOf("zoho.eu", "zohomail.eu"), "zoho.eu"),
        zoho("Zoho IN", setOf("zoho.in", "zohomail.in"), "zoho.in"),
        zoho("Zoho AU", setOf("zoho.com.au", "zohomail.com.au"), "zoho.com.au"),
        zoho("Zoho JP", setOf("zoho.jp", "zohomail.jp"), "zoho.jp"),
        zoho("Zoho CA", setOf("zohocloud.ca", "zohomailcloud.ca"), "zohocloud.ca"),
        // Custom domains on Zoho use the "pro" servers. The MX suffix zoho.com does not match mx.zoho.com.au.
        zohoCustom("Zoho (custom domain)", "zoho.com"),
        zohoCustom("Zoho EU (custom domain)", "zoho.eu"),
        zohoCustom("Zoho IN (custom domain)", "zoho.in"),
        zohoCustom("Zoho AU (custom domain)", "zoho.com.au"),
        Entry(
            "GMX", Provider.OTHER,
            setOf("gmx.net", "gmx.de", "gmx.at", "gmx.ch", "gmx.eu", "gmx.biz", "gmx.org", "gmx.info"), emptyList(),
            servers("imap.gmx.net", 993, "mail.gmx.net", 465),
            notes = setOf(Note.ENABLE_IMAP),
        ),
        Entry(
            "GMX (international)", Provider.OTHER,
            setOf(
                "gmx.com", "gmx.us", "gmx.co.uk", "gmx.es", "gmx.fr", "gmx.ca", "gmx.it", "gmx.pt", "gmx.ie", "gmx.se",
            ),
            emptyList(),
            servers("imap.gmx.com", 993, "mail.gmx.com", 465),
            notes = setOf(Note.ENABLE_IMAP),
        ),
        // Live-check before release: WEB.DE documents SMTP on 587 with STARTTLS.
        Entry(
            "WEB.DE", Provider.OTHER, setOf("web.de"), emptyList(),
            servers("imap.web.de", 993, "smtp.web.de", 587),
            notes = setOf(Note.ENABLE_IMAP),
        ),
        Entry(
            "mailbox.org", Provider.OTHER, setOf("mailbox.org"), listOf("mailbox.org"),
            servers("imap.mailbox.org", 993, "smtp.mailbox.org", 465),
        ),
        Entry(
            "Posteo", Provider.OTHER,
            setOf(
                "posteo.de", "posteo.net", "posteo.at", "posteo.ch", "posteo.eu", "posteo.org", "posteo.be", "posteo.uk",
                "posteo.us", "posteo.nl", "posteo.com",
            ),
            listOf("posteo.de"),
            servers("posteo.de", 993, "posteo.de", 465),
        ),
        Entry(
            "Disroot", Provider.OTHER, setOf("disroot.org"), emptyList(),
            servers("disroot.org", 993, "disroot.org", 465),
            login = Login.LOCAL_PART,
        ),
        Entry(
            "Infomaniak", Provider.OTHER, setOf("ik.me", "etik.com", "ikmail.com"), listOf("infomaniak.ch"),
            servers("mail.infomaniak.com", 993, "mail.infomaniak.com", 465),
            notes = setOf(Note.APP_PASSWORD),
        ),
        Entry(
            "Yandex", Provider.OTHER,
            setOf("yandex.ru", "yandex.com", "yandex.by", "yandex.kz", "yandex.ua", "ya.ru", "narod.ru"),
            listOf("yandex.ru", "yandex.net"),
            servers("imap.yandex.com", 993, "smtp.yandex.com", 465),
            notes = setOf(Note.APP_PASSWORD, Note.ENABLE_IMAP),
        ),
        Entry(
            "Mail.ru", Provider.OTHER, setOf("mail.ru", "inbox.ru", "list.ru", "bk.ru"), listOf("mail.ru"),
            servers("imap.mail.ru", 993, "smtp.mail.ru", 465),
            notes = setOf(Note.APP_PASSWORD),
        ),
        // NetEase refuses SELECT ("Unsafe Login") until the client sends IMAP ID; Plico sends a minimal one.
        netEase("NetEase 163", "163.com"),
        netEase("NetEase 126", "126.com"),
        netEase("NetEase Yeah", "yeah.net"),
        Entry(
            "Libero", Provider.OTHER, setOf("libero.it", "iol.it", "blu.it", "inwind.it", "giallo.it"), emptyList(),
            servers("imapmail.libero.it", 993, "smtp.libero.it", 465),
        ),
        Entry(
            "Virgilio", Provider.OTHER, setOf("virgilio.it"), emptyList(),
            servers("in.virgilio.it", 993, "out.virgilio.it", 465),
        ),
        Entry("TIM", Provider.OTHER, setOf("tim.it"), emptyList(), servers("imap.tim.it", 143, "smtp.tim.it", 587)),
        Entry("Aruba", Provider.OTHER, setOf("aruba.it"), emptyList(), servers("imap.aruba.it", 993, "smtp.aruba.it", 465)),
        Entry(
            "Seznam", Provider.OTHER, setOf("seznam.cz", "email.cz", "post.cz", "spoluzaci.cz"), emptyList(),
            servers("imap.seznam.cz", 993, "smtp.seznam.cz", 465),
        ),
        Entry(
            "Centrum", Provider.OTHER, setOf("centrum.cz"), emptyList(),
            servers("imap.centrum.cz", 993, "smtp.centrum.cz", 465),
        ),
        Entry(
            "Atlas", Provider.OTHER, setOf("atlas.cz"), emptyList(),
            servers("imap.atlas.cz", 993, "smtp.centrum.cz", 465),
        ),
        Entry("Volny", Provider.OTHER, setOf("volny.cz"), emptyList(), servers("imap.volny.cz", 993, "smtp.volny.cz", 465)),
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

    /**
     * Typo check before discovery: a table domain that [domain] probably meant, else null. A table domain of 8 or
     * more characters at an edit distance of 1 (a swap of two neighbours counts 1: `gmial.com`), or a table domain
     * with the same first label of 5 or more characters and another suffix (`gmail.co`). The first match wins.
     * Shorter domains (me.com, bk.ru) are not compared, because many real domains are near them.
     */
    fun suggest(domain: String): String? {
        val d = norm(domain)
        if (d.isEmpty() || byDomain(d) != null) return null
        val first = d.substringBefore('.')
        return entries.asSequence().flatMap { it.domains }.firstOrNull { known ->
            val knownFirst = known.substringBefore('.')
            (known.length >= SUGGEST_MIN_LENGTH && editDistance(d, known, limit = 1) == 1) ||
                (knownFirst.length >= SUGGEST_MIN_LABEL && first == knownFirst && d != known)
        }
    }

    /** RISEUP, GMAIL or MICROSOFT when [host] is in that provider's host set, else null. */
    internal fun pinnedProvider(host: String): Provider? {
        val h = norm(host)
        return PINNED_HOSTS.entries.firstOrNull { h in it.value }?.key
    }

    /**
     * Optimal string alignment distance (Damerau–Levenshtein without repeated edits of one part) of [a] and [b];
     * any value above [limit] is reported as `limit + 1`.
     */
    internal fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var before = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) value = minOf(value, before[j - 2] + 1)
                current[j] = value
            }
            before = previous
            previous = current
        }
        return minOf(previous[b.length], limit + 1)
    }

    private const val SUGGEST_MIN_LENGTH = 8
    private const val SUGGEST_MIN_LABEL = 5

    /** Port 993 or 465: implicit TLS; 143 or 587: STARTTLS. */
    private fun endpoint(host: String, port: Int) =
        Endpoint(host, port, if (port == 143 || port == 587) TlsMode.STARTTLS else TlsMode.IMPLICIT)

    private fun servers(imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int) =
        Servers(endpoint(imapHost, imapPort), endpoint(smtpHost, smtpPort))

    private fun zoho(name: String, domains: Set<String>, region: String) = Entry(
        name, Provider.OTHER, domains, emptyList(), servers("imap.$region", 993, "smtp.$region", 465),
        notes = setOf(Note.ENABLE_IMAP),
    )

    private fun zohoCustom(name: String, region: String) = Entry(
        name, Provider.OTHER, emptySet(), listOf(region), servers("imappro.$region", 993, "smtppro.$region", 465),
        notes = setOf(Note.ENABLE_IMAP),
    )

    // Live-check before release: NetEase accepts the minimal ID.
    private fun netEase(name: String, domain: String) = Entry(
        name, Provider.OTHER, setOf(domain), emptyList(), servers("imap.$domain", 993, "smtp.$domain", 465),
        notes = setOf(Note.APP_PASSWORD, Note.ENABLE_IMAP), needsId = true,
    )

    private fun norm(host: String) = host.trim().lowercase(Locale.ROOT).removeSuffix(".")
}
