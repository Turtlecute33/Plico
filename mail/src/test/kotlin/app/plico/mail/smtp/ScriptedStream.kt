package app.plico.mail.smtp

import app.plico.mail.net.MailStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.assertEquals

/**
 * A fake server that plays a dialog. Each [Step.Server] reply is released only after the client has
 * flushed exactly the [Step.Client] text before it, so the test also checks that the client flushes before
 * it waits. The script ends with EOF.
 */
internal class ScriptedStream(vararg steps: Step, private val failOnFlush: Int = 0) : MailStream {
    sealed interface Step {
        class Client(val text: String) : Step
        class Server(val text: String) : Step
        class Fail(val error: IOException) : Step
    }

    private val script = steps.toList()
    private var next = 0
    private var reply = ByteArray(0)
    private var replyPos = 0
    private val unflushed = ByteArrayOutputStream()
    private val flushed = ByteArrayOutputStream()
    private var flushes = 0

    /** Everything the client flushed, in order. */
    val transcript = StringBuilder()

    /** [readTimeoutMs] at the moment each server step was released or failed. */
    val timeoutsAtRead = mutableListOf<Int>()
    var closed = false
        private set

    override var readTimeoutMs = 60_000
    override var encrypted = true

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            if (replyPos == reply.size && !advance()) return -1
            return reply[replyPos++].toInt() and 0xff
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = unflushed.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = unflushed.write(b, off, len)
        override fun flush() {
            if (++flushes == failOnFlush) throw IOException("broken pipe (test)")
            transcript.append(unflushed.toString(Charsets.UTF_8))
            unflushed.writeTo(flushed)
            unflushed.reset()
        }
    }

    override fun startCompression() = throw UnsupportedOperationException()
    override fun startTls() = throw UnsupportedOperationException()

    override fun close() {
        closed = true
    }

    /** Checks the client text that is due, then loads the next server step. False at the end of the script. */
    private fun advance(): Boolean {
        checkClientSteps()
        val step = script.getOrNull(next) ?: return false
        next++
        timeoutsAtRead += readTimeoutMs
        when (step) {
            is Step.Server -> {
                reply = step.text.toByteArray(Charsets.UTF_8)
                replyPos = 0
            }
            is Step.Fail -> throw step.error
            is Step.Client -> error("unreachable")
        }
        return true
    }

    private fun checkClientSteps() {
        val expected = StringBuilder()
        while (next < script.size && script[next] is Step.Client) expected.append((script[next++] as Step.Client).text)
        assertEquals(expected.toString(), flushed.toString(Charsets.UTF_8), "client output before server step $next")
        flushed.reset()
    }

    /** Asserts that the whole script ran and that the client sent nothing more. */
    fun assertDone() {
        checkClientSteps()
        assertEquals(script.size, next, "unused script steps")
        assertEquals("", unflushed.toString(Charsets.UTF_8), "unflushed client output")
    }
}

/** A client line; CRLF is added. */
internal fun c(line: String) = ScriptedStream.Step.Client("$line\r\n")

/** A server reply; each line gets CRLF. */
internal fun s(vararg lines: String) = ScriptedStream.Step.Server(lines.joinToString("") { "$it\r\n" })
