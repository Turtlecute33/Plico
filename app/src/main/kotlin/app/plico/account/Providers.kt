package app.plico.account

import app.plico.data.Account
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode.IMPLICIT
import app.plico.mail.net.TlsMode.STARTTLS

/** Behaviour per provider kind. Servers are in the account row; the *_SERVERS values are the built-in servers. */
object Providers {
    data class Traits(
        /** Cap for one encoded message; the live SMTP SIZE and APPENDLIMIT can only lower it. */
        val maxMessageBytes: Long,
        /** True when the client must APPEND its own copy to Sent (unless the provider table says the server saves it). */
        val clientSavesSent: Boolean,
        /** Check the newest Sent messages for the Message-ID before that APPEND (the server may have saved a copy). */
        val checkSentCopy: Boolean,
        /** First client-mode keepalive interval in minutes. */
        val idleStartMinutes: Int,
        /** Web page for settings that have no protocol (filters, vacation, aliases, spam). */
        val webSettings: String?,
    )

    val RISEUP = Traits(25_600_000, clientSavesSent = true, checkSentCopy = false, 15, "https://account.riseup.net/")
    val GMAIL = Traits(35_651_584, clientSavesSent = false, checkSentCopy = false, 9, null)

    // Live-check before release: the Microsoft size limit and its Sent Items behaviour.
    val MICROSOFT = Traits(25_000_000, clientSavesSent = false, checkSentCopy = false, 15, null)
    val OTHER = Traits(25_000_000, clientSavesSent = true, checkSentCopy = true, 15, null)

    fun traits(provider: Provider): Traits = when (provider) {
        Provider.RISEUP -> RISEUP
        Provider.GMAIL -> GMAIL
        Provider.MICROSOFT -> MICROSOFT
        Provider.OTHER -> OTHER
    }

    val RISEUP_SERVERS = Servers(Endpoint("mail.riseup.net", 993, IMPLICIT), Endpoint("mail.riseup.net", 465, IMPLICIT))
    val GMAIL_SERVERS = Servers(Endpoint("imap.gmail.com", 993, IMPLICIT), Endpoint("smtp.gmail.com", 465, IMPLICIT))

    /** Outlook.com, Hotmail and Live. */
    val OUTLOOK_SERVERS =
        Servers(Endpoint("outlook.office365.com", 993, IMPLICIT), Endpoint("smtp-mail.outlook.com", 587, STARTTLS))

    /** Microsoft 365 (work and school accounts). */
    val M365_SERVERS =
        Servers(Endpoint("outlook.office365.com", 993, IMPLICIT), Endpoint("smtp.office365.com", 587, STARTTLS))

    const val GMAIL_SCOPE = "https://mail.google.com/"
    const val APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords"
}

val Account.traits: Providers.Traits get() = Providers.traits(provider)
