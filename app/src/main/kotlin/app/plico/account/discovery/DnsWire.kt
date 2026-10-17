package app.plico.account.discovery

/** One answer record of a DNS query; host names are ASCII, lower case, without a trailing dot. */
sealed interface DnsRecord {
    /** RFC 2782. An empty [target] (`.`) means that the domain does not offer the service. */
    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String) : DnsRecord

    data class Mx(val preference: Int, val host: String) : DnsRecord
}
