package app.plico.account.discovery

import app.plico.PlicoApp
import app.plico.account.ProviderTable
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.html.HostRules
import app.plico.html.Idn
import app.plico.html.Psl
import app.plico.html.RemoteFetcher
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the server settings of an address came from. */
enum class Source { TABLE, AUTOCONFIG, SRV, MX, MANUAL, GUESS }

/** The result of [Discovery.discover]. */
sealed interface Outcome {
    data class Found(
        val provider: Provider,
        val servers: Servers,
        val source: Source,
        val notes: Set<ProviderTable.Note>,
    ) : Outcome

    data class Refused(val name: String, val reason: ProviderTable.Refusal) : Outcome

    /**
     * The SERVERS page, filled in with [guess]. [source] GUESS = nothing usable found; SRV or MX = values from
     * unauthenticated DNS outside the mail domain that the user must check.
     */
    data class NotFound(
        val guess: Servers,
        val source: Source = Source.GUESS,
        val notes: Set<ProviderTable.Note> = emptySet(),
    ) : Outcome
}

/**
 * Finds the servers of an email address at setup (DESIGN.md §6). Only the domain is used; the local part never
 * leaves the phone. Order: the built-in [ProviderTable] (no network request), then at the same time the domain's
 * own autoconfig over HTTPS, DNS SRV (RFC 6186/8314) and MX through the system resolver; else a guess.
 *
 * Results that name Google, Microsoft or Riseup hosts always get the built-in servers of that provider, and a pair
 * of one such host and another host is dropped. DNS values for other servers outside the mail domain are never
 * [Outcome.Found]: the user checks them on the server page ([Outcome.NotFound] with source SRV or MX).
 *
 * @param fetch autoconfig GET of a URL for a domain, decoded as UTF-8; null on any failure.
 * @param dns a query by name and record type; null when the query failed.
 * @param sameSite true when a host and the domain have the same registrable domain.
 */
class Discovery(
    private val fetch: suspend (url: String, domain: String) -> String?,
    private val dns: suspend (name: String, type: Int) -> List<DnsRecord>?,
    private val sameSite: (host: String, domain: String) -> Boolean,
) {
    suspend fun discover(email: String): Outcome {
        val domain = normalizeHost(email.substringAfterLast('@')) ?: return Outcome.NotFound(Servers.NONE)
        ProviderTable.byDomain(domain)?.let { return table(it, email, domain) }
        return withTimeoutOrNull(TIMEOUT_MS) { lookUp(email, domain) } ?: Outcome.NotFound(guess(domain))
    }

    private fun table(entry: ProviderTable.Entry, email: String, domain: String): Outcome {
        entry.refusal?.let { return Outcome.Refused(entry.name, it) }
        val servers = entry.servers ?: return Outcome.NotFound(guess(domain))
        return Outcome.Found(entry.provider, withLogin(entry, servers, email), Source.TABLE, entry.notes)
    }

    private suspend fun lookUp(email: String, domain: String): Outcome = coroutineScope {
        val configs = Autoconfig.urls(domain).map { url -> async { fetch(url, domain) } }
        val srv = SRV_LABELS.map { label -> async { dns("$label.$domain", DnsWire.TYPE_SRV) } }
        val mx = async { dns(domain, DnsWire.TYPE_MX) }

        val mxEntry = lowestMx(mx.await())?.let(ProviderTable::byMx)
        val mxOutcome = mxEntry?.let { mxResult(it, email) }
        val (imaps, imap, submissions, submission) = srv.map { it.await() }
        val srvImap = srvEndpoint(imaps, imap)
        val srvSmtp = srvEndpoint(submissions, submission)
        val srvServers = if (srvImap != null && srvSmtp != null) Servers(srvImap, srvSmtp) else null
        val srvProvider = srvServers?.let(ProviderTable::providerOf)

        // a. The domain's own autoconfig, unless the MX moved to Google or Microsoft after it was written.
        val config = configs.firstNotNullOfOrNull { fetched ->
            fetched.await()?.let { Autoconfig.parse(it, email) }?.takeIf { ProviderTable.providerOf(it) != null }
        }
        if (config != null) {
            val provider = ProviderTable.providerOf(config)!!
            val mxProvider = mxEntry?.provider?.takeIf { it == Provider.GMAIL || it == Provider.MICROSOFT }
            if (mxProvider != null && mxProvider != provider && mxOutcome != null) return@coroutineScope mxOutcome
            val servers = ProviderTable.pinned(provider, email) ?: config
            return@coroutineScope Outcome.Found(provider, servers, Source.AUTOCONFIG, emptySet())
        }
        // b. SRV for both protocols, both inside the mail domain.
        if (srvServers != null && srvProvider == Provider.OTHER &&
            sameSite(srvServers.imap.host, domain) && sameSite(srvServers.smtp.host, domain)
        ) {
            return@coroutineScope Outcome.Found(Provider.OTHER, srvServers, Source.SRV, emptySet())
        }
        // c. The MX host of a table provider.
        if (mxOutcome != null) return@coroutineScope mxOutcome
        // d. SRV for both protocols with a host outside the mail domain; a mixed pair is dropped.
        if (srvServers != null && srvProvider != null) {
            return@coroutineScope if (srvProvider == Provider.OTHER) {
                Outcome.NotFound(srvServers, Source.SRV)
            } else {
                Outcome.Found(srvProvider, ProviderTable.pinned(srvProvider, email)!!, Source.SRV, emptySet())
            }
        }
        // e. SRV for one protocol: only a host inside the mail domain fills in the guess.
        val guess = guess(domain)
        if (srvServers == null && (srvImap != null || srvSmtp != null)) {
            val filled = Servers(
                srvImap?.takeIf { sameSite(it.host, domain) } ?: guess.imap,
                srvSmtp?.takeIf { sameSite(it.host, domain) } ?: guess.smtp,
            )
            return@coroutineScope Outcome.NotFound(filled)
        }
        Outcome.NotFound(guess)
    }

    /** A refusal; Google, Microsoft and Riseup with their built-in servers; other providers for the user to check. */
    private fun mxResult(entry: ProviderTable.Entry, email: String): Outcome? {
        entry.refusal?.let { return Outcome.Refused(entry.name, it) }
        ProviderTable.pinned(entry.provider, email)?.let {
            return Outcome.Found(entry.provider, it, Source.MX, entry.notes)
        }
        val servers = entry.servers ?: return null
        return Outcome.NotFound(withLogin(entry, servers, email), Source.MX, entry.notes + entry.mxNotes)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val AUTOCONFIG_TOTAL_MS = 8_000L
        private const val AUTOCONFIG_REDIRECTS = 3

        /** The implicit-TLS label first for each protocol (RFC 8314 §5.1). */
        private val SRV_LABELS = listOf("_imaps._tcp", "_imap._tcp", "_submissions._tcp", "_submission._tcp")

        /** The Android wiring: [RemoteFetcher] for autoconfig, [SystemDns] for SRV and MX, the PSL for sites. */
        fun of(app: PlicoApp): Discovery {
            val sameSite = { host: String, domain: String -> Psl.get(app).let { it.site(host) == it.site(domain) } }
            return Discovery(
                fetch = { url, domain ->
                    withContext(Dispatchers.IO) {
                        val fetched = RemoteFetcher.fetch(autoconfigRequest(url, domain, sameSite))
                        (fetched as? RemoteFetcher.Fetched.Ok)?.resource?.bytes?.toString(Charsets.UTF_8)
                    }
                },
                dns = SystemDns::query,
                sameSite = sameSite,
            )
        }

        /**
         * The autoconfig GET: HTTPS at every hop (HostRules), at most 3 redirects, each inside the site of
         * [domain] or to a host of the provider table; 64 KiB, 8 s, only status 200, no cookies.
         */
        internal fun autoconfigRequest(url: String, domain: String, sameSite: (String, String) -> Boolean) =
            RemoteFetcher.Request(
                url = url,
                accept = "application/xml, text/xml",
                maxBytes = Autoconfig.MAX_CHARS,
                type = { true },
                maxRedirects = AUTOCONFIG_REDIRECTS,
                hop = { sameSite(it.host, domain) || ProviderTable.isKnownHost(it.host) },
                totalMs = AUTOCONFIG_TOTAL_MS,
                blockTrackers = false,
            )

        /**
         * The ASCII lower-case form of a host, or null when it is not a usable server name: an IP address, a single
         * label, or a name under `.local`, `.lan`, `.internal`, `.home.arpa` or `localhost`.
         */
        fun normalizeHost(raw: String): String? = Idn.toAscii(raw.trim())?.takeIf(HostRules::allowed)

        /** `imap.<domain>:993` and `smtp.<domain>:465`, both with implicit TLS. */
        fun guess(domain: String): Servers =
            Servers(Endpoint("imap.$domain", 993, TlsMode.IMPLICIT), Endpoint("smtp.$domain", 465, TlsMode.IMPLICIT))

        /**
         * The server of one protocol from its SRV answers: the implicit-TLS label when it has a usable record, else
         * the STARTTLS label. Lowest priority first, then the highest weight, then answer order. Records with the
         * target `.` or an unusable host are skipped. Ports 993 and 465 are always implicit TLS.
         */
        internal fun srvEndpoint(implicit: List<DnsRecord>?, starttls: List<DnsRecord>?): Endpoint? =
            best(implicit, TlsMode.IMPLICIT) ?: best(starttls, TlsMode.STARTTLS)

        private fun best(records: List<DnsRecord>?, mode: TlsMode): Endpoint? = records.orEmpty()
            .filterIsInstance<DnsRecord.Srv>()
            .filter { it.port in 1..65535 }
            .mapNotNull { record -> normalizeHost(record.target)?.let { record to it } }
            .sortedWith(compareBy({ it.first.priority }, { -it.first.weight }))
            .firstOrNull()
            ?.let { (record, host) ->
                val tls = if (record.port == 993 || record.port == 465) TlsMode.IMPLICIT else mode
                Endpoint(host, record.port, tls)
            }

        /** The usable MX host with the lowest preference value; the first one of equal hosts. */
        internal fun lowestMx(records: List<DnsRecord>?): String? = records.orEmpty()
            .filterIsInstance<DnsRecord.Mx>()
            .mapNotNull { record -> normalizeHost(record.host)?.let { record.preference to it } }
            .minByOrNull { it.first }
            ?.second

        private fun withLogin(entry: ProviderTable.Entry, servers: Servers, email: String): Servers =
            if (entry.login == ProviderTable.Login.LOCAL_PART) servers.copy(login = email.substringBeforeLast('@')) else servers
    }
}
