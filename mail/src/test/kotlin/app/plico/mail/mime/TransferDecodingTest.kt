package app.plico.mail.mime

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransferDecodingTest {
    private fun b64(s: String) = String(TransferDecoding.decode(s.toByteArray(), "base64"), Charsets.UTF_8)
    private fun qp(s: String) = String(TransferDecoding.decode(s.toByteArray(), "quoted-printable"), Charsets.UTF_8)

    @Test
    fun base64WithLineBreaks() {
        val wrapped = "SGVsbG8sIFBsaWNvISBIZWxsbywgUGxpY28hIEhlbGxvLCBQbGljbyEgSGVsbG8sIFBsaWNvISBI\r\n" +
            "ZWxsbywgUGxpY28hIA==\r\n"
        assertEquals("Hello, Plico! ".repeat(5), b64(wrapped))
    }

    @Test
    fun base64IgnoresGarbageAndMissingPadding() {
        assertEquals("Hello", b64("SGVsbG8="))
        assertEquals("Hello", b64("SGVsbG8"))
        assertEquals("Hell", b64("SGVsbA"))
        assertEquals("Hello", b64("  SG Vs\tbG8 *!\r\n"))
        assertEquals("Hello", b64("S\u0000GVs|bG8=="))
        assertEquals("", b64(""))
        assertEquals("", b64("===="))
    }

    @Test
    fun base64DanglingCharAndDataAfterPadding() {
        assertEquals("Hello", b64("SGVsbG8=X"))
        assertEquals("ab", b64("YQ==Yg=="))
        assertEquals("Hi", b64("SGk=\r\n"))
    }

    @Test
    fun base64RoundTripRandomBinary() {
        val rnd = Random(42)
        repeat(20) {
            val data = rnd.nextBytes(rnd.nextInt(0, 3000))
            val encoded = Base64.getMimeEncoder().encode(data)
            assertContentEquals(data, TransferDecoding.decode(encoded, "base64"))
            assertContentEquals(data, TransferDecoding.decodingStream(ByteArrayInputStream(encoded), "BASE64").readBytes())
        }
    }

    @Test
    fun quotedPrintableRealBody() {
        val encoded = "Dobr=C3=BD den,\r\n\r\npos=C3=ADl=C3=A1m fakturu za z=C3=A1=C5=99=C3=AD. Pros=C3=ADm o " +
            "=C3=BAhradu=\r\n do 30. 10. 2026, d=C4=9Bkuji a p=C5=99eji hezk=C3=BD den.\r\n\r\nS pozdravem\r\n" +
            "Jan Nov=C3=A1k\r\n"
        val expected = "Dobrý den,\r\n\r\nposílám fakturu za září. Prosím o úhradu do 30. 10. 2026, děkuji a " +
            "přeji hezký den.\r\n\r\nS pozdravem\r\nJan Novák\r\n"
        assertEquals(expected, qp(encoded))
    }

    @Test
    fun quotedPrintableSoftBreaks() {
        assertEquals("abcdef", qp("abc=\r\ndef"))
        assertEquals("abcdef", qp("abc=\ndef"))
        assertEquals("abcdef", qp("abc= \t\r\ndef"))
        assertEquals("abcdef", qp("abc=  \ndef"))
        assertEquals("abc", qp("abc="))
        assertEquals("abc", qp("abc= "))
    }

    @Test
    fun quotedPrintableHexCase() {
        assertEquals("é", qp("=C3=A9"))
        assertEquals("é", qp("=c3=a9"))
        assertEquals("=", qp("=3D"))
    }

    @Test
    fun quotedPrintableInvalidEqualsKeptLiterally() {
        assertEquals("a=b", qp("a=b"))
        assertEquals("=G1", qp("=G1"))
        assertEquals("=A", qp("=A"))
        assertEquals("=AZ", qp("=AZ"))
        assertEquals("=A" + "A", qp("=A=41"))
        assertEquals("50% = half", qp("50% = half"))
        assertEquals("x= y", qp("x= y"))
    }

    @Test
    fun quotedPrintableKeepsLineEndsAndTrailingSpaces() {
        assertEquals("line one  \nline two\r\n", qp("line one  \nline two\r\n"))
    }

    @Test
    fun quotedPrintableWindows1252() {
        val bytes = TransferDecoding.decode("Gr=FC=DFe aus M=FCnchen =96 =84Zitat=93 =80".toByteArray(), "quoted-printable")
        assertEquals("Grüße aus München – „Zitat“ €", TransferDecoding.text(bytes, "windows-1252"))
    }

    @Test
    fun identityEncodings() {
        val data = byteArrayOf(0, 1, 2, -1, '='.code.toByte(), 'A'.code.toByte())
        for (enc in listOf("7bit", "8bit", "binary", "x-uuencode", "", "  7BIT ")) {
            assertSame(data, TransferDecoding.decode(data, enc))
        }
    }

    @Test
    fun encodingNameIsTrimmedAndCaseInsensitive() {
        assertEquals("Hello", String(TransferDecoding.decode("SGVsbG8=".toByteArray(), " Base64\t")))
        assertEquals("é", String(TransferDecoding.decode("=C3=A9".toByteArray(), "Quoted-Printable "), Charsets.UTF_8))
    }

    @Test
    fun streamMatchesDecodeForQuotedPrintable() {
        val rnd = Random(7)
        val alphabet = "abc =\r\n\t3DFGaf09_"
        repeat(200) {
            val input = String(CharArray(rnd.nextInt(0, 400)) { alphabet[rnd.nextInt(alphabet.length)] }).toByteArray()
            val expected = TransferDecoding.decode(input, "quoted-printable")
            assertContentEquals(expected, TransferDecoding.decodingStream(ByteArrayInputStream(input), "quoted-printable").readBytes())
            assertContentEquals(expected, TransferDecoding.decodingStream(Trickle(input), "quoted-printable").readBytes())
        }
    }

    @Test
    fun streamHandlesChunkBoundaries() {
        // An escape split across reads of the underlying stream, and a large body.
        val body = ("=C3=A9t=C3=A9 =\r\n" + "x".repeat(70) + "=\n").repeat(500)
        val expected = TransferDecoding.decode(body.toByteArray(), "quoted-printable")
        val stream = TransferDecoding.decodingStream(Trickle(body.toByteArray()), "quoted-printable")
        assertContentEquals(expected, stream.readBytes())
        assertTrue(String(expected, Charsets.UTF_8).startsWith("été " + "x".repeat(70) + "été"))
    }

    @Test
    fun streamSingleByteReadsAndEnd() {
        val stream = TransferDecoding.decodingStream(ByteArrayInputStream("SGk=".toByteArray()), "base64")
        assertEquals('H'.code, stream.read())
        assertEquals('i'.code, stream.read())
        assertEquals(-1, stream.read())
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        assertEquals(0, stream.read(ByteArray(4), 0, 0))
    }

    @Test
    fun streamCloseClosesInput() {
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() {
                closed = true
            }
        }
        TransferDecoding.decodingStream(input, "base64").close()
        assertTrue(closed)
    }

    @Test
    fun streamIdentityReturnsInput() {
        val input = ByteArrayInputStream(ByteArray(3))
        assertSame(input, TransferDecoding.decodingStream(input, "8bit"))
    }

    @Test
    fun textUtf8AndAliases() {
        val cafe = "Café".toByteArray(Charsets.UTF_8)
        assertEquals("Café", TransferDecoding.text(cafe, "utf-8"))
        assertEquals("Café", TransferDecoding.text(cafe, "UTF8"))
        assertEquals("Café", TransferDecoding.text(cafe, " \"utf-8\" "))
        assertEquals("Café", TransferDecoding.text(cafe, null))
        assertEquals("Café", TransferDecoding.text(cafe, ""))
        assertEquals("Café", TransferDecoding.text(cafe, "x-unknown"))
        assertEquals("Café", TransferDecoding.text(cafe, "unknown-8bit"))
        assertEquals("Café", TransferDecoding.text(cafe, "us-ascii"))
        assertEquals("Café", TransferDecoding.text(cafe, "no-such-charset"))
        assertEquals("Café", TransferDecoding.text(cafe, "bad charset name!"))
    }

    @Test
    fun textInvalidUtf8FallsBackToWindows1252() {
        val latin = "Café – €".toByteArray(charset("windows-1252"))
        assertEquals("Café – €", TransferDecoding.text(latin, "utf-8"))
        assertEquals("Café – €", TransferDecoding.text(latin, "us-ascii"))
        assertEquals("Café – €", TransferDecoding.text(latin, null))
        // Overlong encoding of '/' is not valid UTF-8.
        assertEquals("À€¯", TransferDecoding.text(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0xAF.toByte()), "utf-8"))
    }

    @Test
    fun textNamedCharsets() {
        assertEquals("안녕하세요", TransferDecoding.text("안녕하세요".toByteArray(charset("EUC-KR")), "ks_c_5601-1987"))
        assertEquals("שלום", TransferDecoding.text("שלום".toByteArray(charset("ISO-8859-8")), "iso-8859-8-i"))
        assertEquals("Привет", TransferDecoding.text("Привет".toByteArray(charset("KOI8-R")), "koi8-r"))
        assertEquals("测试", TransferDecoding.text("测试".toByteArray(charset("GBK")), "GB2312"))
        // Mislabelled ISO-8859-1 that really is windows-1252 (smart quotes), under every Latin-1 label.
        val smart = byteArrayOf(0x93.toByte()) + "quoted".toByteArray() + byteArrayOf(0x94.toByte())
        for (label in listOf("iso-8859-1", "ISO8859_1", "latin1", "l1", "windows-1252", "cp1252")) {
            assertEquals("\u201Cquoted\u201D", TransferDecoding.text(smart, label), label)
        }
    }

    @Test
    fun textStripsBom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hi".toByteArray()
        assertEquals("Hi", TransferDecoding.text(bom, "utf-8"))
        assertEquals("Hi", TransferDecoding.text(bom, null))
        assertEquals("Hi", TransferDecoding.text("\uFEFFHi".toByteArray(Charsets.UTF_16LE), "utf-16le"))
        assertEquals("Hi", TransferDecoding.text("\uFEFFHi".toByteArray(Charsets.UTF_16BE), "utf-16"))
    }

    /** Delivers one byte per read call, to test state kept across buffer refills. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            if (len == 0) return 0
            b[off] = data[pos++]
            return 1
        }
    }

    @Test(timeout = 5_000)
    fun hostileInputStaysFast() {
        val blanks = ("=" + " ".repeat(1_000_000)).toByteArray()
        assertEquals(1_000_001, TransferDecoding.decode(blanks, "quoted-printable").size)
        val big = ByteArray(4_000_000) { 'A'.code.toByte() }
        assertEquals(3_000_000, TransferDecoding.decodingStream(ByteArrayInputStream(big), "base64").readBytes().size)
    }
}
