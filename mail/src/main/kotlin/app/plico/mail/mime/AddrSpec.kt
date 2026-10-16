package app.plico.mail.mime

/** Strict addr-spec check for SMTP commands and outgoing headers. */
object AddrSpec {
    private const val MAX_LOCAL = 64 // RFC 5321 §4.5.3.1, in UTF-8 octets
    private const val MAX_DOMAIN = 255
    private const val MAX_ADDRESS = 254
    private const val MAX_LABEL = 63
    private const val ATEXT_SPECIALS = "!#$%&'*+-/=?^_`{|}~"
    private val PRINTABLE = 0x20..0x7e
    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK)
    private val ipv4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
    private val ipv6 = Regex("""IPv6:[0-9A-Fa-f:.]+""", RegexOption.IGNORE_CASE)

    /** `local@domain`; no CR, LF, `<`, `>`, unquoted space or controls; non-ASCII letters allowed (SMTPUTF8). */
    fun isValid(s: String): Boolean {
        if (s.any { it.isISOControl() || it == '<' || it == '>' }) return false
        val at = localPartEnd(s)
        if (at <= 0) return false
        val local = s.substring(0, at)
        val domain = s.substring(at + 1)
        return utf8Length(local) <= MAX_LOCAL && utf8Length(domain) <= MAX_DOMAIN &&
            utf8Length(s) <= MAX_ADDRESS && isLocalPart(local) && isDomain(domain)
    }

    /** Index of the `@` that ends the local part, or -1. A quoted local part may contain `@`. */
    private fun localPartEnd(s: String): Int {
        if (!s.startsWith('"')) return s.indexOf('@')
        var i = 1
        while (i < s.length && s[i] != '"') i += if (s[i] == '\\') 2 else 1
        return if (i + 1 < s.length && s[i + 1] == '@') i + 1 else -1
    }

    private fun isLocalPart(local: String): Boolean =
        if (local.startsWith('"')) isQuoted(local.substring(1, local.length - 1)) else isDotAtom(local)

    private fun isDotAtom(s: String): Boolean =
        s.split('.').all { atom -> atom.isNotEmpty() && atom.codePoints().allMatch(::isAtext) }

    /** Content of a quoted string: printable ASCII and space, `\` pairs, non-ASCII letters. */
    private fun isQuoted(content: String): Boolean {
        if (content.isEmpty()) return false
        var i = 0
        while (i < content.length) {
            val cp = content.codePointAt(i)
            when {
                cp == '\\'.code -> if (content.getOrNull(++i)?.code !in PRINTABLE) return false
                cp == '"'.code -> return false
                cp < 0x80 -> if (cp !in PRINTABLE) return false
                !isUnicodeWord(cp) -> return false
            }
            i += Character.charCount(cp)
        }
        return true
    }

    private fun isDomain(domain: String): Boolean {
        if (!domain.startsWith('[')) return domain.split('.').all(::isLabel)
        return domain.endsWith(']') && isAddressLiteral(domain.substring(1, domain.length - 1))
    }

    private fun isAddressLiteral(s: String): Boolean = ipv4.matches(s) || ipv6.matches(s)

    private fun isLabel(label: String): Boolean =
        label.length in 1..MAX_LABEL && !label.startsWith('-') && !label.endsWith('-') &&
            label.codePoints().allMatch { cp -> cp == '-'.code || isAlphaNum(cp) || cp >= 0x80 && isUnicodeWord(cp) }

    private fun isAtext(cp: Int): Boolean =
        isAlphaNum(cp) || cp < 0x80 && ATEXT_SPECIALS.indexOf(cp.toChar()) >= 0 || cp >= 0x80 && isUnicodeWord(cp)

    private fun isAlphaNum(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code

    /** Letters, digits and combining marks (needed by Indic scripts). No symbols, spaces or format characters. */
    private fun isUnicodeWord(cp: Int): Boolean =
        Character.isLetterOrDigit(cp) || Character.getType(cp).toByte() in MARKS

    private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
