package app.plico.account.discovery

import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The autoconfig samples of the JVM test, parsed with the Android DOM parser (it can refuse the secure-processing
 * feature; the parser must still work and still refuse DTDs).
 */
class AutoconfigDeviceTest {
    @Test
    fun providerFile() {
        assertEquals(
            Servers(Endpoint("imap.fastmail.com", 993, TlsMode.IMPLICIT), Endpoint("smtp.fastmail.com", 465, TlsMode.IMPLICIT)),
            Autoconfig.parse(FASTMAIL_LIKE, "me@example.com"),
        )
    }

    @Test
    fun localPartLogin() {
        val servers = Autoconfig.parse(DISROOT_LIKE, "me@disroot.org")
        assertEquals(Endpoint("disroot.org", 465, TlsMode.IMPLICIT), servers?.smtp)
        assertEquals("me", servers?.login)
    }

    @Test
    fun implicitTlsWins() {
        assertEquals(
            Servers(Endpoint("imap.esiliati.org", 993, TlsMode.IMPLICIT), Endpoint("mail.esiliati.org", 465, TlsMode.IMPLICIT)),
            Autoconfig.parse(ESILIATI, "me@esiliati.org"),
        )
    }

    @Test
    fun socketTypeTlsByPort() {
        val xml = FASTMAIL_LIKE.replace("<port>993</port>\n      <socketType>SSL</socketType>", "<port>143</port>\n      <socketType>TLS</socketType>")
        assertEquals(Endpoint("imap.fastmail.com", 143, TlsMode.STARTTLS), Autoconfig.parse(xml, "me@example.com")?.imap)
    }

    @Test
    fun doctypeAndEntitiesRefused() {
        val body = FASTMAIL_LIKE.substringAfter("?>")
        assertNull(Autoconfig.parse("""<?xml version="1.0"?><!DOCTYPE clientConfig>$body""", "me@example.com"))
        val bomb = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]>""" +
            """<clientConfig>&lol2;</clientConfig>"""
        assertNull(Autoconfig.parse(bomb, "me@example.com"))
        val utf16 = ("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE x>" + body).toByteArray(Charsets.UTF_16LE)
        assertNull(Autoconfig.parse(utf16.toString(Charsets.UTF_8), "me@example.com"))
    }

    @Test
    fun brokenXml() {
        assertNull(Autoconfig.parse(FASTMAIL_LIKE.dropLast(30), "me@example.com"))
        assertNull(Autoconfig.parse("<html><body>404</body></html>", "me@example.com"))
    }

    @Test
    fun unicodeHostToAscii() {
        val xml = FASTMAIL_LIKE.replace("imap.fastmail.com", "imap.bücher.example")
        assertEquals("imap.xn--bcher-kva.example", Autoconfig.parse(xml, "me@example.com")?.imap?.host)
    }

    private companion object {
        const val FASTMAIL_LIKE = """<?xml version="1.0" encoding="UTF-8"?>
<clientConfig version="1.1">
  <emailProvider id="messagingengine.com">
    <domain>example.com</domain>
    <displayName>Hosted mail</displayName>
    <incomingServer type="imap">
      <hostname>imap.fastmail.com</hostname>
      <port>993</port>
      <socketType>SSL</socketType>
      <username>%EMAILADDRESS%</username>
      <authentication>password-cleartext</authentication>
    </incomingServer>
    <outgoingServer type="smtp">
      <hostname>smtp.fastmail.com</hostname>
      <port>465</port>
      <socketType>SSL</socketType>
      <username>%EMAILADDRESS%</username>
      <authentication>password-cleartext</authentication>
    </outgoingServer>
    <documentation url="https://example.com/help"><descr lang="en">Help</descr></documentation>
  </emailProvider>
</clientConfig>
"""

        const val DISROOT_LIKE = """<?xml version="1.0"?>
<clientConfig version="1.1">
  <emailProvider id="disroot.org">
    <incomingServer type="imap">
      <hostname>disroot.org</hostname>
      <port>993</port>
      <socketType>SSL</socketType>
      <authentication>password-cleartext</authentication>
      <username>%EMAILLOCALPART%</username>
    </incomingServer>
    <outgoingServer type="smtp">
      <hostname>disroot.org</hostname>
      <port>587</port>
      <socketType>STARTTLS</socketType>
      <authentication>password-cleartext</authentication>
      <username>%EMAILLOCALPART%</username>
    </outgoingServer>
    <outgoingServer type="smtp">
      <hostname>disroot.org</hostname>
      <port>465</port>
      <socketType>SSL</socketType>
      <authentication>password-cleartext</authentication>
      <username>%EMAILLOCALPART%</username>
    </outgoingServer>
  </emailProvider>
</clientConfig>
"""

        /** The real file of a small mail host (fetched 2026-10-17). */
        const val ESILIATI = """<?xml version="1.0"?>
<clientConfig version="1.1">

    <!-- Outgoing Server -->
    <emailProvider id="esiliati.org">

        <!-- Hosted Domains -->
        <domain>esiliati.org</domain>
        <domain>oziosi.org</domain>
        <domain>ventuordici.org</domain>
        <domain>porcod.io</domain>
        <displayName>esiliati</displayName>
        <displayShortName>esiliati.org</displayShortName>

 <!-- Incoming Mail Servers -->

        <incomingServer type="imap">
            <hostname>imap.%EMAILDOMAIN%</hostname>
            <port>143</port>
            <socketType>STARTTLS</socketType>
            <authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username>
        </incomingServer>

        <incomingServer type="imap">
            <hostname>imap.%EMAILDOMAIN%</hostname>
            <port>993</port>
            <socketType>SSL</socketType>
            <authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username>
        </incomingServer>

        <incomingServer type="pop3">
            <hostname>pop3.%EMAILDOMAIN%</hostname>
            <port>110</port>
            <socketType>STARTTLS</socketType>
            <authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username>
            <pop3>
                <leaveMessagesOnServer>true</leaveMessagesOnServer>
            </pop3>
        </incomingServer>

        <incomingServer type="pop3">
            <hostname>pop3.%EMAILDOMAIN%</hostname>
            <port>995</port>
            <socketType>SSL</socketType>
            <authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username>
            <pop3>
                <leaveMessagesOnServer>true</leaveMessagesOnServer>
            </pop3>
        </incomingServer>

<!-- Outgoing Mail Servers -->

        <outgoingServer type="smtp">
            <hostname>smtp.%EMAILDOMAIN%</hostname>
            <port>587</port>
            <socketType>STARTTLS</socketType>
            <authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username>
        </outgoingServer>

        <outgoingServer type="smtp">
            <hostname>mail.%EMAILDOMAIN%</hostname>
            <port>465</port>
            <socketType>SSL</socketType>
            <authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username>
        </outgoingServer>

	    </emailProvider>
</clientConfig>
"""
    }
}
