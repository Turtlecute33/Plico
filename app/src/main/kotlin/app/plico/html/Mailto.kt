package app.plico.html

import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses
import app.plico.mail.mime.HeaderSafety

/**
 * A `mailto:` URI (RFC 6068) from a link, SENDTO or VIEW intent (DESIGN.md §11.6, privacy PS2). Only `to`, `cc`,
 * `bcc`, `subject` and `body` are read; `attach` and every other field are ignored. Addresses are valid
 * addr-specs, the subject has no control characters and the body uses `\n` line breaks.
 */
data class Mailto(
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,
    val subject: String,
    val body: String,
) {
    companion object {
        /** Null when [uri] is not a mailto URI. */
        fun parse(uri: String): Mailto? {
            val s = uri.trim()
            if (!s.startsWith("mailto:", ignoreCase = true)) return null
            val rest = s.substring("mailto:".length).substringBefore('#')
            val to = addresses(Percent.decode(rest.substringBefore('?'))).toMutableList()
            val cc = ArrayList<Address>()
            val bcc = ArrayList<Address>()
            var subject: String? = null
            var body: String? = null
            for (field in rest.substringAfter('?', "").split('&')) {
                val value = Percent.decode(field.substringAfter('=', ""))
                when (Percent.decode(field.substringBefore('=')).lowercase()) {
                    "to" -> to += addresses(value)
                    "cc" -> cc += addresses(value)
                    "bcc" -> bcc += addresses(value)
                    "subject" -> if (subject == null) subject = HeaderSafety.clean(value)
                    "body" -> if (body == null) body = cleanBody(value)
                }
            }
            return Mailto(to.unique(), cc.unique(), bcc.unique(), subject.orEmpty(), body.orEmpty())
        }

        /** The valid addr-specs in [value]. */
        internal fun addresses(value: String): List<Address> =
            Addresses.parseList(value).filter { Addresses.isValidAddrSpec(it.email) }

        /** The first address of each email (compared without case). */
        private fun List<Address>.unique(): List<Address> = distinctBy { it.email.lowercase() }

        /** CR LF and CR become LF; other controls except tab are dropped. */
        internal fun cleanBody(value: String): String =
            value.replace("\r\n", "\n").replace('\r', '\n').filter { it == '\n' || it == '\t' || !it.isISOControl() }
    }
}
