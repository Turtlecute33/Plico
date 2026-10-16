package app.plico.html

/**
 * CSS sanitizing for `<style>` blocks and `style` attributes (DESIGN.md §11.1, privacy PS12). It works on the
 * text and handles every match of a dangerous name, also inside strings and comments, so that no difference
 * between this code and the browser's tokenizer can hide one:
 * 1. escapes that give an ASCII letter or `-` are decoded (`u\72l(` is `url(`); other escapes stay;
 * 2. comments are removed;
 * 3. `@import` rules, `@font-face` blocks, `expression`, `behavior`, `-moz-binding`, `javascript:` and
 *    `vbscript:` become a space until none is left;
 * 4. `url(…)` becomes `url("<URL from the callback>")` or `none`; `image-set()`, `image()`, `cross-fade()`,
 *    `element()`, `src()` and `paint()` (and their prefixed forms) become `none`;
 * 5. `<` becomes `\3c `, so the text cannot close the `<style>` element.
 */
internal object Css {
    private val comment = Regex("""/\*[\s\S]*?(?:\*/|\z)""")
    private val banned = Regex(
        """@import[^;{}]*;?|@font-face\s*\{[^}]*\}?|expression|behavior|-moz-binding|javascript:|vbscript:""",
        RegexOption.IGNORE_CASE,
    )
    private val function = Regex(
        """(?:-webkit-|-moz-)?(url|image-set|image|cross-fade|element|src|paint)\(""",
        RegexOption.IGNORE_CASE,
    )
    private const val WHITESPACE = " \t\n\r\u000C"

    /** @param url maps a URL from `url()` to a safe absolute URL, or null to drop it. */
    fun sanitize(css: String, url: (String) -> String?): String {
        var s = comment.replace(decodeLetterEscapes(css), "")
        // A space cannot join the parts around a match into a new name, so few passes are needed.
        while (true) {
            val next = banned.replace(s, " ")
            if (next == s) break
            s = next
        }
        return functions(s, url).replace("<", "\\3c ")
    }

    /** A CSS string literal for [value] (control characters removed). */
    fun quote(value: String): String =
        "\"" + value.filterNot(Char::isISOControl).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Decodes the escapes that give ASCII letters or `-`; keeps every other escape as written. */
    fun decodeLetterEscapes(css: String): String {
        if ('\\' !in css) return css
        val out = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            if (css[i] != '\\' || i + 1 == css.length) {
                out.append(css[i++])
                continue
            }
            var (cp, end) = hexEscape(css, i)
            if (end > i + 1) {
                end = afterEscapeWhitespace(css, end)
            } else {
                cp = css[i + 1].code
                end = i + 2
            }
            val c = cp.toChar()
            if (cp < 0x80 && (c.isLetter() || c == '-')) out.append(c) else out.append(css, i, end)
            i = end
        }
        return out.toString()
    }

    private fun functions(css: String, url: (String) -> String?): String {
        val out = StringBuilder(css.length)
        var from = 0
        while (true) {
            val m = function.find(css, from) ?: break
            out.append(css, from, m.range.first)
            val start = m.range.last + 1
            val close = closingParen(css, start)
            val replacement = if (m.groupValues[1].equals("url", ignoreCase = true)) {
                url(argument(css.substring(start, close)))
                    ?.let { "url(\"" + Percent.encode(it) { c -> c > ' ' && c !in "\"'()\\<>" } + "\")" }
            } else {
                null
            }
            out.append(replacement ?: "none")
            from = minOf(close + 1, css.length)
        }
        return out.append(css, from, css.length).toString()
    }

    /** Index of the `)` that closes the function whose arguments start at [start], or the end of [css]. */
    private fun closingParen(css: String, start: Int): Int {
        var depth = 1
        var quote = 0.toChar()
        var i = start
        while (i < css.length) {
            val c = css[i]
            when {
                c == '\\' -> i++
                quote != 0.toChar() -> if (c == quote || c == '\n') quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return i
            }
            i++
        }
        return css.length
    }

    /** The URL inside `url(…)`: a quoted string or a bare value, with escapes decoded. */
    private fun argument(raw: String): String {
        val s = raw.trim { it in WHITESPACE }
        val quoted = s.isNotEmpty() && (s[0] == '"' || s[0] == '\'')
        val out = StringBuilder(s.length)
        var i = if (quoted) 1 else 0
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == s[0] -> break
                c != '\\' -> out.append(c).also { i++ }
                i + 1 < s.length && s[i + 1] == '\n' -> i += 2
                else -> {
                    val (cp, end) = hexEscape(s, i)
                    if (end > i + 1) {
                        out.appendCodePoint(if (cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) 0xFFFD else cp)
                        i = afterEscapeWhitespace(s, end)
                    } else {
                        if (end < s.length) out.append(s[end])
                        i = end + 1
                    }
                }
            }
        }
        return out.toString()
    }

    /** The code point of the hex digits (at most 6) after the backslash at [i], and the index after them. */
    private fun hexEscape(s: String, i: Int): Pair<Int, Int> {
        var end = i + 1
        var cp = 0
        while (end < s.length && end <= i + 6 && Percent.hex(s[end]) >= 0) cp = cp * 16 + Percent.hex(s[end++])
        return cp to end
    }

    /** One whitespace (CR LF counts as one) ends a hex escape. */
    private fun afterEscapeWhitespace(s: String, i: Int): Int = when {
        s.startsWith("\r\n", i) -> i + 2
        i < s.length && s[i] in WHITESPACE -> i + 1
        else -> i
    }
}
