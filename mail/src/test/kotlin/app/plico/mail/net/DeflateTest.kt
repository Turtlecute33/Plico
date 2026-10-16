package app.plico.mail.net

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Random
import java.util.zip.Inflater
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test

class DeflateTest {
    private val syncMarker = byteArrayOf(0, 0, -1, -1)

    /** Client output -> pipe -> server input, with room for everything a test writes. */
    private class Pipe {
        private val sink = PipedInputStream(1 shl 21)
        val output = Deflate.deflating(PipedOutputStream(sink))
        val input = Deflate.inflating(BufferedInputStream(sink))
    }

    @Test(timeout = 10_000)
    fun `every flush delivers what was written`() {
        val pipe = Pipe()
        pipe.output.write("A1 NOOP\r\n".toByteArray())
        pipe.output.flush()
        assertEquals("A1 NOOP", pipe.input.readCrlfLine())
        pipe.output.write("A2 IDLE\r\n".toByteArray())
        pipe.output.flush()
        assertEquals("A2 IDLE", pipe.input.readCrlfLine())
    }

    @Test(timeout = 10_000)
    fun `bulk read returns a complete reply without waiting for more bytes`() {
        val pipe = Pipe()
        pipe.output.write("* OK done\r\n".toByteArray())
        pipe.output.flush()
        val buffer = ByteArray(8192)
        val n = pipe.input.read(buffer)
        assertEquals("* OK done\r\n", String(buffer, 0, n))
        // A small read through the BufferedInputStream buffer must not block either.
        pipe.output.write("* 3 EXISTS\r\n".toByteArray())
        pipe.output.flush()
        val small = ByteArray(100)
        val m = pipe.input.read(small)
        assertEquals("* 3 EXISTS\r\n", String(small, 0, m))
    }

    @Test
    fun `flush ends with a SYNC_FLUSH marker and the data is raw deflate`() {
        val wire = ByteArrayOutputStream()
        val out = Deflate.deflating(wire)
        out.write("A1 NOOP\r\n".toByteArray())
        out.flush()
        val first = wire.toByteArray()
        assertContentEquals(syncMarker, first.copyOfRange(first.size - 4, first.size))
        out.write("A2 NOOP\r\n".toByteArray())
        out.flush()
        val all = wire.toByteArray()
        assertContentEquals(syncMarker, all.copyOfRange(all.size - 4, all.size))

        val inflater = Inflater(true)
        inflater.setInput(all)
        val plain = ByteArray(100)
        val n = inflater.inflate(plain)
        inflater.end()
        assertEquals("A1 NOOP\r\nA2 NOOP\r\n", String(plain, 0, n))
    }

    @Test
    fun `wrapping the buffered input keeps compressed bytes it has read ahead`() {
        val compressed = ByteArrayOutputStream().also { wire ->
            Deflate.deflating(wire).apply {
                write("* compressed\r\n".toByteArray())
                flush()
            }
        }.toByteArray()
        val buffered = BufferedInputStream(ByteArrayInputStream("A1 OK begin\r\n".toByteArray() + compressed))
        assertEquals("A1 OK begin", buffered.readCrlfLine())
        assertEquals("* compressed", Deflate.inflating(buffered).readCrlfLine())
    }

    @Test(timeout = 20_000)
    fun `large data survives many flushes`() {
        val pipe = Pipe()
        val random = Random(7)
        val data = ByteArray(600_000) { (' '.code + random.nextInt(90)).toByte() }
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(10_000 + random.nextInt(50_000), data.size - offset)
            pipe.output.write(data, offset, chunk)
            pipe.output.flush()
            offset += chunk
        }
        assertContentEquals(data, pipe.input.readExactly(data.size))
    }
}
