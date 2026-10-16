package app.plico.mail.mime

/** Unfolded header fields in order. Names compare case-insensitively. Values are raw (not 2047-decoded). */
class HeaderBlock(val fields: List<Pair<String, String>>) {
    operator fun get(name: String): String? = fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    fun all(name: String): List<String> = fields.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    companion object {
        /**
         * Parses a header block (stops at the first empty line). Raw 8-bit bytes: UTF-8, else ISO-8859-1, per line.
         * CRLF and LF line ends; lines without a valid field name are skipped with their continuation lines.
         */
        fun parse(bytes: ByteArray): HeaderBlock {
            val unfolded = ArrayList<StringBuilder>()
            var start = 0
            while (start < bytes.size) {
                var end = start
                while (end < bytes.size && bytes[end] != LF) end++
                val next = end + 1
                if (end > start && bytes[end - 1] == CR) end--
                if (end == start) break
                val line = TransferDecoding.utf8OrNull(bytes, start, end - start)
                    ?: String(bytes, start, end - start, Charsets.ISO_8859_1)
                if (line[0] == ' ' || line[0] == '\t') unfolded.lastOrNull()?.append(line) else unfolded += StringBuilder(line)
                start = next
            }
            return HeaderBlock(unfolded.mapNotNull(::field))
        }

        private fun field(line: CharSequence): Pair<String, String>? {
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trimEnd(' ', '\t')
            if (name.isEmpty() || name.any { it <= ' ' || it >= '\u007F' }) return null
            return name to line.substring(colon + 1).trim(' ', '\t')
        }

        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
