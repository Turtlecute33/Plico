package app.plico.account.discovery

import app.plico.account.ProviderTable
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * The domain's own autoconfig file (the Thunderbird `config-v1.1.xml` format), fetched over HTTPS from the mail
 * domain itself. No third-party database is asked, and the URL has no address in it.
 */
object Autoconfig {
    /** The largest file that is read. */
    const val MAX_CHARS = 64 * 1024

    private val forbidden = Regex("<!(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
    private val passwordMethods = setOf("password-cleartext", "plain", "password-encrypted", "secure")

    /** The two autoconfig URLs of [domain], in the order of use. HTTPS only, without the address. */
    fun urls(domain: String): List<String> = listOf(
        "https://autoconfig.$domain/mail/config-v1.1.xml",
        "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
    )

    /**
     * The IMAP and SMTP servers in [xml] for [email]; null when the file is refused or has no usable pair.
     * [Servers.login] is the user name when it is not the address. Refused before parsing: more than [MAX_CHARS]
     * characters, a DOCTYPE or ENTITY declaration (no entity expansion is possible) or a NUL character.
     *
     * Per type, the first entry with implicit TLS wins; a STARTTLS entry only when no implicit-TLS entry passes
     * (RFC 8314 §5.1). An entry passes when its socket type is SSL, STARTTLS or TLS (never plain), its port is
     * 1..65535, its host is a usable server name without the address placeholders and it has a password method
     * (OAuth2 only for Google and Microsoft hosts). Placeholders are replaced in user names and host names.
     */
    fun parse(xml: String, email: String): Servers? {
        val text = xml.removePrefix("\uFEFF")
        if (text.length > MAX_CHARS || forbidden.containsMatchIn(text) || '\u0000' in text) return null
        val root = try {
            builder().parse(InputSource(StringReader(text))).documentElement
        } catch (_: Exception) {
            // Any parser failure: SAXException, IOException, or a runtime exception of a platform parser.
            return null
        } ?: return null
        val local = email.substringBeforeLast('@')
        val domain = email.substringAfterLast('@').let { Discovery.normalizeHost(it) ?: it.lowercase(Locale.ROOT) }
        val fill = { value: String ->
            value.replace("%EMAILADDRESS%", email).replace("%EMAILLOCALPART%", local).replace("%EMAILDOMAIN%", domain)
        }
        val imap = pick(root.elements("incomingServer").filter { it.getAttribute("type").equals("imap", true) }, fill)
        val smtp = pick(root.elements("outgoingServer").filter { it.getAttribute("type").equals("smtp", true) }, fill)
        if (imap == null || smtp == null) return null
        val user = imap.second?.takeIf { it.isNotBlank() && !it.equals(email, ignoreCase = true) }
        return Servers(imap.first, smtp.first, user)
    }

    /** The TLS mode for a socket type and port; null for plain or an unknown type. */
    internal fun tlsOf(socketType: String, port: Int): TlsMode? = when (socketType.trim().uppercase(Locale.ROOT)) {
        "SSL" -> TlsMode.IMPLICIT
        "STARTTLS" -> TlsMode.STARTTLS
        "TLS" -> tlsByPort(port)
        else -> null
    }

    /** 143 and 587: STARTTLS; every other port (993, 465 …): implicit TLS. The same rule as for SRV records. */
    internal fun tlsByPort(port: Int): TlsMode = if (port == 143 || port == 587) TlsMode.STARTTLS else TlsMode.IMPLICIT

    /** The first passing entry with implicit TLS, else the first passing STARTTLS entry; with its user name. */
    private fun pick(entries: List<Element>, fill: (String) -> String): Pair<Endpoint, String?>? {
        val passing = entries.mapNotNull { entry(it, fill) }
        return passing.firstOrNull { it.first.tls == TlsMode.IMPLICIT } ?: passing.firstOrNull()
    }

    private fun entry(server: Element, fill: (String) -> String): Pair<Endpoint, String?>? {
        // A host name with the address in it would send the local part to DNS: refused.
        val rawHost = server.child("hostname")?.takeIf { "%EMAILADDRESS%" !in it && "%EMAILLOCALPART%" !in it }
        val host = rawHost?.let(fill)?.let(Discovery::normalizeHost) ?: return null
        val port = server.child("port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val tls = server.child("socketType")?.let { tlsOf(it, port) } ?: return null
        val methods = server.elements("authentication").map { it.textContent.trim().lowercase(Locale.ROOT) }
        val oauthHost = ProviderTable.pinnedProvider(host).let { it == Provider.GMAIL || it == Provider.MICROSOFT }
        if (methods.none { it in passwordMethods || (it == "oauth2" && oauthHost) }) return null
        return Endpoint(host, port, tls) to server.child("username")?.let(fill)?.trim()
    }

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        isExpandEntityReferences = false
        isXIncludeAware = false
        // Android's parser refuses some features; the string checks above already stop DTDs and entities.
        secure(XMLConstants.FEATURE_SECURE_PROCESSING)
        secure("http://apache.org/xml/features/disallow-doctype-decl")
    }.newDocumentBuilder()

    private fun DocumentBuilderFactory.secure(feature: String) {
        try {
            setFeature(feature, true)
        } catch (_: ParserConfigurationException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    /** The child elements of this element named [name], in document order, at any depth. */
    private fun Element.elements(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** The text of the first direct child element named [name]. */
    private fun Element.child(name: String): String? {
        var node = firstChild
        while (node != null) {
            if (node is Element && node.tagName == name) return node.textContent
            node = node.nextSibling
        }
        return null
    }
}
