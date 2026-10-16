package app.plico.html

import java.lang.Character.UnicodeScript
import java.net.IDN
import java.util.Locale

/** Host name forms and homograph checks (DESIGN.md §11.3, research G54). */
object Idn {
    /** Scripts that may mix in one label: Latin with CJK, as in Chrome's IDN policy. */
    private val cjkMix = setOf(
        UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA,
        UnicodeScript.HANGUL, UnicodeScript.BOPOMOFO,
    )

    /** Cyrillic, Greek and Armenian letters that look like Latin letters. */
    private val latinLookalikes: Set<Int> =
        ("аьсԁеһіјкӏорԛѕԝхуү" + "αικνορτυω" + "հոսօց").codePoints().toArray().toSet()

    /**
     * Lower-case ASCII form of [host] (punycode for non-ASCII labels), without a trailing dot. Null when it is
     * not a valid DNS name: empty labels, labels over 63 or names over 253 characters, characters other than
     * letters, digits, `-` and `_`.
     */
    fun toAscii(host: String): String? {
        val ascii = try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
        } catch (_: IllegalArgumentException) {
            return null
        }.lowercase(Locale.ROOT).removeSuffix(".")
        val labels = ascii.split('.')
        val valid = ascii.length <= 253 && labels.all { label ->
            label.length in 1..63 && label.all { it in 'a'..'z' || it in '0'..'9' || it in "-_" }
        }
        return ascii.takeIf { valid }
    }

    /** Unicode form of an ASCII host; labels that are not valid punycode stay as they are. */
    fun toUnicode(host: String): String = IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED)

    /**
     * True for IP literals in the forms browsers accept: IPv6 (`[::1]`, or any `:`), and every host whose last
     * label is a number (`1.2.3.4`, `127.1`, `0x7f.1`), which the WHATWG URL parser reads as IPv4.
     */
    fun isIpAddress(host: String): Boolean {
        if (host.startsWith('[') || ':' in host) return true
        val last = host.removeSuffix(".").substringAfterLast('.').lowercase(Locale.ROOT)
        val hex = last.startsWith("0x") && last.drop(2).all { Percent.hex(it) >= 0 }
        return last.isNotEmpty() && (hex || last.all { it in '0'..'9' })
    }

    /** True when one label mixes scripts, other than Latin with CJK (`аpple.com` with a Cyrillic `а`). */
    fun isMixedScript(host: String): Boolean = labels(host).any { label ->
        val scripts = label.codePoints().toArray().map(UnicodeScript::of)
            .filter { it != UnicodeScript.COMMON && it != UnicodeScript.INHERITED }
            .toSet()
        scripts.size > 1 && !cjkMix.containsAll(scripts)
    }

    /** True when a non-ASCII label has only ASCII and Latin-lookalike letters (`аррӏе.com`, all Cyrillic). */
    fun isLookalike(host: String): Boolean = labels(host).any { label ->
        label.any { it >= '\u0080' } && label.codePoints().allMatch { it < 0x80 || it in latinLookalikes }
    }

    private fun labels(host: String): List<String> = toUnicode(host).lowercase(Locale.ROOT).split('.')
}
