package app.plico.mail.imap

/**
 * An IMAP command without its tag. Parts are joined with single spaces on the wire.
 * [ImapConnection] decides how each [Part.Text] is sent: as a quoted string when it is printable ASCII
 * without `"` and `\`, else as a literal (LITERAL+ / LITERAL- non-synchronizing when the server allows).
 */
class Command private constructor(val parts: List<Part>) {

    sealed interface Part {
        /** Sent exactly as given: keywords, atoms, sequence sets, parenthesized lists of atoms. */
        data class Raw(val text: String) : Part {
            init {
                require('\r' !in text && '\n' !in text) { "Line break in a raw command part" }
            }
        }

        /** A string argument (mailbox name already encoded, search text, header value). */
        data class Text(val value: String) : Part

        /** Raw bytes that are always sent as a literal (APPEND message data). */
        class Bytes(val value: ByteArray) : Part

        /** A parenthesized list of parts, e.g. `(\Inbox "Work")`. */
        data class Group(val parts: List<Part>) : Part
    }

    /** True when any string argument contains non-ASCII characters (search then needs CHARSET UTF-8). */
    val hasNonAscii: Boolean get() = parts.any(::nonAscii)

    /** The first raw word, e.g. `UID`, `SELECT`, `AUTHENTICATE`. Used for log redaction. */
    val verb: String get() = (parts.firstOrNull() as? Part.Raw)?.text?.substringBefore(' ')?.uppercase() ?: ""

    class Builder {
        private val parts = ArrayList<Part>()
        fun raw(text: String) = apply { parts += Part.Raw(text) }
        fun string(value: String) = apply { parts += Part.Text(value) }
        fun bytes(value: ByteArray) = apply { parts += Part.Bytes(value) }

        /** Adds a parenthesized list built by [block]. */
        fun list(block: Builder.() -> Unit) = apply { parts += Part.Group(Builder().apply(block).parts.toList()) }

        /** Adds every part of [other], e.g. search criteria after `UID SEARCH`. */
        fun append(other: Command) = apply { parts += other.parts }

        fun build() = Command(parts.toList())
    }

    companion object {
        fun build(block: Builder.() -> Unit): Command = Builder().apply(block).build()

        /** Command made only of raw words, e.g. `Command.raw("NOOP")`. */
        fun raw(vararg words: String): Command = Command(words.map { Part.Raw(it) })

        private fun nonAscii(part: Part): Boolean = when (part) {
            is Part.Text -> part.value.any { it.code > 0x7e }
            is Part.Group -> part.parts.any(::nonAscii)
            else -> false
        }
    }
}
