package app.plico.ui.compose

import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses

/** One chip of a To, Cc or Bcc field (DESIGN §13). */
internal sealed interface Recipient {
    /** The form stored in the draft and shown for editing; [Recipients.parse] reads it back. */
    val text: String

    /** What the chip shows. */
    val label: String

    data class Valid(val address: Address) : Recipient {
        override val text: String get() = Recipients.display(address)
        override val label: String get() = address.name ?: address.email
    }

    /** Text that is not a usable address. It stays visible, marked, and blocks sending. */
    data class Invalid(override val text: String) : Recipient {
        override val label: String get() = text
    }
}

/** Parses typed and pasted recipient text into chips. Pure Kotlin. */
internal object Recipients {
    private const val SPECIALS = "()<>[]:;@\\,.\""

    /** Every recipient in [text]: list separators are `,` `;` and line breaks outside quotes. */
    fun parse(text: String): List<Recipient> = distinct(split(text).flatMap(::parseEntry))

    /**
     * Splits what the user typed into finished recipients and the text that is still being typed. An entry
     * is finished at a separator, or at a space after a complete valid address (as in most mail apps).
     */
    fun takeFinished(input: String): Pair<List<Recipient>, String> {
        val parts = split(input)
        val done = parts.dropLast(1).flatMap(::parseEntry)
        val pending = parts.last().trimStart()
        if (pending.isNotEmpty() && pending.last().isWhitespace() && isBalanced(pending)) {
            val last = parseEntry(pending)
            if (last.isNotEmpty() && last.all { it is Recipient.Valid }) return distinct(done + last) to ""
        }
        return distinct(done) to pending
    }

    /** The draft column value (DESIGN §5 `draft.to_list`); [parse] reads it back. */
    fun format(list: List<Recipient>): String = list.joinToString(", ") { it.text }

    /** The items of [added] that are not in [existing] yet; addresses compare without case. */
    fun newOnes(existing: List<Recipient>, added: List<Recipient>): List<Recipient> {
        val seen = existing.mapTo(HashSet(), ::key)
        return added.filter { seen.add(key(it)) }
    }

    /** A chip for [address]: valid only when it is a deliverable addr-spec. */
    fun of(address: Address): Recipient =
        if (isDeliverable(address.email)) Recipient.Valid(address) else Recipient.Invalid(display(address))

    /**
     * A strict addr-spec whose domain has a dot (or is an address literal): `bob@gmail` is almost always a
     * typing error, and neither provider delivers to single-label domains.
     */
    fun isDeliverable(email: String): Boolean {
        if (!Addresses.isValidAddrSpec(email)) return false
        val domain = email.substringAfterLast('@')
        return domain.startsWith('[') || '.' in domain.trimEnd('.')
    }

    /** `Name <a@b>` with the name quoted when it holds specials, or `a@b`. Readable, and safe to parse again. */
    fun display(address: Address): String {
        val name = address.name?.takeIf { it.isNotBlank() } ?: return address.email
        val phrase = if (name.any { it in SPECIALS }) {
            "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            name
        }
        return "$phrase <${address.email}>"
    }

    private fun parseEntry(entry: String): List<Recipient> {
        val text = entry.trim()
        if (text.isEmpty()) return emptyList()
        val addresses = Addresses.parseList(text)
        return if (addresses.isEmpty()) listOf(Recipient.Invalid(text)) else addresses.map(::of)
    }

    private fun distinct(list: List<Recipient>): List<Recipient> = newOnes(emptyList(), list)

    private fun key(r: Recipient): String = when (r) {
        is Recipient.Valid -> r.address.email.lowercase()
        is Recipient.Invalid -> "\u0000" + r.text
    }

    /**
     * Entries between separators outside quoted strings. After a quote that is never closed, the rest is one
     * entry, so a name such as `"Doe, Jane` that is still being typed does not split.
     */
    private fun split(text: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    i = closingQuote(text, i)
                    if (i < 0) break
                }
                ',', ';', '\n', '\r' -> {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        parts += text.substring(start)
        return parts
    }

    /** Index of the quote that closes the one at [open], or -1. */
    private fun closingQuote(s: String, open: Int): Int {
        var i = open + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return -1
    }

    /** Quotes closed and every `<` closed by a `>`: the user is not in the middle of a name or address. */
    private fun isBalanced(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            if (s[i] == '"') {
                i = closingQuote(s, i)
                if (i < 0) return false
            }
            i++
        }
        return s.count { it == '<' } == s.count { it == '>' }
    }
}
