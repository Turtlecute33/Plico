package app.plico.sync

import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapConnection
import app.plico.mail.net.MailStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * An IMAP server in memory for sync tests. It holds mailboxes and answers the commands that sync sends (SELECT,
 * EXAMINE, STATUS, LIST, FETCH, UID FETCH with CHANGEDSINCE, UID SEARCH RETURN with UID and X-GM-RAW criteria)
 * synchronously: a command line written by the client queues its responses for the next reads.
 */
class SyncFakeServer(
    caps: String = "IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH CONDSTORE LIST-STATUS QUOTA",
    private val delimiter: String = ".",
) : MailStream {

    class Msg(
        val uid: Long,
        val gmMsgId: Long,
        val date: Long,
        val header: String,
        val flags: MutableSet<String>,
        var modSeq: Long,
        val labels: MutableList<String>,
        val category: String,
    )

    inner class Box(val name: String, val attributes: List<String>, var uidValidity: Long) {
        var uidNext = 1L
        var modSeq = 1L
        val msgs = ArrayList<Msg>()

        /** Delivers a message; returns it. */
        fun add(
            subject: String = "Hello",
            from: String = "Ann <ann@example.org>",
            flags: Set<String> = emptySet(),
            labels: List<String> = emptyList(),
            category: String = "primary",
            date: Long = System.currentTimeMillis(),
            gmMsgId: Long = nextMsgId++,
        ): Msg {
            val header = "From: $from\r\nTo: me@example.org\r\nSubject: $subject\r\nMessage-ID: <m$gmMsgId@example.org>\r\n"
            return Msg(uidNext++, gmMsgId, date, header, flags.toMutableSet(), ++modSeq, labels.toMutableList(), category)
                .also(msgs::add)
        }

        fun addAll(count: Int, labels: List<String> = emptyList()) = repeat(count) { add(subject = "Old $it", labels = labels) }

        fun flag(uid: Long, flag: String, on: Boolean = true) {
            val msg = msgs.first { it.uid == uid }
            if (on) msg.flags += flag else msg.flags -= flag
            msg.modSeq = ++modSeq
        }

        fun setLabels(uid: Long, vararg labels: String) {
            val msg = msgs.first { it.uid == uid }
            msg.labels.clear()
            msg.labels += labels
            msg.modSeq = ++modSeq
        }

        fun expunge(uids: Collection<Long>) {
            msgs.removeAll { it.uid in uids }
            modSeq++
        }

        /** Skips UIDs, as Gmail does for mail that went to Trash or Spam. */
        fun skipUids(count: Int) {
            uidNext += count
        }
    }

    val boxes = LinkedHashMap<String, Box>()

    /** Command lines received, without tags. */
    val commands = ArrayList<String>()

    private var selected: Box? = null
    private var nextMsgId = 1_000L
    private var pendingUntagged = ArrayList<String>()
    private val toClient = ArrayDeque<Byte>()
    private val line = ByteArrayOutputStream()

    init {
        reply("* OK [CAPABILITY $caps] Fake ready")
    }

    fun box(name: String, vararg attributes: String, uidValidity: Long = 1): Box =
        Box(name, attributes.toList(), uidValidity).also { boxes[name] = it }

    fun client(): ImapClient = ImapClient(ImapConnection(this)).also { it.conn.greeting() }

    /** Sends [untagged] (e.g. `* 9 EXISTS`) before the reply to the next command. */
    fun announce(untagged: String) {
        pendingUntagged += untagged
    }

    /** Commands received since the last call, for assertions. */
    fun take(): List<String> = commands.toList().also { commands.clear() }

    // ---- MailStream

    override val input: InputStream = object : InputStream() {
        override fun read(): Int = toClient.removeFirstOrNull()?.toInt()?.and(0xff)
            ?: throw IOException("Fake server: the client waits for a reply that was never sent")
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            line.write(b)
            if (b == '\n'.code) {
                val text = line.toString(Charsets.UTF_8).trimEnd('\r', '\n')
                line.reset()
                handle(text)
            }
        }
    }

    override var readTimeoutMs: Int = 0

    override fun startCompression() = error("Not used")

    override fun close() {}

    // ---- Commands

    private fun handle(text: String) {
        val tag = text.substringBefore(' ')
        val command = text.substringAfter(' ')
        commands += command
        pendingUntagged.forEach(::reply)
        pendingUntagged.clear()
        val verb = command.substringBefore(' ').uppercase(Locale.ROOT)
        val args = command.substringAfter(' ', "")
        when (verb) {
            "SELECT", "EXAMINE" -> open(tag, astring(args).first)
            "STATUS" -> status(tag, args)
            "LIST" -> list(tag, args)
            "FETCH" -> fetch(tag, args, byUid = false)
            "UID" -> uid(tag, args)
            "ENABLE" -> reply("* ENABLED CONDSTORE", "$tag OK Enabled")
            "NOOP", "CAPABILITY" -> reply("$tag OK Done")
            "CREATE" -> {
                box(astring(args).first)
                reply("$tag OK Created")
            }
            "GETQUOTAROOT" -> reply("* QUOTAROOT INBOX \"\"", "* QUOTA \"\" (STORAGE 512 1048576)", "$tag OK Quota")
            "LOGOUT" -> reply("* BYE Bye", "$tag OK Logged out")
            else -> reply("$tag BAD Unknown command")
        }
    }

    private fun uid(tag: String, args: String) {
        val verb = args.substringBefore(' ').uppercase(Locale.ROOT)
        val rest = args.substringAfter(' ')
        when (verb) {
            "FETCH" -> fetch(tag, rest, byUid = true)
            "SEARCH" -> search(tag, rest)
            else -> reply("$tag BAD Unknown UID command")
        }
    }

    private fun open(tag: String, name: String) {
        val box = boxes[name] ?: return reply("$tag NO [NONEXISTENT] No such mailbox")
        selected = box
        reply(
            "* ${box.msgs.size} EXISTS",
            "* OK [UIDVALIDITY ${box.uidValidity}] UIDs valid",
            "* OK [UIDNEXT ${box.uidNext}] Predicted next UID",
            "* OK [HIGHESTMODSEQ ${box.modSeq}] Highest",
            "$tag OK [READ-WRITE] Select completed",
        )
    }

    private fun status(tag: String, args: String) {
        val box = boxes[astring(args).first] ?: return reply("$tag NO [NONEXISTENT] No such mailbox")
        reply("* STATUS ${quote(box.name)} ${statusItems(box)}", "$tag OK Status completed")
    }

    private fun list(tag: String, args: String) {
        val withStatus = "STATUS" in args
        for (box in boxes.values) {
            reply("* LIST (${(listOf("\\HasNoChildren") + box.attributes).joinToString(" ")}) \"$delimiter\" ${quote(box.name)}")
            if (withStatus && box.attributes.none { it.equals("\\Noselect", true) }) {
                reply("* STATUS ${quote(box.name)} ${statusItems(box)}")
            }
        }
        reply("$tag OK List completed")
    }

    private fun statusItems(box: Box) =
        "(MESSAGES ${box.msgs.size} UIDNEXT ${box.uidNext} UIDVALIDITY ${box.uidValidity} HIGHESTMODSEQ ${box.modSeq})"

    /** `FETCH 3:* (items)` or, by UID, `5:* (items) (CHANGEDSINCE n)`. */
    private fun fetch(tag: String, args: String, byUid: Boolean) {
        val box = selected ?: return reply("$tag BAD No mailbox selected")
        val set = args.substringBefore(' ')
        val items = group(args, args.indexOf('('))
        val changedSince = Regex("""\(CHANGEDSINCE (\d+)\)\s*$""").find(args)?.groupValues?.get(1)?.toLong()
        val out = ByteArrayOutputStream()
        box.msgs.forEachIndexed { index, msg ->
            val seq = index + 1L
            val matches = if (byUid) inSet(set, msg.uid, box.msgs.lastOrNull()?.uid ?: 0) else inSet(set, seq, box.msgs.size.toLong())
            if (!matches || (changedSince != null && msg.modSeq <= changedSince)) return@forEachIndexed
            out.write(fetchResponse(seq, msg, items, changedSince != null))
        }
        toClient.addAll(out.toByteArray().toList())
        reply("$tag OK Fetch completed")
    }

    private fun fetchResponse(seq: Long, msg: Msg, items: String, withModSeq: Boolean): ByteArray {
        val parts = ArrayList<String>()
        parts += "UID ${msg.uid}"
        if ("FLAGS" in items.split(' ', '(', ')')) parts += "FLAGS (${msg.flags.joinToString(" ")})"
        if ("INTERNALDATE" in items) parts += "INTERNALDATE \"${INTERNAL_DATE.format(Instant.ofEpochMilli(msg.date))}\""
        if ("RFC822.SIZE" in items) parts += "RFC822.SIZE ${msg.header.length + 100}"
        if ("MODSEQ" in items || withModSeq) parts += "MODSEQ (${msg.modSeq})"
        if ("X-GM-MSGID" in items) parts += "X-GM-MSGID ${msg.gmMsgId}"
        if ("X-GM-LABELS" in items) parts += "X-GM-LABELS (${msg.labels.joinToString(" ") { if (it.startsWith('\\')) it else quote(it) }})"
        val out = ByteArrayOutputStream()
        out.write("* $seq FETCH (${parts.joinToString(" ")}".toByteArray())
        val section = Regex("""BODY\.PEEK\[(HEADER\.FIELDS \([^)]*\))]""").find(items)?.groupValues?.get(1)
        if (section != null) {
            val bytes = (msg.header + "\r\n").toByteArray()
            out.write(" BODY[$section] {${bytes.size}}\r\n".toByteArray())
            out.write(bytes)
        }
        out.write(")\r\n".toByteArray())
        return out.toByteArray()
    }

    /** `RETURN (COUNT|ALL|MAX) UID <set> [X-GM-RAW "category:a OR category:b"]`. */
    private fun search(tag: String, args: String) {
        val box = selected ?: return reply("$tag BAD No mailbox selected")
        val returns = Regex("""RETURN \((\w+)\)""").find(args)!!.groupValues[1]
        val set = Regex("""\bUID (\S+)""").find(args.substringAfter(')'))!!.groupValues[1]
        val categories = Regex("""X-GM-RAW "([^"]*)"""").find(args)?.groupValues?.get(1)
            ?.split(" OR ")?.map { it.removePrefix("category:") }
        val maxUid = box.msgs.lastOrNull()?.uid ?: 0
        val hits = box.msgs.filter { inSet(set, it.uid, maxUid) && (categories == null || it.category in categories) }
            .map { it.uid }
        val result = when (returns) {
            "COUNT" -> " COUNT ${hits.size}"
            "ALL" -> if (hits.isEmpty()) "" else " ALL ${hits.joinToString(",")}"
            else -> hits.maxOrNull()?.let { " MAX $it" }.orEmpty()
        }
        reply("* ESEARCH (TAG \"$tag\") UID$result", "$tag OK Search completed")
    }

    // ---- Helpers

    private fun reply(vararg lines: String) {
        for (l in lines) toClient.addAll("$l\r\n".toByteArray().toList())
    }

    /** RFC 3501 sets: `*` is the highest number in use; `n:*` with n above it still matches the last one. */
    private fun inSet(set: String, value: Long, max: Long): Boolean = set.split(',').any { part ->
        val ends = part.split(':').map { if (it == "*") max else it.toLong() }
        value in minOf(ends.first(), ends.last())..maxOf(ends.first(), ends.last())
    }

    /** A quoted string or atom at the start of [s], and the rest. */
    private fun astring(s: String): Pair<String, String> {
        if (!s.startsWith('"')) return s.substringBefore(' ') to s.substringAfter(' ', "")
        val end = s.indexOf('"', 1)
        return s.substring(1, end) to s.substring(end + 1).trimStart()
    }

    /** The parenthesized group that starts at [start], without its outer parentheses. */
    private fun group(s: String, start: Int): String {
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '(', '[' -> depth++
                ')', ']' -> if (--depth == 0) return s.substring(start + 1, i)
            }
        }
        return s.substring(start + 1)
    }

    private fun quote(name: String) = "\"$name\""

    private companion object {
        val INTERNAL_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.US).withZone(ZoneOffset.UTC)
    }
}
