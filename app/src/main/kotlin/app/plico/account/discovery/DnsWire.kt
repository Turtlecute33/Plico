package app.plico.account.discovery

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Locale

/** One answer record of a DNS query; host names are ASCII, lower case, without a trailing dot. */
sealed interface DnsRecord {
    /** RFC 2782. An empty [target] (`.`) means that the domain does not offer the service. */
    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String) : DnsRecord

    data class Mx(val preference: Int, val host: String) : DnsRecord
}

/**
 * DNS messages in wire format (RFC 1035 §4) for the system resolver: one question out, SRV and MX records in.
 * Every read is bounds-checked; a malformed answer gives null, never an exception.
 */
object DnsWire {
    const val TYPE_SRV = 33
    const val TYPE_MX = 15

    private const val CLASS_IN = 1
    private const val HEADER = 12
    private const val FLAG_QR = 0x8000
    private const val FLAG_TC = 0x0200
    private const val FLAG_RD = 0x0100
    private const val RCODE = 0xF
    private const val MAX_LABEL = 63
    private const val MAX_NAME = 255
    private const val MAX_JUMPS = 16

    private val random = SecureRandom()

    /** A query for [name] and [type]: random id, recursion desired, one question of class IN. */
    fun query(name: String, type: Int): ByteArray {
        val labels = name.removeSuffix(".").split('.')
        require(labels.all { label -> label.length in 1..MAX_LABEL && label.all { it.code in 0x21..0x7e } }) {
            "Invalid DNS name"
        }
        val out = ByteArrayOutputStream(HEADER + name.length + 6)
        out.u16(random.nextInt(0x10000))
        out.u16(FLAG_RD)
        out.u16(1) // one question
        repeat(3) { out.u16(0) }
        for (label in labels) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        require(out.size() - HEADER <= MAX_NAME) { "DNS name too long" }
        out.u16(type)
        out.u16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * The SRV and MX records in the answer section of [answer]; other record types (CNAME …) are skipped. An
     * empty list for an error code (NXDOMAIN, SERVFAIL …); null for a truncated or malformed message.
     */
    fun parse(answer: ByteArray): List<DnsRecord>? = try {
        Reader(answer).records()
    } catch (_: Malformed) {
        null
    }

    private class Malformed : Exception() {
        override fun fillInStackTrace() = this
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun records(): List<DnsRecord>? {
            if (bytes.size < HEADER) throw Malformed()
            pos = 2
            val flags = u16()
            if (flags and FLAG_QR == 0) throw Malformed()
            if (flags and FLAG_TC != 0) return null
            if (flags and RCODE != 0) return emptyList()
            val questions = u16()
            val answers = u16()
            pos = HEADER
            repeat(questions) {
                name()
                skip(4) // type and class
            }
            val out = ArrayList<DnsRecord>()
            repeat(answers) {
                name()
                val type = u16()
                val cls = u16()
                skip(4) // TTL
                val length = u16()
                val end = pos + length
                if (end > bytes.size) throw Malformed()
                if (cls == CLASS_IN) {
                    when (type) {
                        TYPE_SRV -> out += DnsRecord.Srv(u16(), u16(), u16(), name())
                        TYPE_MX -> out += DnsRecord.Mx(u16(), name())
                    }
                    if (pos > end) throw Malformed()
                }
                pos = end
            }
            return out
        }

        /** The name at [pos], with compression pointers; [pos] moves past it. The root name is "". */
        fun name(): String {
            val labels = ArrayList<String>()
            var at = pos
            var resume = -1
            var jumps = 0
            var length = 1
            while (true) {
                val size = byte(at)
                when (size and 0xC0) {
                    0x00 -> {
                        if (size == 0) {
                            pos = if (resume >= 0) resume else at + 1
                            return labels.joinToString(".").lowercase(Locale.ROOT)
                        }
                        length += size + 1
                        if (length > MAX_NAME || at + 1 + size > bytes.size) throw Malformed()
                        labels += String(bytes, at + 1, size, Charsets.ISO_8859_1)
                        at += 1 + size
                    }
                    0xC0 -> {
                        if (++jumps > MAX_JUMPS) throw Malformed()
                        val target = ((size and 0x3F) shl 8) or byte(at + 1)
                        if (resume < 0) resume = at + 2
                        if (target >= bytes.size) throw Malformed()
                        at = target
                    }
                    // 0x40 and 0x80 label types are not defined.
                    else -> throw Malformed()
                }
            }
        }

        fun u16(): Int {
            val value = (byte(pos) shl 8) or byte(pos + 1)
            pos += 2
            return value
        }

        fun skip(n: Int) {
            if (pos + n > bytes.size) throw Malformed()
            pos += n
        }

        fun byte(at: Int): Int {
            if (at < 0 || at >= bytes.size) throw Malformed()
            return bytes[at].toInt() and 0xFF
        }
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }
}
