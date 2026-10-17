package app.plico.account.discovery

import app.plico.PlicoApp
import app.plico.account.ProviderTable
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.html.HostRules
import app.plico.html.Idn
import app.plico.html.Psl
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode

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
 * leaves the phone. The built-in [ProviderTable] answers first, without a network request.
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
        val entry = ProviderTable.byDomain(domain) ?: return Outcome.NotFound(guess(domain))
        val refusal = entry.refusal
        val servers = entry.servers
        return when {
            refusal != null -> Outcome.Refused(entry.name, refusal)
            servers != null -> Outcome.Found(entry.provider, servers, Source.TABLE, entry.notes)
            else -> Outcome.NotFound(guess(domain))
        }
    }

    companion object {
        /** The Android wiring. Autoconfig and DNS are not used yet: only the table answers. */
        fun of(app: PlicoApp): Discovery = Discovery(
            fetch = { _, _ -> null },
            dns = { _, _ -> null },
            sameSite = { host, domain -> Psl.get(app).let { it.site(host) == it.site(domain) } },
        )

        /**
         * The ASCII lower-case form of a host, or null when it is not a usable server name: an IP address, a single
         * label, or a name under `.local`, `.lan`, `.internal`, `.home.arpa` or `localhost`.
         */
        fun normalizeHost(raw: String): String? = Idn.toAscii(raw.trim())?.takeIf(HostRules::allowed)

        /** `imap.<domain>:993` and `smtp.<domain>:465`, both with implicit TLS. */
        fun guess(domain: String): Servers =
            Servers(Endpoint("imap.$domain", 993, TlsMode.IMPLICIT), Endpoint("smtp.$domain", 465, TlsMode.IMPLICIT))
    }
}
