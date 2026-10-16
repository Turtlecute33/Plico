package app.plico.sync

import app.plico.mail.net.MailStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Bytes the client may read; reading past them fails the test, unless the server hung up ([eof]). */
private class Replies {
    private val bytes = ArrayDeque<Byte>()
    var eof = false

    fun add(line: String) = line.plus("\r\n").toByteArray(Charsets.UTF_8).forEach(bytes::addLast)

    val stream = object : InputStream() {
        override fun read(): Int = when {
            bytes.isNotEmpty() -> bytes.removeFirst().toInt() and 0xFF
            eof -> -1
            else -> throw AssertionError("The client waits for a reply that is not scripted")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bytes.isEmpty()) return read().also { if (it >= 0) b[off] = it.toByte() }
            var n = 0
            while (n < len && bytes.isNotEmpty()) b[off + n++] = bytes.removeFirst()
            return n
        }
    }
}

/**
 * A scripted IMAP server for one connection. Each [expect] matches the next command (without its tag; each
 * literal shows as `{literal}` and lands in [literals]) and queues its replies. A reply that does not start
 * with `*` or `+` is tagged; without one the server answers `OK done`.
 */
class FakeImap(capabilities: String = "IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH LITERAL+ CONDSTORE") : MailStream {
    private class Step(val command: Regex, val replies: List<String>)

    private val steps = ArrayDeque<Step>()
    private val replies = Replies()
    private val pending = ByteArrayOutputStream()
    private var continued = -1

    /** Literal bytes of the commands, in order. */
    val literals = ArrayList<ByteArray>()

    /** Commands received, without tags. */
    val commands = ArrayList<String>()

    init {
        replies.add("* OK [CAPABILITY $capabilities] ready")
    }

    override val input: InputStream = replies.stream
    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = pending.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = pending.write(b, off, len)
        override fun flush() = parse()
    }
    override var readTimeoutMs: Int = 60_000

    override fun startCompression() = throw AssertionError("COMPRESS is not scripted")

    override fun close() {
        replies.eof = true
    }

    fun expect(command: String, vararg replies: String) = expect(Regex(Regex.escape(command)), *replies)

    fun expect(command: Regex, vararg replies: String) = apply { steps += Step(command, replies.toList()) }

    /** The SELECT of [path] with its UIDVALIDITY. */
    fun select(path: String, uidValidity: Long = 7, exists: Int = 1) = expect(
        "SELECT \"$path\"",
        "* $exists EXISTS", "* OK [UIDVALIDITY $uidValidity] ok", "* OK [UIDNEXT 1000] ok", "OK [READ-WRITE] done",
    )

    /** Fails unless every scripted command arrived. */
    fun finish() {
        if (steps.isNotEmpty()) throw AssertionError("Not received: ${steps.first().command}; got $commands")
    }

    private fun parse() {
        val bytes = pending.toByteArray()
        val line = StringBuilder()
        val found = ArrayList<ByteArray>()
        var pos = 0
        var consumed = 0
        while (true) {
            val end = indexOfCrlf(bytes, pos)
            if (end < 0) break
            val text = String(bytes, pos, end - pos, Charsets.UTF_8)
            val literal = LITERAL.find(text)
            if (literal != null) {
                val size = literal.groupValues[1].toInt()
                val start = end + 2
                if (bytes.size < start + size) {
                    // A synchronizing literal waits for the continuation.
                    if (!literal.value.endsWith("+}") && continued != start) {
                        continued = start
                        replies.add("+ go")
                    }
                    break
                }
                found += bytes.copyOfRange(start, start + size)
                line.append(text, 0, literal.range.first).append("{literal}")
                pos = start + size
                continue
            }
            line.append(text)
            pos = end + 2
            consumed = pos
            literals += found
            found.clear()
            command(line.toString())
            line.setLength(0)
        }
        pending.reset()
        pending.write(bytes, consumed, bytes.size - consumed)
    }

    private fun command(full: String) {
        val tag = full.substringBefore(' ')
        val command = full.substringAfter(' ')
        commands += command
        val step = steps.removeFirstOrNull() ?: throw AssertionError("Unexpected command: $command")
        if (!step.command.matches(command)) throw AssertionError("Expected ${step.command} but got: $command")
        var tagged = false
        for (reply in step.replies) {
            if (reply.startsWith("*") || reply.startsWith("+")) {
                replies.add(reply)
            } else {
                replies.add("$tag $reply")
                tagged = true
            }
        }
        if (!tagged) replies.add("$tag OK done")
    }

    private companion object {
        val LITERAL = Regex("""\{(\d+)\+?\}$""")
    }
}

/**
 * A scripted SMTP server for one connection. Each [expect] matches the next command line and queues its
 * reply; a null reply hangs up. The message after DATA arrives as the command "." and lands in [messages].
 */
class FakeSmtp : MailStream {
    private class Step(val command: Regex, val reply: String?)

    private val steps = ArrayDeque<Step>()
    private val replies = Replies()
    private val pending = ByteArrayOutputStream()
    private var data: StringBuilder? = null

    /** Messages received after DATA, without the final dot. */
    val messages = ArrayList<String>()
    val commands = ArrayList<String>()

    init {
        replies.add("220 fake ESMTP")
    }

    override val input: InputStream = replies.stream
    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = pending.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = pending.write(b, off, len)
        override fun flush() = parse()
    }
    override var readTimeoutMs: Int = 60_000

    override fun startCompression() = throw AssertionError("SMTP has no compression")

    override fun close() {
        replies.eof = true
    }

    fun expect(command: String, reply: String?) = expect(Regex(Regex.escape(command)), reply)

    fun expect(command: Regex, reply: String?) = apply { steps += Step(command, reply) }

    /** EHLO with a SIZE limit. */
    fun ehlo(size: Long = 25_600_000) = expect("EHLO [127.0.0.1]", "250-fake\r\n250-SIZE $size\r\n250 8BITMIME")

    fun finish() {
        if (steps.isNotEmpty()) throw AssertionError("Not received: ${steps.first().command}; got $commands")
    }

    private fun parse() {
        val bytes = pending.toByteArray()
        var pos = 0
        while (true) {
            val end = indexOfCrlf(bytes, pos)
            if (end < 0) break
            val line = String(bytes, pos, end - pos, Charsets.UTF_8)
            pos = end + 2
            val body = data
            when {
                body == null -> command(line)
                line == "." -> {
                    messages += body.toString()
                    data = null
                    command(".")
                }
                else -> body.append(line.removePrefix(".")).append("\r\n")
            }
        }
        pending.reset()
        pending.write(bytes, pos, bytes.size - pos)
    }

    private fun command(line: String) {
        // After a hang-up the client may still try QUIT; nobody listens.
        if (replies.eof) return
        commands += line
        val step = steps.removeFirstOrNull() ?: throw AssertionError("Unexpected command: $line")
        if (!step.command.matches(line)) throw AssertionError("Expected ${step.command} but got: $line")
        val reply = step.reply ?: return run { replies.eof = true }
        if (line == "DATA" && reply.startsWith("354")) data = StringBuilder()
        replies.add(reply)
    }
}

private fun indexOfCrlf(bytes: ByteArray, from: Int): Int {
    for (i in from until bytes.size - 1) if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) return i
    return -1
}
