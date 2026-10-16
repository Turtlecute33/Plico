package app.plico.mail.smtp

import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import org.junit.Test

class DotStuffingTest {
    private fun wire(message: String): String {
        val out = ByteArrayOutputStream()
        DotStuffing.write(message.toByteArray(Charsets.UTF_8), out)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `CRLF text passes unchanged and gets no extra CRLF`() {
        assertEquals("a\r\nb\r\n", wire("a\r\nb\r\n"))
        assertEquals("a\r\n\r\n", wire("a\r\n\r\n"))
        assertEquals("", wire(""))
    }

    @Test
    fun `missing final line end is added`() {
        assertEquals("a\r\nb\r\n", wire("a\r\nb"))
        assertEquals("x\r\n", wire("x"))
    }

    @Test
    fun `bare LF and bare CR become CRLF`() {
        assertEquals("a\r\nb\r\nc\r\n", wire("a\nb\rc"))
        assertEquals("\r\n\r\n", wire("\n\n"))
        assertEquals("a\r\n\r\nb\r\n", wire("a\r\r\nb"))
    }

    @Test
    fun `lines that start with a dot get one more`() {
        assertEquals("..hidden\r\n", wire(".hidden"))
        assertEquals("a\r\n..\r\nb\r\n", wire("a\r\n.\r\nb"))
        assertEquals("a\r\n...\r\n", wire("a\r\n..\r\n"))
        assertEquals("a.b\r\n.. x\r\n", wire("a.b\r\n. x"))
        assertEquals("1.5 and .5\r\n", wire("1.5 and .5"))
    }

    @Test
    fun `smuggled end-of-data sequences are stuffed`() {
        assertEquals("a\r\n..\r\nMAIL FROM:<x@evil>\r\n", wire("a\n.\nMAIL FROM:<x@evil>"))
        assertEquals("a\r\n..\r\nb\r\n", wire("a\r.\rb"))
        assertEquals("a\r\n..\r\nb\r\n", wire("a\n.\r\nb"))
    }

    @Test
    fun `UTF-8 bytes pass unchanged`() {
        assertEquals("Grüße 用户\r\n", wire("Grüße 用户"))
    }

    @Test
    fun `size counts normalised line ends but not stuffing dots`() {
        for (message in listOf("", "a", "a\r\n", "a\nb", "a\rb", "a\r\r\nb", ".x\n..y", "\n", "\r", "Grüße\n")) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            val normalized = wire(message).toByteArray(Charsets.UTF_8)
            val stuffing = Regex("(?m)^\\.").findAll(wire(message).replace("\r\n", "\n")).count()
            assertEquals((normalized.size - stuffing).toLong(), DotStuffing.size(bytes), message)
        }
    }
}
