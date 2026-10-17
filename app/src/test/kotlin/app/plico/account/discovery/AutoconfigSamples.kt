package app.plico.account.discovery

/**
 * Autoconfig files in the Thunderbird format for the parser tests. [ESILIATI] is the real file of a small mail
 * host (fetched 2026-10-17); the others are written for the tests.
 */
internal object AutoconfigSamples {
    /** One provider for many domains; the IMAP and SMTP hosts are the provider's. */
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
    <incomingServer type="pop3">
      <hostname>pop.fastmail.com</hostname>
      <port>995</port>
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
    <documentation url="https://example.com/help">
      <descr lang="en">Help</descr>
    </documentation>
  </emailProvider>
</clientConfig>
"""

    /** The user name is the local part; SMTP lists STARTTLS before implicit TLS. */
    const val DISROOT_LIKE = """<?xml version="1.0"?>
<clientConfig version="1.1">
  <emailProvider id="disroot.org">
    <domain>disroot.org</domain>
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

    /** A server file with STARTTLS listed first for both protocols and POP3 entries. */
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

    /** A file with the given IMAP and SMTP sections. */
    fun config(imap: String, smtp: String) = """<?xml version="1.0"?>
<clientConfig version="1.1"><emailProvider id="example.org"><domain>example.org</domain>
$imap
$smtp
</emailProvider></clientConfig>"""

    fun imap(host: String, port: Int, socketType: String, auth: String = "password-cleartext", user: String = "%EMAILADDRESS%") =
        section("incomingServer", "imap", host, port, socketType, auth, user)

    fun smtp(host: String, port: Int, socketType: String, auth: String = "password-cleartext", user: String = "%EMAILADDRESS%") =
        section("outgoingServer", "smtp", host, port, socketType, auth, user)

    private fun section(tag: String, type: String, host: String, port: Int, socketType: String, auth: String, user: String) =
        """<$tag type="$type"><hostname>$host</hostname><port>$port</port><socketType>$socketType</socketType>""" +
            """<authentication>$auth</authentication><username>$user</username></$tag>"""
}
