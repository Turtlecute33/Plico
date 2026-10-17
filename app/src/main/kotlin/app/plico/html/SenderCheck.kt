package app.plico.html

import androidx.annotation.StringRes
import app.plico.R
import app.plico.data.Auth
import app.plico.mail.mime.Addresses

/** A reason to look at the sender twice (DESIGN.md §11.4). [text] is the banner text. */
enum class SenderWarning(@StringRes val text: Int) {
    /** Gmail's `Authentication-Results`: DMARC failed, or SPF and DKIM both failed. */
    AUTH_FAILED(R.string.html_sender_auth_failed),

    /** The display name contains an address other than the sender's. */
    NAME_HAS_OTHER_ADDRESS(R.string.html_sender_name_address),

    /** The display name contains a domain with another registrable domain than the sender's. */
    NAME_HAS_OTHER_DOMAIN(R.string.html_sender_name_domain),

    /** A Reply-To address has another registrable domain than the sender's. */
    REPLY_TO_OTHER_DOMAIN(R.string.html_sender_reply_to),

    /** No earlier message from this address is stored. Subtle. */
    FIRST_CONTACT(R.string.html_sender_first_contact),
}

/**
 * What the message view may do on the strength of the From address (DESIGN.md §11.4, privacy PS4): honour
 * "Always show remote content from this sender", and offer the one-click unsubscribe POST.
 */
enum class SenderTrust(@StringRes val note: Int?) {
    /** Gmail with `dmarc=pass`, not in Junk: per-sender trust and one-click unsubscribe. */
    VERIFIED(null),

    /** Riseup, not in Junk: per-sender trust by From only, with [note] on the toggle; no one-click POST. */
    FROM_ONLY(R.string.html_sender_trust_fakeable),

    /** No per-sender trust and no one-click POST: "Show once" and the link sheet only. */
    NONE(null);

    /** True when "Always show remote content from this sender" is offered and honoured. */
    val alwaysShowRemote: Boolean get() = this != NONE

    /** True when the RFC 8058 one-click POST is offered; otherwise the unsubscribe link opens the link sheet. */
    val oneClickUnsubscribe: Boolean get() = this == VERIFIED
}

/** Sender checks for the message view (DESIGN.md §11.4, privacy PS4/PS6). Domains are compared with the [psl]. */
class SenderCheck(private val psl: Psl) {
    /**
     * Warnings in display order, most serious first.
     *
     * @param fromName decoded display name of From, empty when none.
     * @param replyTo the decoded Reply-To value (`message.reply_to`), empty when none.
     * @param auth the stored [Auth] value; only [Auth.FAIL] warns.
     * @param firstContact true when the local database has no earlier message from [fromEmail].
     */
    fun check(
        fromName: String,
        fromEmail: String,
        replyTo: String,
        auth: Int,
        firstContact: Boolean,
    ): List<SenderWarning> = buildList {
        val site = siteOf(fromEmail)
        if (auth == Auth.FAIL) add(SenderWarning.AUTH_FAILED)
        if (addressInName.findAll(fromName).any { !it.value.equals(fromEmail, ignoreCase = true) }) {
            add(SenderWarning.NAME_HAS_OTHER_ADDRESS)
        } else if (psl.domainsIn(fromName).any { it != site }) {
            add(SenderWarning.NAME_HAS_OTHER_DOMAIN)
        }
        if (Addresses.parseList(replyTo).any { siteOf(it.email) != site }) add(SenderWarning.REPLY_TO_OTHER_DOMAIN)
        if (firstContact) add(SenderWarning.FIRST_CONTACT)
    }

    private fun siteOf(email: String): String = psl.site(email.substringAfterLast('@'))

    companion object {
        /**
         * The [SenderTrust] of a message: Gmail only with [Auth.PASS], other servers by From only, never for a message
         * in Junk.
         */
        fun trust(gmail: Boolean, auth: Int, inJunk: Boolean): SenderTrust = when {
            inJunk -> SenderTrust.NONE
            !gmail -> SenderTrust.FROM_ONLY
            auth == Auth.PASS -> SenderTrust.VERIFIED
            else -> SenderTrust.NONE
        }

        private val addressInName = Regex("""[^\s<>()\[\]"',;:@]+@[^\s<>()\[\]"',;:@]+\.[^\s<>()\[\]"',;:@.]+""")
    }
}
