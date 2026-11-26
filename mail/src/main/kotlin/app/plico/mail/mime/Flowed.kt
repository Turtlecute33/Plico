package app.plico.mail.mime

/**
 * Joins the soft line breaks of a `format=flowed` text (RFC 3676 §4.5). A line that ends with a space is flowed:
 * it joins the next line of the same quote depth. [delSp] (`delsp=yes`) drops that space. Space-stuffing is
 * removed and the signature separator `-- ` never flows. Quote marks come back as `> ` per output line (FR40).
 * Both CRLF and LF input give LF output.
 */
fun unflow(text: String, delSp: Boolean): String {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var depth = 0
    var open = false

    fun flush() {
        if (open && !delSp && cur.endsWith(' ')) cur.setLength(cur.length - 1)
        val prefix = if (depth == 0) "" else ">".repeat(depth) + if (cur.isEmpty()) "" else " "
        out += prefix + cur
        cur.setLength(0)
        open = false
    }

    for (raw in text.replace("\r\n", "\n").split('\n')) {
        val d = raw.takeWhile { it == '>' }.length
        val rest = raw.substring(d).removePrefix(" ")
        if (open && d == depth && rest.isNotEmpty() && rest != "-- ") {
            cur.append(rest)
        } else {
            if (open) flush()
            depth = d
            cur.append(rest)
        }
        if (rest.endsWith(' ') && rest != "-- ") {
            if (delSp) cur.setLength(cur.length - 1)
            open = true
        } else {
            open = false
            flush()
        }
    }
    if (open) flush()
    return out.joinToString("\n")
}
