package app.plico.mail.imap

/** One parsed IMAP data item. */
sealed interface ImapValue {
    /** Atom, number, flag (`\Seen`) or section key (`BODY[1.2]<0>`). */
    data class Atom(val text: String) : ImapValue {
        override fun toString() = text
    }

    /** Quoted string or literal. */
    class Str(val bytes: ByteArray) : ImapValue {
        fun text(): String = bytes.toString(Charsets.UTF_8)
        override fun equals(other: Any?) = other is Str && other.bytes.contentEquals(bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "\"${text()}\""
    }

    data class ListV(val items: List<ImapValue>) : ImapValue {
        override fun toString() = items.joinToString(" ", "(", ")")
    }

    data object Nil : ImapValue {
        override fun toString() = "NIL"
    }
}

/** Text of an atom or string; null for NIL and lists. */
fun ImapValue.textOrNull(): String? = when (this) {
    is ImapValue.Atom -> text
    is ImapValue.Str -> text()
    else -> null
}

fun ImapValue.longOrNull(): Long? = (this as? ImapValue.Atom)?.text?.toLongOrNull()

fun ImapValue.listOrEmpty(): List<ImapValue> = (this as? ImapValue.ListV)?.items ?: emptyList()
