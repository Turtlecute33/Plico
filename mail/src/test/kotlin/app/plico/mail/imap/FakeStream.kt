package app.plico.mail.imap

import app.plico.mail.net.MailStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.fail

/**
 * A scripted IMAP server. [server] queues lines the client can read; [client] queues lines the client
 * must have written and flushed before it may read on. A read that needs a client line waits up to
 * [waitMs] for it (another thread may write it), then fails. Client bytes that arrive before the script
 * expects them also fail. [finish] checks that the script is complete.
 *
 * [startCompression] replaces [input] and [output] with new objects; the old ones then fail when used.
 */
class FakeStream(private val waitMs: Long = 2_000) : MailStream {
    private sealed interface Step
    private class Send(val bytes: ByteArray) : Step
    private class Expect(val bytes: ByteArray) : Step

    private val lock = ReentrantLock()
    private val written = lock.newCondition()
    private val steps = ArrayDeque<Step>()
    private var serverBytes = ByteArray(0)
    private var serverPos = 0
    private val unflushed = ByteArrayOutputStream()
    private var flushed = ByteArray(0)
    private var generation = 0
    private var inStream: InputStream = In(0)
    private var outStream: OutputStream = Out(0)

    @Volatile
    var closed = false
        private set
    var compressions = 0
        private set
    val timeouts = ArrayList<Int>()

    override val input: InputStream get() = lock.withLock { inStream }
    override val output: OutputStream get() = lock.withLock { outStream }

    override var readTimeoutMs: Int = 60_000
        set(value) {
            field = value
            timeouts += value
        }

    override var encrypted = true

    override fun startTls() = throw UnsupportedOperationException()

    /** Each line is sent with CRLF appended. Use `\r\n` inside a line for literals. */
    fun server(vararg lines: String) = apply { lines.forEach { lock.withLock { steps += Send(bytes(it)) } } }

    /** Each line must arrive with CRLF appended. */
    fun client(vararg lines: String) = apply { lines.forEach { lock.withLock { steps += Expect(bytes(it)) } } }

    override fun startCompression(): Unit = lock.withLock {
        if (serverPos != serverBytes.size) fail("Compression started with unread server bytes")
        if (unflushed.size() != 0) fail("Compression started with unflushed client bytes")
        generation++
        inStream = In(generation)
        outStream = Out(generation)
        compressions++
    }

    override fun close(): Unit = lock.withLock {
        closed = true
        written.signalAll()
    }

    /** Fails unless every step ran, all server bytes were read and the client sent nothing more. */
    fun finish(): Unit = lock.withLock {
        while (true) {
            when (val step = steps.removeFirstOrNull() ?: break) {
                is Send -> fail("Server line not read: ${show(step.bytes)}")
                is Expect -> awaitClient(step.bytes)
            }
        }
        if (serverPos != serverBytes.size) fail("Server bytes not read")
        if (flushed.isNotEmpty()) fail("Unexpected client bytes: ${show(flushed)}")
        if (unflushed.size() != 0) fail("Client bytes not flushed: ${show(unflushed.toByteArray())}")
    }

    private fun nextByte(): Int {
        while (true) {
            if (closed) throw IOException("closed")
            if (serverPos < serverBytes.size) return serverBytes[serverPos++].toInt() and 0xff
            when (val step = steps.removeFirstOrNull() ?: return -1) {
                is Send -> {
                    if (flushed.isNotEmpty()) fail("Client sent ${show(flushed)} before ${show(step.bytes)}")
                    serverBytes = step.bytes
                    serverPos = 0
                }
                is Expect -> awaitClient(step.bytes)
            }
        }
    }

    private fun awaitClient(expected: ByteArray) {
        val deadline = System.nanoTime() + waitMs * 1_000_000
        while (true) {
            val n = minOf(flushed.size, expected.size)
            if (!flushed.copyOf(n).contentEquals(expected.copyOf(n))) {
                fail("Expected client ${show(expected)} but got ${show(flushed)}")
            }
            if (flushed.size >= expected.size) break
            if (closed) throw IOException("closed")
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) {
                fail("Client did not send ${show(expected)}; got ${show(flushed + unflushed.toByteArray())}")
            }
            written.await(left, TimeUnit.MILLISECONDS)
        }
        flushed = flushed.copyOfRange(expected.size, flushed.size)
    }

    private fun checkGeneration(gen: Int) {
        if (gen != generation) fail("Stream used after the compression switch")
    }

    private inner class In(private val gen: Int) : InputStream() {
        override fun read(): Int = lock.withLock {
            checkGeneration(gen)
            nextByte()
        }

        override fun available(): Int = lock.withLock { serverBytes.size - serverPos }
    }

    private inner class Out(private val gen: Int) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int): Unit = lock.withLock {
            checkGeneration(gen)
            if (closed) throw IOException("closed")
            unflushed.write(b, off, len)
        }

        override fun flush(): Unit = lock.withLock {
            checkGeneration(gen)
            if (closed) throw IOException("closed")
            flushed += unflushed.toByteArray()
            unflushed.reset()
            written.signalAll()
        }
    }

    companion object {
        private fun bytes(line: String) = (line + "\r\n").toByteArray(Charsets.UTF_8)

        private fun show(bytes: ByteArray) =
            bytes.toString(Charsets.UTF_8).replace("\r", "\\r").replace("\n", "\\n")

        /** `{n}` CRLF text, with n in UTF-8 bytes. */
        fun literal(text: String) = "{${text.toByteArray(Charsets.UTF_8).size}}\r\n$text"
    }
}
