package app.plico.account.discovery

import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DnsWireTest {
    private val srv = DnsWire.TYPE_SRV
    private val mx = DnsWire.TYPE_MX

    /** Wire bytes of a name without compression; "" is the root. */
    private fun name(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (text.isNotEmpty()) {
            for (label in text.split('.')) {
                out.write(label.length)
                out.write(label.toByteArray())
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun pointer(offset: Int) = byteArrayOf((0xC0 or (offset shr 8)).toByte(), offset.toByte())

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun rr(owner: ByteArray, type: Int, rdata: ByteArray, cls: Int = 1) =
        owner + u16(type) + u16(cls) + byteArrayOf(0, 0, 0x0e, 0x10) + u16(rdata.size) + rdata

    private fun srvData(priority: Int, weight: Int, port: Int, target: ByteArray) =
        u16(priority) + u16(weight) + u16(port) + target

    private fun mxData(preference: Int, host: ByteArray) = u16(preference) + host

    /** A response with one question for [question] and the given answers. QR and RD/RA are set. */
    private fun response(
        question: String,
        type: Int,
        answers: List<ByteArray>,
        flags: Int = 0x8180,
    ): ByteArray = u16(0x1234) + u16(flags) + u16(1) + u16(answers.size) + u16(0) + u16(0) +
        name(question) + u16(type) + u16(1) + answers.fold(ByteArray(0)) { a, b -> a + b }

    /** Offset of the question name in [response]. */
    private val questionAt = 12

    @Test
    fun `query has one question with recursion desired`() {
        val query = DnsWire.query("_imaps._tcp.Example.org", srv)
        assertEquals(0x01, query[2].toInt())
        assertEquals(0x00, query[3].toInt())
        assertContentEquals(u16(1) + u16(0) + u16(0) + u16(0), query.copyOfRange(4, 12))
        assertContentEquals(name("_imaps._tcp.Example.org") + u16(33) + u16(1), query.copyOfRange(12, query.size))
        val mxQuery = DnsWire.query("example.org.", mx)
        assertContentEquals(name("example.org") + u16(15) + u16(1), mxQuery.copyOfRange(12, mxQuery.size))
        val ids = List(20) { DnsWire.query("example.org", mx).copyOfRange(0, 2).toList() }.toSet()
        assertEquals(true, ids.size > 1, "random ids")
    }

    @Test
    fun `query refuses names that DNS cannot carry`() {
        assertFailsWith<IllegalArgumentException> { DnsWire.query("a..b", mx) }
        assertFailsWith<IllegalArgumentException> { DnsWire.query("x".repeat(64) + ".org", mx) }
        assertFailsWith<IllegalArgumentException> { DnsWire.query(List(5) { "x".repeat(60) }.joinToString("."), mx) }
        assertFailsWith<IllegalArgumentException> { DnsWire.query("a b.org", mx) }
    }

    @Test
    fun `SRV records are read in answer order`() {
        val q = "_imaps._tcp.example.org"
        val packet = response(
            q, srv,
            listOf(
                rr(pointer(questionAt), srv, srvData(20, 0, 993, name("Backup.Example.org"))),
                rr(pointer(questionAt), srv, srvData(10, 5, 993, name("imap.example.org"))),
            ),
        )
        assertEquals(
            listOf(DnsRecord.Srv(20, 0, 993, "backup.example.org"), DnsRecord.Srv(10, 5, 993, "imap.example.org")),
            DnsWire.parse(packet),
        )
    }

    @Test
    fun `SRV choice is lowest priority, then highest weight`() {
        val records = DnsWire.parse(
            response(
                "_imaps._tcp.example.org", srv,
                listOf(
                    rr(pointer(questionAt), srv, srvData(20, 100, 993, name("c.example.org"))),
                    rr(pointer(questionAt), srv, srvData(10, 1, 993, name("light.example.org"))),
                    rr(pointer(questionAt), srv, srvData(10, 60, 993, name("heavy.example.org"))),
                ),
            ),
        )
        assertEquals(Endpoint("heavy.example.org", 993, TlsMode.IMPLICIT), Discovery.srvEndpoint(records, null))
    }

    @Test
    fun `a dot target means no service`() {
        val records = DnsWire.parse(
            response("_imap._tcp.example.org", srv, listOf(rr(pointer(questionAt), srv, srvData(0, 0, 0, name(""))))),
        )
        assertEquals(listOf(DnsRecord.Srv(0, 0, 0, "")), records)
        assertNull(Discovery.srvEndpoint(null, records))
    }

    @Test
    fun `MX records and the lowest preference`() {
        val records = DnsWire.parse(
            response(
                "example.org", mx,
                listOf(
                    rr(pointer(questionAt), mx, mxData(20, name("alt1.aspmx.l.google.com"))),
                    rr(pointer(questionAt), mx, mxData(10, name("ASPMX.L.GOOGLE.COM"))),
                    rr(pointer(questionAt), mx, mxData(10, name("second.example.org"))),
                ),
            ),
        )
        assertEquals(
            listOf(
                DnsRecord.Mx(20, "alt1.aspmx.l.google.com"),
                DnsRecord.Mx(10, "aspmx.l.google.com"),
                DnsRecord.Mx(10, "second.example.org"),
            ),
            records,
        )
        assertEquals("aspmx.l.google.com", Discovery.lowestMx(records))
    }

    @Test
    fun `compressed names follow pointers`() {
        // The target "mail" + pointer to "example.org" inside the question name.
        val suffixAt = questionAt + 1 + "_imaps".length + 1 + "_tcp".length
        val target = byteArrayOf(4) + "mail".toByteArray() + pointer(suffixAt)
        val packet = response("_imaps._tcp.example.org", srv, listOf(rr(pointer(questionAt), srv, srvData(0, 1, 993, target))))
        assertEquals(listOf(DnsRecord.Srv(0, 1, 993, "mail.example.org")), DnsWire.parse(packet))
    }

    @Test
    fun `other record types are skipped`() {
        val cname = rr(pointer(questionAt), 5, name("real.example.net"))
        val packet = response(
            "example.org", mx,
            listOf(cname, rr(name("real.example.net"), mx, mxData(5, name("mx.example.net"))), rr(pointer(questionAt), mx, mxData(1, name("x.example.org")), cls = 3)),
        )
        assertEquals(listOf(DnsRecord.Mx(5, "mx.example.net")), DnsWire.parse(packet))
    }

    @Test
    fun `pointer loops and pointers out of range are malformed`() {
        val answerAt = 12 + name("example.org").size + 4
        // Owner pointer, type, class, TTL and length are 12 bytes; the preference 2 more: the name points at itself.
        val loop = response("example.org", mx, listOf(rr(pointer(questionAt), mx, mxData(1, pointer(answerAt + 14)))))
        assertNull(DnsWire.parse(loop), "a pointer to itself")
        val outside = response("example.org", mx, listOf(rr(pointer(questionAt), mx, mxData(1, pointer(4000)))))
        assertNull(DnsWire.parse(outside))
    }

    @Test
    fun `undefined label types are malformed`() {
        for (prefix in listOf(0x40, 0x80)) {
            val bad = byteArrayOf(prefix.toByte(), 0)
            assertNull(DnsWire.parse(response("example.org", mx, listOf(rr(pointer(questionAt), mx, mxData(1, bad))))), "$prefix")
        }
    }

    @Test
    fun `truncated and short messages`() {
        val packet = response("example.org", mx, listOf(rr(pointer(questionAt), mx, mxData(1, name("mx.example.org")))))
        for (cut in listOf(5, 11, 20, packet.size - 1)) assertNull(DnsWire.parse(packet.copyOf(cut)), "cut at $cut")
        assertNull(DnsWire.parse(ByteArray(0)))
        val tc = response("example.org", mx, listOf(rr(pointer(questionAt), mx, mxData(1, name("mx.example.org")))), flags = 0x8380)
        assertNull(DnsWire.parse(tc), "TC: the answer is not complete")
        val notAnswer = response("example.org", mx, emptyList(), flags = 0x0100)
        assertNull(DnsWire.parse(notAnswer), "QR not set")
    }

    @Test
    fun `record data must fit its length`() {
        val rdata = mxData(1, name("mx.example.org"))
        val short = pointer(questionAt) + u16(mx) + u16(1) + byteArrayOf(0, 0, 0, 1) + u16(3) + rdata
        val packet = u16(1) + u16(0x8180) + u16(1) + u16(1) + u16(0) + u16(0) + name("example.org") + u16(mx) + u16(1) + short
        assertNull(DnsWire.parse(packet))
    }

    @Test
    fun `error codes give no records`() {
        assertEquals(emptyList(), DnsWire.parse(response("example.org", mx, emptyList(), flags = 0x8183)), "NXDOMAIN")
        assertEquals(emptyList(), DnsWire.parse(response("example.org", mx, emptyList(), flags = 0x8182)), "SERVFAIL")
        assertEquals(emptyList(), DnsWire.parse(response("example.org", mx, emptyList())), "no data")
    }

    @Test
    fun `long names are malformed`() {
        val long = ByteArrayOutputStream()
        repeat(5) {
            long.write(60)
            long.write("x".repeat(60).toByteArray())
        }
        long.write(0)
        assertNull(DnsWire.parse(response("example.org", mx, listOf(rr(pointer(questionAt), mx, mxData(1, long.toByteArray()))))))
    }
}
