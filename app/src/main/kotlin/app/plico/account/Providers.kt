package app.plico.account

import app.plico.data.Provider

/** Hardcoded server settings. No autoconfig, MX or ISPDB lookups. */
object Providers {
    data class Server(val host: String, val port: Int)

    data class Spec(
        val provider: Provider,
        val imap: Server,
        val smtp: Server,
        /** Largest encoded message the server accepts. */
        val maxMessageBytes: Long,
        /** True when the client must APPEND its own copy to Sent. */
        val clientSavesSent: Boolean,
        /** First client-mode keepalive interval in minutes. */
        val idleStartMinutes: Int,
        /** Web pages for settings that have no protocol (filters, vacation, aliases, spam). */
        val webSettings: String?,
    )

    val RISEUP = Spec(
        provider = Provider.RISEUP,
        imap = Server("mail.riseup.net", 993),
        smtp = Server("mail.riseup.net", 465),
        maxMessageBytes = 25_600_000,
        clientSavesSent = true,
        idleStartMinutes = 15,
        webSettings = "https://account.riseup.net/",
    )

    val GMAIL = Spec(
        provider = Provider.GMAIL,
        imap = Server("imap.gmail.com", 993),
        smtp = Server("smtp.gmail.com", 465),
        maxMessageBytes = 35_651_584,
        clientSavesSent = false,
        idleStartMinutes = 9,
        webSettings = null,
    )

    fun of(provider: Provider) = when (provider) {
        Provider.RISEUP -> RISEUP
        Provider.GMAIL -> GMAIL
    }

    /** Provider for well-known domains; null for others (a Workspace domain is added as Gmail by the user). */
    fun detect(email: String): Provider? = when (email.substringAfterLast('@').lowercase()) {
        "riseup.net" -> Provider.RISEUP
        "gmail.com", "googlemail.com" -> Provider.GMAIL
        else -> null
    }

    const val GMAIL_SCOPE = "https://mail.google.com/"
    const val APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords"
}
