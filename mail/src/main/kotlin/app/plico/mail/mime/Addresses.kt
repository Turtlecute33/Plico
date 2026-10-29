package app.plico.mail.mime

/** One mailbox. [name] is decoded (RFC 2047) and unquoted. */
data class Address(val name: String?, val email: String) {
    /** RFC 5322 form for headers: `"Name" <a@b>` (name quoted when needed, 2047-encoded when non-ASCII) or `a@b`. */
    fun format(): String = Addresses.format(listOf(this))
}

object Addresses {
    private const val SPECIALS = "()<>[]:;@\\,.\""

    /**
     * Parses an address list: groups, comments, quoted names, encoded words, missing angle brackets. Lenient.
     * Group names are dropped; their members are kept. Names pass [HeaderSafety.clean]; empty names are null.
     * Emails pass [HeaderSafety.revealHidden]. Entries without an address are skipped.
     */
    fun parseList(value: String): List<Address> =
        segments(tokenize(value)).flatMap(::mailboxes).map { it.copy(email = HeaderSafety.revealHidden(it.email)) }

    /**
     * Comma-separated header value. Names and emails pass [HeaderSafety.clean] first; a name is quoted when it
     * holds specials. With [encode] false, non-ASCII names stay as they are (stored lists and the UI), and
     * [parseList] reads the result back.
     */
    fun format(list: List<Address>, encode: Boolean = true): String = list.joinToString(", ") { a ->
        val email = HeaderSafety.clean(a.email)
        val name = HeaderSafety.clean(a.name.orEmpty())
        if (name.isEmpty()) email else "${displayName(name, encode)} <$email>"
    }

    /** Strict addr-spec check for SMTP commands and outgoing headers; see [AddrSpec.isValid]. */
    fun isValidAddrSpec(s: String): Boolean = AddrSpec.isValid(s)

    private fun displayName(name: String, encode: Boolean): String = when {
        encode && name.any { it > '~' } -> EncodedWords.encode(name)
        name.any { it in SPECIALS } -> "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        else -> name
    }

    private sealed interface Token {
        /** Adjacent atoms and quoted strings: [raw] as written, [text] without quotes and escapes. */
        class Word(val raw: String, val text: String) : Token
        class Comment(val text: String) : Token
        class Angle(val addr: String) : Token
        class Separator(val char: Char) : Token
        data object Space : Token
    }

    private fun tokenize(s: String): List<Token> {
        val tokens = ArrayList<Token>()
        val raw = StringBuilder()
        val text = StringBuilder()
        // Adjacent atoms and quoted strings collect in raw/text until another token ends the word.
        fun add(token: Token) {
            if (raw.isNotEmpty()) {
                tokens += Token.Word(raw.toString(), text.toString())
                raw.setLength(0)
                text.setLength(0)
            }
            if (token != Token.Space || tokens.lastOrNull() != Token.Space) tokens += token
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c <= ' ' || c == '\u007F' || c == '>' || c == ')' -> {
                    add(Token.Space)
                    i++
                }
                c == '"' -> {
                    val start = i
                    i = quoted(s, i, text)
                    raw.append(s, start, i)
                }
                c == '(' -> {
                    val body = StringBuilder()
                    i = comment(s, i, body)
                    add(Token.Comment(body.toString()))
                }
                c == '<' -> {
                    val end = angleEnd(s, i)
                    add(Token.Angle(s.substring(i + 1, end)))
                    i = minOf(end + 1, s.length)
                }
                c == ',' || c == ';' || c == ':' -> {
                    add(Token.Separator(c))
                    i++
                }
                else -> {
                    val end = atomEnd(s, i)
                    raw.append(s, i, end)
                    text.append(s, i, end)
                    i = end
                }
            }
        }
        add(Token.Space)
        return tokens
    }

    /** Reads the quoted string at [start] into [text]; returns the index after the closing quote. */
    private fun quoted(s: String, start: Int, text: StringBuilder): Int {
        var i = start + 1
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return i
                c == '\\' && i < s.length -> text.append(s[i++])
                else -> text.append(c)
            }
        }
        return i
    }

    /** Reads the (nested) comment at [start] into [text]; returns the index after it. */
    private fun comment(s: String, start: Int, text: StringBuilder): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '\\' && i < s.length -> text.append(s[i++])
                c == '(' -> if (depth++ > 0) text.append(c)
                c == ')' -> if (--depth > 0) text.append(c) else return i
                else -> text.append(c)
            }
        }
        return i
    }

    /** Index of the `>` that closes the angle address at [start] (quotes respected), or the end. */
    private fun angleEnd(s: String, start: Int): Int {
        var i = start + 1
        var inQuotes = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && inQuotes -> i++
                c == '"' -> inQuotes = !inQuotes
                c == '>' && !inQuotes -> return i
            }
            i++
        }
        return s.length
    }

    /** An atom ends at whitespace or a special; an encoded word is kept whole even if it holds specials. */
    private fun atomEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && s.startsWith("=?", i)) {
                val end = EncodedWords.wordEnd(s, i)
                if (end > 0) {
                    i = end
                    continue
                }
            }
            if (c <= ' ' || c == '\u007F' || c in "\"(),;:<>") return i
            i++
        }
        return i
    }

    /** Splits at `,` and `;`, drops group names before `:` and starts a new mailbox after `<...>` + a word. */
    private fun segments(tokens: List<Token>): List<List<Token>> {
        val result = ArrayList<List<Token>>()
        var current = ArrayList<Token>()
        var angled = false
        for (t in tokens) {
            when {
                t is Token.Separator && t.char == ':' && !angled -> current.clear()
                t is Token.Separator || angled && (t is Token.Word || t is Token.Angle) -> {
                    result += current
                    current = ArrayList()
                    angled = false
                    if (t !is Token.Separator) current += t
                }
                else -> current += t
            }
            if (t is Token.Angle) angled = true
        }
        result += current
        return result
    }

    private fun mailboxes(segment: List<Token>): List<Address> {
        val angle = segment.indexOfFirst { it is Token.Angle }
        if (angle < 0) return bare(segment)
        val email = addrSpec((segment[angle] as Token.Angle).addr)
        if (email.isEmpty()) return emptyList()
        val name = phrase(segment.subList(0, angle).filterIsInstance<Token.Word>()) ?: commentName(segment)
        return listOf(Address(name, email))
    }

    /** No angle brackets: each word that looks like `x@y` is an address; the words before it are its name. */
    private fun bare(segment: List<Token>): List<Address> {
        val words = joinObsolete(segment.filterIsInstance<Token.Word>())
        val result = ArrayList<Address>()
        var nameStart = 0
        words.forEachIndexed { i, w ->
            if (isAddrLike(w.raw)) {
                result += Address(phrase(words.subList(nameStart, i)), w.raw)
                nameStart = i + 1
            }
        }
        if (result.size == 1 && result[0].name == null) result[0] = result[0].copy(name = commentName(segment))
        return result
    }

    /** Obsolete spacing around `.` and `@`: `jdoe @ test . example` is one word. */
    private fun joinObsolete(words: List<Token.Word>): List<Token.Word> {
        val groups = ArrayList<MutableList<Token.Word>>()
        var glue = false
        for (w in words) {
            if (groups.isNotEmpty() && (glue || w.raw.startsWith('.') || w.raw.startsWith('@'))) groups.last() += w
            else groups += mutableListOf(w)
            glue = w.raw == "." || w.raw.endsWith('@')
        }
        return groups.map { g -> Token.Word(g.joinToString("") { it.raw }, g.joinToString("") { it.text }) }
    }

    /** Angle content without comments, whitespace outside quotes and an obsolete route `@a,@b:`. */
    private fun addrSpec(angle: String): String {
        val out = StringBuilder(angle.length)
        var inQuotes = false
        var escaped = false
        var depth = 0
        for (c in angle) {
            when {
                escaped -> {
                    escaped = false
                    if (depth == 0) out.append(c)
                }
                c == '\\' && (inQuotes || depth > 0) -> {
                    escaped = true
                    if (depth == 0) out.append(c)
                }
                inQuotes -> {
                    if (c == '"') inQuotes = false
                    out.append(c)
                }
                c == '(' -> depth++
                depth > 0 -> if (c == ')') depth--
                c == '"' -> {
                    inQuotes = true
                    out.append(c)
                }
                c > ' ' && c != '\u007F' -> out.append(c)
            }
        }
        val spec = out.toString()
        return if (spec.startsWith('@') && ':' in spec) spec.substringAfter(':') else spec
    }

    /** True when an `@` outside quotes has text on both sides. */
    private fun isAddrLike(raw: String): Boolean {
        var at = -1
        var inQuotes = false
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '\\' -> if (inQuotes) i++
                '"' -> inQuotes = !inQuotes
                '@' -> if (!inQuotes) at = i
            }
            i++
        }
        return at > 0 && at < raw.length - 1
    }

    private fun phrase(words: List<Token.Word>): String? = display(words.joinToString(" ") { it.text })

    private fun commentName(segment: List<Token>): String? =
        segment.firstNotNullOfOrNull { (it as? Token.Comment)?.text?.let(::display) }

    private fun display(text: String): String? = HeaderSafety.clean(EncodedWords.decode(text)).ifEmpty { null }
}
