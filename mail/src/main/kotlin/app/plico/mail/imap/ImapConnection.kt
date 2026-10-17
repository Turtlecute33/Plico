package app.plico.mail.imap

import app.plico.mail.net.MailStream
import app.plico.mail.net.PlaintextTimeoutException
import app.plico.mail.net.beforeTls
import java.io.Closeable
import java.io.IOException

/**
 * Low-level IMAP connection: tags, literals, untagged routing, IDLE. See DESIGN.md §4.3.
 * Thread rules: [execute] and [idle] run on one worker thread; [done] and [close] may be called from
 * any other non-main thread.
 *
 * Fails closed: any error before the tagged response of a command or IDLE (read error or timeout, reader
 * limit, protocol error, an exception from a callback or hook) closes the stream, because the rest of
 * that response would otherwise be read as the next command's. Later calls then throw [IOException] at
 * once. A tagged NO or BAD ends the command cleanly and keeps the connection.
 *
 * The log callback gets only tags, command verbs, statuses and response-code names; a continuation and its
 * answer appear as `<<< +` and `>>> <sasl response>`, never with their data.
 */
class ImapConnection(private val stream: MailStream, private val log: (String) -> Unit = {}) : Closeable {

    /** Upper-case capability names from the greeting, a [CAPABILITY] code or a CAPABILITY response. */
    @Volatile
    var capabilities: Set<String> = emptySet()
        private set

    /** Receives EXISTS / EXPUNGE / FETCH / RECENT responses seen while any command runs. */
    var onUnsolicited: (Untagged) -> Unit = {}

    private var reader = ImapReader(stream.input)
    private var tagCounter = 0

    /** Set by [close] and by an error inside a response; the stream is then closed. */
    @Volatile
    private var broken = false

    /** Serializes every write. */
    private val writeLock = Any()

    /**
     * Guards the IDLE state below and the IDLE and DONE writes. [done] takes only this lock, so it never
     * waits behind a command write: while [idling] is true the worker writes nothing else.
     */
    private val idleLock = Any()
    private var idling = false
    private var continued = false
    private var doneRequested = false
    private var doneSent = false

    /** Reads the greeting. `* BYE` -> [ImapByeException]. */
    fun greeting(): Untagged = failClosed {
        val greeting = reader.read() as? Untagged ?: throw ImapProtocolException("Unexpected greeting")
        log("<<< * ${greeting.keyword}${codeName(greeting.code)}")
        when (greeting.keyword) {
            "OK", "PREAUTH" -> updateCapabilities(greeting)
            "BYE" -> throw ImapByeException(greeting.code, greeting.text.orEmpty())
            else -> throw ImapProtocolException("Unexpected greeting")
        }
        greeting
    }

    /**
     * Sends [command] with a new tag and reads until its tagged response. Every untagged response goes to
     * [onUntagged]; EXISTS/EXPUNGE/FETCH/RECENT also go to [onUnsolicited]. A `[CAPABILITY ...]` code updates
     * [capabilities]. NO -> [ImapNoException], BAD -> [ImapBadException], BYE -> [ImapByeException].
     */
    fun execute(command: Command, onUntagged: (Untagged) -> Unit = {}): Tagged =
        execute(command, onUntagged) { null }

    /**
     * Like [execute], and answers each server continuation (`+ text`) that is not part of a literal with
     * the line [onContinuation] returns, e.g. an empty line for the XOAUTH2 error challenge. A null answer
     * means the continuation was not expected: [ImapProtocolException].
     */
    fun execute(command: Command, onUntagged: (Untagged) -> Unit, onContinuation: (String) -> String?): Tagged {
        checkOpen()
        val tag = nextTag()
        log(">>> $tag ${logName(command)}")
        val allowBye = command.verb == "LOGOUT"
        val tagged = failClosed {
            send(tag, command) { untagged -> route(untagged, allowBye, onUntagged) }
                ?: awaitTagged(tag, allowBye, onUntagged, onContinuation)
        }
        log("<<< $tag ${tagged.status}${codeName(tagged.code)}")
        updateCapabilities(tagged.code)
        return checked(tagged)
    }

    /** Runs one IDLE until [done] (or [IdleHooks.stopRequested] after `+`) and the tagged OK. */
    fun idle(hooks: IdleHooks, onEvent: (IdleEvent) -> Unit) {
        checkOpen()
        val tag = nextTag()
        log(">>> $tag IDLE")
        val timeout = stream.readTimeoutMs
        try {
            val tagged = failClosed {
                synchronized(idleLock) {
                    idling = true
                    continued = false
                    doneRequested = false
                    doneSent = false
                    write("$tag IDLE\r\n".toByteArray(Charsets.US_ASCII), flush = true)
                }
                idleUntilTagged(tag, hooks, onEvent)
            }
            log("<<< $tag ${tagged.status}${codeName(tagged.code)}")
            checked(tagged)
        } finally {
            synchronized(idleLock) { idling = false }
            restoreTimeout(timeout)
        }
    }

    /**
     * Ends the running IDLE. Idempotent, never blocks on a command write and never throws: when DONE cannot
     * be written, the connection is closed, so that the blocked [idle] fails and reports the error. Before
     * `+` it only sets a flag; [idle] then sends DONE after `+`.
     */
    fun done() {
        synchronized(idleLock) {
            if (!idling) return
            doneRequested = true
            if (!continued) return
            try {
                sendDoneLocked()
            } catch (_: Exception) {
                close()
            }
        }
    }

    /** Sends COMPRESS DEFLATE and switches the stream. */
    fun startCompression() {
        execute(Command.raw("COMPRESS", "DEFLATE"))
        // The server compresses from its OK on, so a failed switch leaves the two ends out of step.
        failClosed { stream.startCompression() }
        reader = ImapReader(stream.input)
    }

    /**
     * Sends STARTTLS and switches the stream to TLS (RFC 3501 §6.2.1). A tagged NO or BAD throws as for any
     * command. The capabilities are cleared after the reply: the tagged OK can carry a `[CAPABILITY]` code, and
     * capabilities from before TLS are not trusted. Bytes after the OK fail the switch ([MailStream.startTls]). No
     * reply in time -> [PlaintextTimeoutException].
     */
    fun startTls() {
        beforeTls { execute(Command.raw("STARTTLS")) }
        failClosed { stream.startTls() }
        reader = ImapReader(stream.input)
        capabilities = emptySet()
    }

    /** True once TLS is up; credentials are sent only then. */
    internal val encrypted: Boolean get() = stream.encrypted

    /** False after [close] or an error that closed the connection; every later call then fails. */
    val isOpen: Boolean get() = !broken

    override fun close() {
        broken = true
        stream.close()
    }

    private fun nextTag() = "A${++tagCounter}"

    private fun checkOpen() {
        if (broken) throw IOException("Connection closed")
    }

    /** Runs [block]; any error closes the connection, see the class comment. */
    private inline fun <T> failClosed(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        close()
        throw e
    }

    /**
     * Writes the command. Synchronizing literals wait for `+`; a tagged response in its place ends the
     * command and is returned. Returns null when the whole command was sent.
     */
    private fun send(tag: String, command: Command, onUntagged: (Untagged) -> Unit): Tagged? {
        for (segment in Wire(capabilities).encode(tag, command.parts)) {
            val literal = segment.literal
            if (literal == null) {
                write(segment.text, flush = true)
                break
            }
            write(segment.text, flush = segment.synchronizing)
            if (segment.synchronizing) awaitContinuation(tag, onUntagged)?.let { return it }
            write(literal, flush = false)
        }
        return null
    }

    private fun awaitContinuation(tag: String, onUntagged: (Untagged) -> Unit): Tagged? {
        while (true) {
            when (val r = reader.read()) {
                is Continuation -> return null
                is Untagged -> onUntagged(r)
                is Tagged -> return r.also { requireTag(tag, it) }
            }
        }
    }

    private fun awaitTagged(
        tag: String,
        allowBye: Boolean,
        onUntagged: (Untagged) -> Unit,
        onContinuation: (String) -> String?,
    ): Tagged {
        while (true) {
            when (val r = reader.read()) {
                is Untagged -> route(r, allowBye, onUntagged)
                is Tagged -> return r.also { requireTag(tag, it) }
                is Continuation -> {
                    log("<<< +")
                    val answer = onContinuation(r.text) ?: throw ImapProtocolException("Unexpected continuation")
                    // A continuation answer is always SASL data (or its cancel): never logged.
                    log(">>> <sasl response>")
                    write((answer + "\r\n").toByteArray(Charsets.UTF_8), flush = true)
                }
            }
        }
    }

    private fun route(r: Untagged, allowBye: Boolean, onUntagged: (Untagged) -> Unit) {
        updateCapabilities(r)
        if (r.keyword == "BYE" && !allowBye) throw bye(r)
        if (r.keyword in CHANGES) onUnsolicited(r)
        onUntagged(r)
    }

    /** Reads until the tagged response of the IDLE [tag]. */
    private fun idleUntilTagged(tag: String, hooks: IdleHooks, onEvent: (IdleEvent) -> Unit): Tagged {
        while (true) {
            when (val r = idleRead(hooks)) {
                is Continuation -> if (!continued) idleStarted(hooks)
                is Untagged -> idleUntagged(r, onEvent)
                is Tagged -> {
                    synchronized(idleLock) { idling = false }
                    requireTag(tag, r)
                    return r
                }
            }
        }
    }

    private fun idleRead(hooks: IdleHooks): ImapResponse {
        hooks.beforeBlockingRead()
        try {
            return reader.read()
        } finally {
            hooks.afterRead()
        }
    }

    /** The server sent `+`: IDLE is active. */
    private fun idleStarted(hooks: IdleHooks) {
        stream.readTimeoutMs = 0
        val stop = hooks.stopRequested()
        synchronized(idleLock) {
            continued = true
            if (stop || doneRequested) sendDoneLocked()
        }
    }

    private fun idleUntagged(r: Untagged, onEvent: (IdleEvent) -> Unit) {
        updateCapabilities(r)
        when {
            r.keyword == "BYE" -> throw bye(r)
            r.keyword in CHANGES -> {
                onUnsolicited(r)
                onEvent(IdleEvent.Changed)
            }
            r.keyword == "OK" && r.code == null -> onEvent(IdleEvent.Keepalive)
        }
    }

    private fun sendDoneLocked() {
        if (doneSent) return
        doneSent = true
        log(">>> DONE")
        write(DONE, flush = true)
    }

    private fun write(bytes: ByteArray, flush: Boolean) = synchronized(writeLock) {
        stream.output.write(bytes)
        if (flush) stream.output.flush()
    }

    private fun restoreTimeout(ms: Int) {
        try {
            stream.readTimeoutMs = ms
        } catch (_: IOException) {
            // The stream is already closed; the caller sees the original error.
        }
    }

    private fun updateCapabilities(r: Untagged) {
        if (r.keyword == "CAPABILITY") capabilities = names(r.values)
        updateCapabilities(r.code)
    }

    private fun updateCapabilities(code: RespCode?) {
        if (code?.name == "CAPABILITY") capabilities = names(code.args)
    }

    private fun names(values: List<ImapValue>) = values.mapNotNull { it.textOrNull()?.uppercase() }.toSet()

    private fun requireTag(tag: String, r: Tagged) {
        if (r.tag != tag) throw ImapProtocolException("Unexpected tag")
    }

    private fun checked(r: Tagged): Tagged = when (r.status) {
        "OK" -> r
        "NO" -> throw ImapNoException(r.code, r.text)
        else -> throw ImapBadException(r.text)
    }

    private fun bye(r: Untagged) = ImapByeException(r.code, r.text.orEmpty())

    private fun codeName(code: RespCode?) = if (code == null) "" else " [${code.name}]"

    /** `UID FETCH` instead of `UID`; never arguments. */
    private fun logName(command: Command): String {
        val words = command.parts.asSequence()
            .takeWhile { it is Command.Part.Raw }
            .flatMap { (it as Command.Part.Raw).text.split(' ') }
            .take(2)
            .toList()
        val verb = words.firstOrNull()?.uppercase().orEmpty()
        return if (verb == "UID" && words.size > 1) "UID ${words[1].uppercase()}" else verb
    }

    /** One piece of a command on the wire: [text], then CRLF and [literal] when there is one. */
    private class Segment(val text: ByteArray, val literal: ByteArray?, val synchronizing: Boolean)

    /** Turns command parts into segments, choosing quoted strings or literals per DESIGN.md §4.3. */
    private class Wire(private val caps: Set<String>) {
        private val segments = ArrayList<Segment>()
        private val text = StringBuilder()

        fun encode(tag: String, parts: List<Command.Part>): List<Segment> {
            text.append(tag)
            parts.forEach {
                text.append(' ')
                part(it)
            }
            text.append("\r\n")
            segments += Segment(drain(), null, false)
            return segments
        }

        private fun part(part: Command.Part) {
            when (part) {
                is Command.Part.Raw -> text.append(part.text)
                is Command.Part.Text ->
                    if (part.value.all { it in QUOTABLE && it != '"' && it != '\\' }) {
                        text.append('"').append(part.value).append('"')
                    } else {
                        literal(part.value.toByteArray(Charsets.UTF_8))
                    }
                is Command.Part.Bytes -> literal(part.value)
                is Command.Part.Group -> {
                    text.append('(')
                    part.parts.forEachIndexed { i, p ->
                        if (i > 0) text.append(' ')
                        part(p)
                    }
                    text.append(')')
                }
            }
        }

        private fun literal(bytes: ByteArray) {
            val nonSync = "LITERAL+" in caps || ("LITERAL-" in caps && bytes.size <= 4096)
            text.append('{').append(bytes.size).append(if (nonSync) "+}" else "}").append("\r\n")
            segments += Segment(drain(), bytes, !nonSync)
        }

        private fun drain(): ByteArray = text.toString().toByteArray(Charsets.UTF_8).also { text.setLength(0) }
    }

    private companion object {
        val CHANGES = setOf("EXISTS", "EXPUNGE", "FETCH", "RECENT")
        val QUOTABLE = ' '..'~'
        val DONE = "DONE\r\n".toByteArray(Charsets.US_ASCII)
    }
}
