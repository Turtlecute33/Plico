package app.plico.mail.imap

import app.plico.mail.net.MailSocket
import app.plico.mail.net.SocketHooks
import app.plico.mail.sasl.Sasl
import java.io.Closeable
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Typed IMAP operations used by the app. See DESIGN.md §4.4.
 * Mailbox names and Gmail labels are given and returned decoded; this class encodes them to modified
 * UTF-7 for the wire. Commands that take a [UidSet] are split into chunks of at most 500 ranges.
 */
class ImapClient(val conn: ImapConnection) : Closeable {

    val caps: Set<String> get() = conn.capabilities
    val isGmail: Boolean get() = "X-GM-EXT-1" in caps

    /** True when the server sent its capabilities after authentication. */
    private var capsAfterAuth = false
    private var current: String? = null

    /** SASL PLAIN with an initial response (SASL-IR). */
    fun authenticatePlain(user: String, password: String): Unit = authenticate("PLAIN", Sasl.plain(user, password))

    /** Google XOAUTH2 with an initial response. A failed token gets a challenge, then NO. */
    fun authenticateXoauth2(user: String, accessToken: String): Unit =
        authenticate("XOAUTH2", Sasl.xoauth2(user, accessToken))

    /** Sends CAPABILITY when needed; throws [ImapMissingCapabilityException] when [REQUIRED] is not met. */
    fun requireCapabilities() {
        if (!capsAfterAuth) {
            conn.execute(Command.raw("CAPABILITY"))
            capsAfterAuth = true
        }
        val missing = REQUIRED - caps
        if (missing.isNotEmpty()) throw ImapMissingCapabilityException(missing)
    }

    /** `ENABLE`; returns the upper-case extensions the server reports as enabled. */
    fun enable(vararg ext: String): Set<String> {
        if (ext.isEmpty()) return emptySet()
        val enabled = HashSet<String>()
        conn.execute(Command.raw("ENABLE", *ext)) { r ->
            if (r.keyword == "ENABLED") r.values.mapNotNullTo(enabled) { it.textOrNull()?.uppercase() }
        }
        return enabled
    }

    /** Starts COMPRESS=DEFLATE when the server advertises it. */
    fun compress() {
        if ("COMPRESS=DEFLATE" in caps) conn.startCompression()
    }

    /** `LIST "" "*" RETURN (SPECIAL-USE)` (plain LIST when SPECIAL-USE is not advertised). */
    fun list(): List<MailboxInfo> {
        val command = Command.build {
            raw("LIST")
            string("")
            string("*")
            if ("SPECIAL-USE" in caps) raw("RETURN (SPECIAL-USE)")
        }
        val out = ArrayList<MailboxInfo>()
        conn.execute(command) { r -> if (r.keyword == "LIST") mailboxInfo(r.values)?.let(out::add) }
        return out
    }

    /** `STATUS` with MESSAGES UIDNEXT UIDVALIDITY, plus HIGHESTMODSEQ when CONDSTORE is advertised. */
    fun status(mailbox: String): MailboxStatus {
        var values = emptyMap<String, Long>()
        conn.execute(Command.build { raw("STATUS"); string(MailboxName.encode(mailbox)); raw("($statusQuery)") }) { r ->
            if (r.keyword == "STATUS") values = statusItems(r.values.getOrNull(1))
        }
        return mailboxStatus(values) ?: throw ImapProtocolException("Incomplete STATUS")
    }

    /**
     * `LIST "" "*" RETURN (STATUS (...))` (RFC 5819, needs LIST-STATUS): the [status] of every mailbox in one
     * round trip, by decoded name. Incomplete entries are left out.
     */
    fun listStatus(): Map<String, MailboxStatus> {
        val out = HashMap<String, MailboxStatus>()
        val command = Command.build {
            raw("LIST")
            string("")
            string("*")
            raw("RETURN (STATUS ($statusQuery))")
        }
        conn.execute(command) { r ->
            if (r.keyword != "STATUS") return@execute
            val wire = r.values.getOrNull(0)?.textOrNull() ?: return@execute
            mailboxStatus(statusItems(r.values.getOrNull(1)))?.let { out[nameOf(wire)] = it }
        }
        return out
    }

    private val statusQuery: String get() = "MESSAGES UIDNEXT UIDVALIDITY" + if ("CONDSTORE" in caps) " HIGHESTMODSEQ" else ""

    fun select(mailbox: String): SelectResult = open("SELECT", mailbox)
    fun examine(mailbox: String): SelectResult = open("EXAMINE", mailbox)

    /** The mailbox selected now, or null. */
    val selected: String? get() = current

    /** `UID SEARCH RETURN (ALL) [CHARSET UTF-8] <criteria>`. */
    fun uidSearch(criteria: Command): UidSet {
        var result = UidSet.EMPTY
        search("ALL", criteria) { r ->
            result = if (r.keyword == "ESEARCH") {
                esearchItems(r.values)["ALL"]?.let(::uidSet) ?: UidSet.EMPTY
            } else {
                UidSet.of(r.values.mapNotNull { it.longOrNull() })
            }
        }
        return result
    }

    /** `UID SEARCH RETURN (COUNT) [CHARSET UTF-8] <criteria>`. */
    fun uidSearchCount(criteria: Command): Long {
        var count = 0L
        search("COUNT", criteria) { r ->
            count = if (r.keyword == "ESEARCH") {
                esearchItems(r.values)["COUNT"]?.toLongOrNull() ?: 0
            } else {
                r.values.size.toLong()
            }
        }
        return count
    }

    /**
     * `UID SEARCH RETURN (MAX) [CHARSET UTF-8] <criteria>`: the highest matching UID, or null when nothing
     * matches. One short line however many UIDs match (the backfill step, DESIGN.md §7.2).
     */
    fun uidSearchMax(criteria: Command): Long? {
        var max: Long? = null
        search("MAX", criteria) { r ->
            max = if (r.keyword == "ESEARCH") {
                esearchItems(r.values)["MAX"]?.let { it.toLongOrNull() ?: throw ImapProtocolException("Malformed MAX") }
            } else {
                r.values.mapNotNull { it.longOrNull() }.maxOrNull()
            }
        }
        return max
    }

    /**
     * `FETCH <seqRange> (<items>)` by sequence number; used for the first-sync window. UID is always
     * requested. [each] runs while the response is read.
     */
    fun fetch(seqRange: String, items: String, each: (FetchItem) -> Unit): Unit =
        fetchItems(Command.raw("FETCH", seqRange, "(${withUid(items)})"), each)

    /**
     * `UID FETCH <uids> (<items>) [(CHANGEDSINCE n)]`. UID is always requested. [each] runs while the
     * response is read.
     */
    fun uidFetch(uids: UidSet, items: String, changedSince: Long? = null, each: (FetchItem) -> Unit): Unit =
        forChunks(uids) { chunk ->
            val command = Command.build {
                raw("UID FETCH")
                raw(chunk.toString())
                raw("(${withUid(items)})")
                if (changedSince != null) raw("(CHANGEDSINCE $changedSince)")
            }
            fetchItems(command, each)
        }

    /** `UID STORE <uids> ±FLAGS.SILENT (<flags>)`. */
    fun uidStoreFlags(uids: UidSet, add: Boolean, flags: List<String>) {
        if (flags.isEmpty()) return
        flags.forEach(::requireFlag)
        store(uids, if (add) "+FLAGS.SILENT" else "-FLAGS.SILENT") { flags.forEach { raw(it) } }
    }

    /** `UID STORE <uids> ±X-GM-LABELS.SILENT (...)`: system labels (`\Inbox`) as atoms, user labels as strings. */
    fun uidStoreLabels(uids: UidSet, add: Boolean, labels: List<String>) {
        if (labels.isEmpty()) return
        store(uids, if (add) "+X-GM-LABELS.SILENT" else "-X-GM-LABELS.SILENT") {
            labels.forEach { if (it.startsWith('\\')) raw(requireFlag(it)) else string(MailboxName.encode(it)) }
        }
    }

    /** `UID MOVE`. Returns the COPYUID mapping old UID -> new UID (empty when the server sent none). */
    fun uidMove(uids: UidSet, dest: String): Map<Long, Long> {
        val moved = LinkedHashMap<Long, Long>()
        forChunks(uids) { chunk ->
            val command = Command.build { raw("UID MOVE"); raw(chunk.toString()); string(MailboxName.encode(dest)) }
            val ok = conn.execute(command) { r -> if (r.keyword == "OK") copyUid(r.code, chunk, moved) }
            copyUid(ok.code, chunk, moved)
        }
        return moved
    }

    fun uidExpunge(uids: UidSet): Unit = forChunks(uids) { conn.execute(Command.raw("UID EXPUNGE", it.toString())) }

    /** Returns the APPENDUID UID, or null. */
    fun append(mailbox: String, flags: List<String>, message: ByteArray): Long? {
        flags.forEach(::requireFlag)
        val command = Command.build {
            raw("APPEND")
            string(MailboxName.encode(mailbox))
            if (flags.isNotEmpty()) list { flags.forEach { raw(it) } }
            bytes(message)
        }
        val ok = conn.execute(command)
        return ok.code?.takeIf { it.name == "APPENDUID" }?.args?.getOrNull(1)?.longOrNull()
    }

    /** ALREADYEXISTS counts as success. */
    fun create(mailbox: String) {
        try {
            conn.execute(Command.build { raw("CREATE"); string(MailboxName.encode(mailbox)) })
        } catch (e: ImapNoException) {
            if (e.code?.name != "ALREADYEXISTS") throw e
        }
    }

    /** `GETQUOTAROOT`: the STORAGE resource of the first quota root, or null (also without QUOTA). */
    fun quota(mailbox: String = "INBOX"): Quota? {
        if (caps.none { it == "QUOTA" || it.startsWith("QUOTA=") }) return null
        var quota: Quota? = null
        conn.execute(Command.build { raw("GETQUOTAROOT"); string(MailboxName.encode(mailbox)) }) { r ->
            if (r.keyword == "QUOTA" && quota == null) quota = storage(r.values.getOrNull(1))
        }
        return quota
    }

    fun noop() {
        conn.execute(Command.raw("NOOP"))
    }

    /** One IDLE on the selected mailbox; see [ImapConnection.idle]. */
    fun idle(hooks: IdleHooks, onEvent: (IdleEvent) -> Unit): Unit = conn.idle(hooks, onEvent)

    /** Ends the running IDLE ([ImapConnection.done]); safe from another thread and never throws. */
    fun stopIdle(): Unit = conn.done()

    /** `LOGOUT` (BYE, then OK), then closes the connection. */
    fun logout() {
        try {
            conn.execute(Command.raw("LOGOUT"))
        } finally {
            close()
        }
    }

    override fun close() {
        current = null
        conn.close()
    }

    private fun authenticate(mechanism: String, initialResponse: String) {
        var capsSeen = false
        // A challenge after the initial response is an error report (XOAUTH2); an empty answer gets the NO.
        val ok = conn.execute(
            Command.raw("AUTHENTICATE", mechanism, initialResponse),
            onUntagged = { if (it.keyword == "CAPABILITY") capsSeen = true },
            onContinuation = { "" },
        )
        capsAfterAuth = capsSeen || ok.code?.name == "CAPABILITY"
        current = null
    }

    private fun open(verb: String, mailbox: String): SelectResult {
        current = null
        var exists: Long? = null
        var uidValidity: Long? = null
        var uidNext: Long? = null
        var highestModSeq: Long? = null
        var noModSeq = false
        conn.execute(Command.build { raw(verb); string(MailboxName.encode(mailbox)) }) { r ->
            if (r.keyword == "EXISTS") exists = r.number
            if (r.keyword == "OK") {
                val value = r.code?.args?.firstOrNull()?.longOrNull()
                when (r.code?.name) {
                    "UIDVALIDITY" -> uidValidity = value
                    "UIDNEXT" -> uidNext = value
                    "HIGHESTMODSEQ" -> highestModSeq = value
                    "NOMODSEQ" -> noModSeq = true
                }
            }
        }
        current = mailbox
        return SelectResult(
            exists = exists ?: throw ImapProtocolException("$verb without EXISTS"),
            uidValidity = uidValidity ?: throw ImapProtocolException("$verb without UIDVALIDITY"),
            uidNext = uidNext ?: throw ImapProtocolException("$verb without UIDNEXT"),
            highestModSeq = highestModSeq,
            noModSeq = noModSeq,
        )
    }

    private fun search(returns: String, criteria: Command, onResult: (Untagged) -> Unit) {
        val command = Command.build {
            raw("UID SEARCH RETURN ($returns)")
            if (criteria.hasNonAscii) raw("CHARSET UTF-8")
            append(criteria)
        }
        conn.execute(command) { r -> if (r.keyword == "ESEARCH" || r.keyword == "SEARCH") onResult(r) }
    }

    private fun fetchItems(command: Command, each: (FetchItem) -> Unit) {
        conn.execute(command) { r -> if (r.keyword == "FETCH") fetchItem(r)?.let(each) }
    }

    private fun store(uids: UidSet, item: String, values: Command.Builder.() -> Unit) = forChunks(uids) { chunk ->
        conn.execute(Command.build { raw("UID STORE"); raw(chunk.toString()); raw(item); list(values) })
    }

    private inline fun forChunks(uids: UidSet, block: (UidSet) -> Unit) {
        if (!uids.isEmpty) uids.chunks().forEach(block)
    }

    companion object {
        val REQUIRED = setOf("IDLE", "MOVE", "UIDPLUS", "ESEARCH")

        /** Opens the TLS socket, reads the greeting and returns a client ready for authentication. */
        fun connect(host: String, port: Int, hooks: SocketHooks, log: (String) -> Unit = {}): ImapClient {
            val socket = MailSocket.open(host, port, hooks)
            try {
                val conn = ImapConnection(socket, log)
                conn.greeting()
                return ImapClient(conn)
            } catch (e: Throwable) {
                socket.close()
                throw e
            }
        }

        private val INTERNAL_DATE =
            Regex("""(\d{1,2})-([A-Za-z]{3})-(\d{4}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})""")
        private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private const val ATOM_SPECIALS = "(){%*\"]\\"
        private const val NOSELECT = "\\Noselect"
        private const val NONEXISTENT = "\\NonExistent"

        /**
         * One LIST entry. `\NonExistent` implies `\Noselect` (RFC 5258 §3), but Dovecot sends only the former
         * to an extended LIST; any spelling of either adds `\Noselect`, so that callers check one name.
         */
        private fun mailboxInfo(values: List<ImapValue>): MailboxInfo? {
            val name = nameOf(values.getOrNull(2)?.textOrNull() ?: return null)
            var attributes = values[0].listOrEmpty().mapNotNull { it.textOrNull() }.toSet()
            if (attributes.any { it.equals(NONEXISTENT, true) || it.equals(NOSELECT, true) }) {
                attributes = attributes + NOSELECT
            }
            return MailboxInfo(name, values[1].textOrNull(), attributes)
        }

        /** `(NAME n NAME n ...)` as upper-case names to numbers. */
        private fun statusItems(value: ImapValue?): Map<String, Long> {
            val items = value?.listOrEmpty().orEmpty()
            val out = HashMap<String, Long>()
            for (i in 0 until items.size - 1 step 2) {
                val name = items[i].textOrNull() ?: continue
                items[i + 1].longOrNull()?.let { out[name.uppercase()] = it }
            }
            return out
        }

        /** INBOX in any case is INBOX; other names are decoded from modified UTF-7. */
        private fun nameOf(wire: String): String =
            if (wire.equals("INBOX", ignoreCase = true)) "INBOX" else MailboxName.decode(wire)

        /** Null when MESSAGES, UIDNEXT or UIDVALIDITY is missing. A HIGHESTMODSEQ of 0 means none. */
        private fun mailboxStatus(values: Map<String, Long>): MailboxStatus? = MailboxStatus(
            messages = values["MESSAGES"] ?: return null,
            uidNext = values["UIDNEXT"] ?: return null,
            uidValidity = values["UIDVALIDITY"] ?: return null,
            highestModSeq = values["HIGHESTMODSEQ"]?.takeIf { it > 0 },
        )

        /** `* ESEARCH [(TAG "A5")] [UID] NAME value ...` as upper-case names to value text. */
        private fun esearchItems(values: List<ImapValue>): Map<String, String> {
            val items = values.dropWhile { it is ImapValue.ListV || (it as? ImapValue.Atom)?.text.equals("UID", true) }
            val out = HashMap<String, String>()
            for (i in 0 until items.size - 1 step 2) {
                val name = items[i].textOrNull() ?: continue
                items[i + 1].textOrNull()?.let { out[name.uppercase()] = it }
            }
            return out
        }

        private fun uidSet(text: String): UidSet = try {
            UidSet.parse(text)
        } catch (_: IllegalArgumentException) {
            throw ImapProtocolException("Malformed UID set")
        }

        /**
         * Adds the pairs of a `[COPYUID validity src dst]` code. The two sets pair by position as written
         * (RFC 4315 §3), not in numeric order. Ignores sets that do not fit [chunk].
         */
        private fun copyUid(code: RespCode?, chunk: UidSet, into: MutableMap<Long, Long>) {
            if (code?.name != "COPYUID" || code.args.size < 3) return
            val src = orderedUids(code.args[1].textOrNull() ?: return, chunk.count) ?: return
            val dst = orderedUids(code.args[2].textOrNull() ?: return, chunk.count) ?: return
            if (src.size != dst.size || src.any { it !in chunk }) return
            src.zip(dst).toMap(into)
        }

        /**
         * A UID set in its written order; each `a:b` ascending (`4:2` = `2:4`). Null when it contains `*` or
         * a UID twice, or has more than [limit] UIDs.
         */
        private fun orderedUids(text: String, limit: Long): List<Long>? {
            val out = ArrayList<Long>()
            for (part in text.split(',')) {
                val ends = part.split(':').map { if (it == "*") return null else it.toLongOrNull() ?: 0L }
                if (ends.size > 2 || ends.any { it <= 0 }) throw ImapProtocolException("Malformed UID set")
                val from = ends.min()
                val to = ends.max()
                if (to - from >= limit - out.size) return null
                for (uid in from..to) out += uid
            }
            return out.takeIf { it.toSet().size == it.size }
        }

        private fun storage(resources: ImapValue?): Quota? {
            val items = resources?.listOrEmpty().orEmpty()
            for (i in 0 until items.size - 2 step 3) {
                if (!items[i].textOrNull().equals("STORAGE", ignoreCase = true)) continue
                val used = items[i + 1].longOrNull() ?: return null
                val limit = items[i + 2].longOrNull() ?: return null
                return Quota(used, limit)
            }
            return null
        }

        private fun withUid(items: String) =
            if (items.split(' ').any { it.equals("UID", ignoreCase = true) }) items else "UID $items".trimEnd()

        private fun requireFlag(flag: String): String {
            val valid = flag.isNotEmpty() && flag.withIndex().all { (i, c) ->
                c in '!'..'~' && (c !in ATOM_SPECIALS || (c == '\\' && i == 0))
            }
            require(valid) { "Invalid flag" }
            return flag
        }

        /** One `* n FETCH (...)` response; null for unsolicited ones without UID. */
        private fun fetchItem(r: Untagged): FetchItem? {
            val seq = r.number ?: return null
            val items = r.values.firstOrNull()?.listOrEmpty().orEmpty()
            var uid: Long? = null
            var flags: Set<String>? = null
            var modSeq: Long? = null
            var internalDate: Long? = null
            var size: Long? = null
            var gmMsgId: Long? = null
            var gmLabels: List<String>? = null
            var bodyStructure: ImapValue? = null
            val sections = LinkedHashMap<String, ByteArray>()
            for (i in 0 until items.size - 1 step 2) {
                val key = items[i].textOrNull() ?: continue
                val value = items[i + 1]
                when (val name = key.uppercase()) {
                    "UID" -> uid = value.longOrNull()
                    "FLAGS" -> flags = value.listOrEmpty().mapNotNull { it.textOrNull() }.toSet()
                    "MODSEQ" -> modSeq = value.listOrEmpty().firstOrNull()?.longOrNull()
                    "INTERNALDATE" -> internalDate = value.textOrNull()?.let(::internalDate)
                    "RFC822.SIZE" -> size = value.longOrNull()
                    // Unsigned 64-bit; kept as the same bits.
                    "X-GM-MSGID" -> gmMsgId = (value as? ImapValue.Atom)?.text?.toULongOrNull()?.toLong()
                    "X-GM-LABELS" -> gmLabels = value.listOrEmpty().mapNotNull { it.textOrNull()?.let(::label) }
                    "BODYSTRUCTURE" -> bodyStructure = value
                    "BODY" -> if (value is ImapValue.ListV) bodyStructure = value
                    else -> if (name.startsWith("BODY[") && value is ImapValue.Str) {
                        sections[sectionKey(key)] = value.bytes
                    }
                }
            }
            return FetchItem(
                seq, uid ?: return null, flags, modSeq, internalDate, size, gmMsgId, gmLabels, bodyStructure, sections,
            )
        }

        /** `BODY[1.2]<0>` -> `1.2<0>`, `BODY[HEADER.FIELDS (FROM)]` -> `HEADER.FIELDS (FROM)`. */
        private fun sectionKey(atom: String): String {
            val inner = atom.substringAfter('[')
            return inner.substringBeforeLast(']') + inner.substringAfterLast(']', "")
        }

        private fun label(wire: String) = if (wire.startsWith('\\')) wire else MailboxName.decode(wire)

        /** `dd-MMM-yyyy HH:mm:ss +zzzz` (day with one or two digits) to epoch milliseconds. */
        private fun internalDate(text: String): Long? {
            val g = INTERNAL_DATE.matchEntire(text.trim())?.groupValues ?: return null
            val month = MONTHS.indexOf(g[2].uppercase()) + 1
            if (month == 0) return null
            val sign = if (g[7] == "-") -1 else 1
            return try {
                val offset = ZoneOffset.ofHoursMinutes(sign * g[8].toInt(), sign * g[9].toInt())
                LocalDateTime.of(g[3].toInt(), month, g[1].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
                    .toInstant(offset)
                    .toEpochMilli()
            } catch (_: DateTimeException) {
                null
            }
        }
    }
}
