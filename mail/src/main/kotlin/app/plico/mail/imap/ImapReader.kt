package app.plico.mail.imap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reads one complete server response at a time (with all literals). See DESIGN.md §4.2.
 * Limits: literal <= [maxLiteral] bytes, line <= [maxLine] bytes, all literals of one response <=
 * [maxResponse] bytes, lists nested <= [MAX_NESTING] deep, else [ImapProtocolException].
 *
 * Bytes are taken one at a time from [input] (which must be buffered), and the reader never reads past
 * the LF that ends a response, so the stream can switch to COMPRESS right after a tagged OK.
 */
class ImapReader(
    private val input: InputStream,
    private val maxLiteral: Int = 8 * 1024 * 1024,
    private val maxLine: Int = 1024 * 1024,
    // Above the largest message a provider accepts (Gmail 35,882,577 bytes; Riseup 25,600,000).
    private val maxResponse: Long = 64L * 1024 * 1024,
) {
    private var peeked = NONE
    private var lineBytes = 0
    private var literalBytes = 0L
    private var nesting = 0

    /** Blocks until one response is read. Throws [java.io.EOFException] at end of stream. */
    fun read(): ImapResponse {
        lineBytes = 0
        literalBytes = 0
        nesting = 0
        return when (peek()) {
            STAR -> {
                next()
                expect(SP)
                untagged()
            }
            PLUS -> {
                next()
                if (peek() == SP) next()
                Continuation(textToEol())
            }
            else -> tagged()
        }
    }

    private fun tagged(): Tagged {
        val tag = atom()
        expect(SP)
        val status = atom().uppercase()
        val code = statusCode()
        return Tagged(tag, status, code, textToEol())
    }

    private fun untagged(): Untagged {
        var word = atom()
        var number: Long? = null
        if (word.all { it in '0'..'9' }) {
            number = word.toLongOrNull() ?: throw ImapProtocolException("Number too large")
            expect(SP)
            word = atom()
        }
        val keyword = word.uppercase()
        if (keyword in STATUS_KEYWORDS) {
            val code = statusCode()
            return Untagged(number, keyword, emptyList(), code, textToEol())
        }
        return Untagged(number, keyword, valuesToEol(), null, null)
    }

    /** After a status word: an optional `[CODE args]`. Leaves the reader at the start of the free text. */
    private fun statusCode(): RespCode? {
        if (peek() == SP) next()
        if (peek() != LBRACKET) return null
        next()
        val name = atom(inCode = true).uppercase()
        val args = ArrayList<ImapValue>()
        while (true) {
            skipSpaces()
            when (peek()) {
                RBRACKET -> break
                CR, LF -> throw ImapProtocolException("Unterminated response code")
                else -> args += value(inCode = true)
            }
        }
        next()
        if (peek() == SP) next()
        return RespCode(name, args)
    }

    private fun valuesToEol(): List<ImapValue> {
        val values = ArrayList<ImapValue>()
        while (true) {
            skipSpaces()
            if (peek() == CR || peek() == LF) {
                endOfLine()
                return values
            }
            values += value(inCode = false)
        }
    }

    private fun value(inCode: Boolean): ImapValue = when (peek()) {
        QUOTE -> ImapValue.Str(quoted())
        LBRACE -> ImapValue.Str(literal())
        LPAREN -> list(inCode)
        else -> atom(inCode).let { if (it.equals("NIL", ignoreCase = true)) ImapValue.Nil else ImapValue.Atom(it) }
    }

    private fun list(inCode: Boolean): ImapValue.ListV {
        // value() and list() call each other; a limit keeps a deep response from overflowing the stack.
        if (++nesting > MAX_NESTING) throw ImapProtocolException("Lists nested deeper than $MAX_NESTING")
        next()
        val items = ArrayList<ImapValue>()
        while (true) {
            skipSpaces()
            if (peek() == RPAREN) {
                next()
                nesting--
                return ImapValue.ListV(items)
            }
            items += value(inCode)
        }
    }

    /**
     * An atom ends at SP, `(`, `)` or a line break, and inside a response code also at `]`. The `[` of a
     * FETCH section key ([SECTION_KEYS]) makes it run through the matching `]`, spaces and parentheses
     * included, but never past the line break: `BODY[HEADER.FIELDS (FROM)]<0>`. Any other `[` is plain
     * text, as in the labels `[Old` and `Q1[draft` or the mailbox `Archive[2023`.
     */
    private fun atom(inCode: Boolean = false): String {
        val out = ByteArrayOutputStream()
        var depth = 0
        while (true) {
            val c = peek()
            if (c == CR || c == LF) break
            if (depth == 0 && (c == SP || c == LPAREN || c == RPAREN || (inCode && c == RBRACKET))) break
            next()
            if (c == LBRACKET && (depth > 0 || !inCode && isSectionKey(out))) {
                depth++
            } else if (c == RBRACKET && depth > 0) {
                depth--
            }
            out.write(c)
        }
        if (out.size() == 0) throw ImapProtocolException("Expected an atom")
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun isSectionKey(out: ByteArrayOutputStream): Boolean =
        out.size() <= MAX_SECTION_KEY && out.toString(Charsets.US_ASCII).uppercase() in SECTION_KEYS

    private fun quoted(): ByteArray {
        next()
        val out = ByteArrayOutputStream()
        while (true) {
            when (val c = next()) {
                QUOTE -> return out.toByteArray()
                BACKSLASH -> out.write(next())
                CR, LF -> throw ImapProtocolException("Line break in quoted string")
                else -> out.write(c)
            }
        }
    }

    /** `{n}` or `{n+}`, CRLF, then exactly n raw bytes. */
    private fun literal(): ByteArray {
        next()
        var size = 0L
        var digits = 0
        while (peek() in DIGITS) {
            size = size * 10 + (next() - '0'.code)
            if (size > maxLiteral) throw ImapProtocolException("Literal larger than $maxLiteral bytes")
            digits++
        }
        if (peek() == PLUS) next()
        if (digits == 0 || next() != RBRACE) throw ImapProtocolException("Malformed literal")
        literalBytes += size
        if (literalBytes > maxResponse) throw ImapProtocolException("Response larger than $maxResponse bytes")
        endOfLine()
        val bytes = ByteArray(size.toInt())
        var off = 0
        while (off < bytes.size) {
            val n = input.read(bytes, off, bytes.size - off)
            if (n < 0) throw EOFException("End of stream inside a literal")
            off += n
        }
        return bytes
    }

    /** Free text up to the end of the line, without the line break. */
    private fun textToEol(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = next()
            if (c == LF) break
            out.write(c)
        }
        val bytes = out.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last().toInt() == CR) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /** Consumes CRLF (or a bare LF) without looking at the byte after it. */
    private fun endOfLine() {
        if (next() == CR && next() != LF) throw ImapProtocolException("CR without LF")
    }

    private fun skipSpaces() {
        while (peek() == SP) next()
    }

    private fun expect(c: Int) {
        if (next() != c) throw ImapProtocolException("Expected '${c.toChar()}'")
    }

    private fun peek(): Int {
        if (peeked == NONE) peeked = readByte()
        return peeked
    }

    private fun next(): Int {
        val c = peek()
        peeked = NONE
        return c
    }

    private fun readByte(): Int {
        val c = input.read()
        if (c < 0) throw EOFException("End of stream")
        if (++lineBytes > maxLine) throw ImapProtocolException("Line longer than $maxLine bytes")
        return c
    }

    private companion object {
        const val NONE = -2
        const val SP = ' '.code
        const val CR = '\r'.code
        const val LF = '\n'.code
        const val STAR = '*'.code
        const val PLUS = '+'.code
        const val QUOTE = '"'.code
        const val BACKSLASH = '\\'.code
        const val LPAREN = '('.code
        const val RPAREN = ')'.code
        const val LBRACKET = '['.code
        const val RBRACKET = ']'.code
        const val LBRACE = '{'.code
        const val RBRACE = '}'.code
        val DIGITS = '0'.code..'9'.code
        val STATUS_KEYWORDS = setOf("OK", "NO", "BAD", "BYE", "PREAUTH")

        /** FETCH attributes whose `[section]` the server echoes (RFC 3501 §7.4.2, RFC 9051 BINARY). */
        val SECTION_KEYS = setOf("BODY", "BODY.PEEK", "BINARY", "BINARY.PEEK", "BINARY.SIZE")
        val MAX_SECTION_KEY = SECTION_KEYS.maxOf { it.length }

        /**
         * Far above real BODYSTRUCTURE depth (Dovecot stops at 100 MIME levels, and parseBodyStructure cuts
         * there), far below what the stack holds.
         */
        const val MAX_NESTING = 256
    }
}
