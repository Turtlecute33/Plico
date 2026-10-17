package app.plico.mail.imap

import app.plico.mail.net.Endpoint
import app.plico.mail.net.MailSocket
import app.plico.mail.net.MailStream
import app.plico.mail.net.PlaintextTimeoutException
import app.plico.mail.net.SocketHooks
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.TlsMode
import app.plico.mail.net.beforeTls
import app.plico.mail.sasl.Sasl
import java.io.Closeable
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.net.ssl.SSLSocketFactory

/**
 * Typed IMAP operations used by the app. See DESIGN.md §4.4.
 * Mailbox names and Gmail labels are given and returned decoded; this class encodes them to modified
 * UTF-7 for the wire, except on an IMAP4rev2-only server, where mailbox names are UTF-8 (RFC 9051 §5.1).
 * Commands that take a [UidSet] are split into chunks of at most 500 ranges.
 * [host] is the host of the endpoint ("" only in tests); credentials check it before they send a token.
 */
class ImapClient(val conn: ImapConnection, val host: String = "") : Closeable {

    /**
     * The effective capabilities: the advertised ones, plus the RFC 9051 base set for a server that advertises
     * IMAP4rev2 without IMAP4rev1 (such a server need not list the base extensions).
     */
    val caps: Set<String>
        get() = if (rev2Only) conn.capabilities + REV2_BASE else conn.capabilities

    /**
     * IMAP4rev2 without IMAP4rev1. Such a server uses UTF-8 mailbox names (RFC 9051 §5.1) and need not know
     * modified UTF-7; a server with both stays in IMAP4rev1 mode, because Plico never enables IMAP4rev2.
     */
    private val rev2Only: Boolean
        get() = conn.capabilities.let { "IMAP4REV2" in it && "IMAP4REV1" !in it }

    val isGmail: Boolean get() = "X-GM-EXT-1" in caps

    /** True when the server sent its capabilities after authentication. */
    private var capsAfterAuth = false
    private var current: String? = null

    /** The [namespace] answer of this connection, once asked. */
    private var namespaceAsked = false
    private var namespaces: Namespaces? = null

    /**
     * Sign-in with a password or an app password, only over TLS. The mechanism comes from the capabilities
     * (asked first when the greeting had none): `AUTHENTICATE PLAIN` with SASL-IR, `AUTHENTICATE PLAIN` with a
     * continuation, else `LOGIN`. LOGINDISABLED without AUTH=PLAIN -> [ImapMissingCapabilityException] before
     * the password is sent.
     */
    fun authenticatePassword(user: String, password: String) {
        check(conn.encrypted) { "Password sign-in before TLS" }
        if (caps.isEmpty()) conn.execute(Command.raw("CAPABILITY"))
        when (passwordAuth(caps)) {
            PasswordAuth.PLAIN_IR -> authenticatePlain(user, password, inline = true)
            PasswordAuth.PLAIN -> authenticatePlain(user, password, inline = false)
            PasswordAuth.LOGIN -> login(user, password)
        }
    }

    /**
     * XOAUTH2, only over TLS. The initial response goes inline when the server has SASL-IR or its capabilities
     * are not known yet (the Gmail greeting has none); else it answers the first continuation. A failed token
     * gets an error challenge, which gets an empty answer, then NO.
     */
    fun authenticateXoauth2(user: String, accessToken: String) {
        check(conn.encrypted) { "Token sign-in before TLS" }
        val inline = caps.isEmpty() || "SASL-IR" in caps
        authenticate("XOAUTH2", Sasl.xoauth2(user, accessToken), inline, cancel = "")
    }

    /**
     * Sends CAPABILITY when the login did not report it. Throws [ImapMissingCapabilityException] when the server
     * is neither IMAP4rev1 nor IMAP4rev2; every extension is optional.
     */
    fun requireCapabilities() {
        if (!capsAfterAuth) {
            conn.execute(Command.raw("CAPABILITY"))
            capsAfterAuth = true
        }
        if ("IMAP4REV1" !in caps && "IMAP4REV2" !in caps) throw ImapMissingCapabilityException(setOf("IMAP4REV1"))
    }

    /** `ENABLE`; returns the upper-case extensions the server reports as enabled (none without ENABLE). */
    fun enable(vararg ext: String): Set<String> {
        if (ext.isEmpty() || "ENABLE" !in caps) return emptySet()
        val enabled = HashSet<String>()
        conn.execute(Command.raw("ENABLE", *ext)) { r ->
            if (r.keyword == "ENABLED") r.values.mapNotNullTo(enabled) { it.textOrNull()?.uppercase() }
        }
        return enabled
    }

    /**
     * RFC 2342 namespaces, asked once per connection; null when the server has no NAMESPACE, refuses the command
     * or sends no NAMESPACE response. Prefixes are decoded like mailbox names.
     */
    fun namespace(): Namespaces? {
        if ("NAMESPACE" !in caps) return null
        if (namespaceAsked) return namespaces
        try {
            conn.execute(Command.raw("NAMESPACE")) { r -> if (r.keyword == "NAMESPACE") namespaces = namespacesOf(r.values) }
        } catch (_: ImapNoException) {
        } catch (_: ImapBadException) {
        }
        namespaceAsked = true
        return namespaces
    }

    /**
     * RFC 2971 `ID` with [fields]. The reply is ignored, also a NO or BAD. Only for servers that refuse mailboxes
     * without it.
     */
    fun id(fields: List<Pair<String, String>>) {
        try {
            conn.execute(Command.build { raw("ID"); list { fields.forEach { (key, value) -> string(key); string(value) } } })
        } catch (_: ImapNoException) {
        } catch (_: ImapBadException) {
        }
    }

    /** Starts COMPRESS=DEFLATE when the server advertises it. */
    fun compress() {
        if ("COMPRESS=DEFLATE" in caps) conn.startCompression()
    }

    /**
     * `LIST "" "*" RETURN (SPECIAL-USE)`; a plain LIST unless both SPECIAL-USE and LIST-EXTENDED are advertised
     * (RETURN options need LIST-EXTENDED; a plain LIST still carries the special-use attributes).
     */
    fun list(): List<MailboxInfo> {
        val command = Command.build {
            raw("LIST")
            string("")
            string("*")
            if ("SPECIAL-USE" in caps && "LIST-EXTENDED" in caps) raw("RETURN (SPECIAL-USE)")
        }
        val out = ArrayList<MailboxInfo>()
        conn.execute(command) { r -> if (r.keyword == "LIST") mailboxInfo(r.values)?.let(out::add) }
        return out
    }

    /** `STATUS` with MESSAGES UIDNEXT UIDVALIDITY, plus HIGHESTMODSEQ when CONDSTORE is advertised. */
    fun status(mailbox: String): MailboxStatus {
        var values = emptyMap<String, Long>()
        conn.execute(Command.build { raw("STATUS"); string(wire(mailbox)); raw("($statusQuery)") }) { r ->
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

    /**
     * `UID SEARCH RETURN (ALL) [CHARSET UTF-8] <criteria>`; without ESEARCH `UID SEARCH [CHARSET UTF-8]
     * <criteria>`. The result is the union of every `* SEARCH` / `* ESEARCH` line.
     */
    fun uidSearch(criteria: Command): UidSet {
        var result = UidSet.EMPTY
        search("ALL", criteria) { r ->
            val found = if (r.keyword == "ESEARCH") {
                esearchItems(r.values)["ALL"]?.let(::uidSet) ?: UidSet.EMPTY
            } else {
                UidSet.of(r.values.mapNotNull { it.longOrNull() })
            }
            result = result.union(found)
        }
        return result
    }

    /**
     * `UID SEARCH RETURN (COUNT) [CHARSET UTF-8] <criteria>`. Without ESEARCH the server sends every matching UID
     * and the count is their number, summed over the lines.
     */
    fun uidSearchCount(criteria: Command): Long {
        var count = 0L
        search("COUNT", criteria) { r ->
            count += if (r.keyword == "ESEARCH") {
                val items = esearchItems(r.values)
                items["COUNT"]?.toLongOrNull() ?: items["ALL"]?.let(::uidSet)?.count ?: 0
            } else {
                r.values.count { it.longOrNull() != null }.toLong()
            }
        }
        return count
    }

    /**
     * `UID SEARCH RETURN (MAX) [CHARSET UTF-8] <criteria>`: the highest matching UID, or null when nothing
     * matches. One short line however many UIDs match (the backfill step, DESIGN.md §7.2). Without ESEARCH the
     * server sends every matching UID; callers keep such searches in UID windows.
     */
    fun uidSearchMax(criteria: Command): Long? {
        var max: Long? = null
        search("MAX", criteria) { r ->
            val found = if (r.keyword == "ESEARCH") {
                val items = esearchItems(r.values)
                items["MAX"]?.let { it.toLongOrNull() ?: throw ImapProtocolException("Malformed MAX") }
                    ?: items["ALL"]?.let(::uidSet)?.max
            } else {
                r.values.mapNotNull { it.longOrNull() }.maxOrNull()
            }
            if (found != null) max = maxOf(max ?: found, found)
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

    /**
     * `UID MOVE`. Returns the COPYUID mapping old UID -> new UID (empty when the server sent none).
     * Without MOVE: per chunk `UID COPY` and `UID STORE +FLAGS.SILENT (\Deleted)`, then [expunge] with all of
     * [uids] (by default [uidExpunge] with the guard). Not atomic: a retry after a lost connection can copy twice,
     * and a skipped guarded expunge leaves the originals with `\Deleted` on the server.
     */
    fun uidMove(uids: UidSet, dest: String, expunge: (UidSet) -> Unit = { uidExpunge(it) }): Map<Long, Long> {
        val moved = LinkedHashMap<Long, Long>()
        val move = "MOVE" in caps
        forChunks(uids) { chunk ->
            val command = Command.build {
                raw(if (move) "UID MOVE" else "UID COPY")
                raw(chunk.toString())
                string(wire(dest))
            }
            val ok = conn.execute(command) { r -> if (r.keyword == "OK") copyUid(r.code, chunk, moved) }
            copyUid(ok.code, chunk, moved)
            if (!move) store(chunk, "+FLAGS.SILENT") { raw(DELETED) }
        }
        if (!move) expunge(uids)
        return moved
    }

    /**
     * Removes [uids] (already flagged `\Deleted`) from the selected mailbox. With UIDPLUS: `UID EXPUNGE` per
     * chunk. Without UIDPLUS only a plain `EXPUNGE` exists, which also removes every other `\Deleted` message;
     * so with [guard] it first runs `UID SEARCH DELETED` and skips the EXPUNGE when a hit is in neither [uids] nor
     * [allowed] (another client's struck-through mail). [allowed] holds the caller's own `\Deleted` messages that an
     * earlier skipped expunge left; the plain EXPUNGE removes them too. Returns false when the guard skipped the
     * expunge. [guard] = false only for Empty Trash/Spam and permanent deletes inside Trash/Spam.
     */
    fun uidExpunge(uids: UidSet, guard: Boolean = true, allowed: UidSet = UidSet.EMPTY): Boolean {
        if (uids.isEmpty) return true
        if ("UIDPLUS" in caps) {
            forChunks(uids) { conn.execute(Command.raw("UID EXPUNGE", it.toString())) }
            return true
        }
        if (guard) {
            val own = uids.union(allowed)
            if (uidSearch(Command.raw("DELETED")).ranges.any { r -> !own.containsAll(r) }) return false
        }
        conn.execute(Command.raw("EXPUNGE"))
        return true
    }

    /** Returns the APPENDUID UID, or null. */
    fun append(mailbox: String, flags: List<String>, message: ByteArray): Long? {
        flags.forEach(::requireFlag)
        val command = Command.build {
            raw("APPEND")
            string(wire(mailbox))
            if (flags.isNotEmpty()) list { flags.forEach { raw(it) } }
            bytes(message)
        }
        val ok = conn.execute(command)
        return ok.code?.takeIf { it.name == "APPENDUID" }?.args?.getOrNull(1)?.longOrNull()
    }

    /** ALREADYEXISTS counts as success. */
    fun create(mailbox: String) {
        try {
            conn.execute(Command.build { raw("CREATE"); string(wire(mailbox)) })
        } catch (e: ImapNoException) {
            if (e.code?.name != "ALREADYEXISTS") throw e
        }
    }

    /** `GETQUOTAROOT`: the STORAGE resource of the first quota root, or null (also without QUOTA). */
    fun quota(mailbox: String = "INBOX"): Quota? {
        if (caps.none { it == "QUOTA" || it.startsWith("QUOTA=") }) return null
        var quota: Quota? = null
        conn.execute(Command.build { raw("GETQUOTAROOT"); string(wire(mailbox)) }) { r ->
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

    /** SASL PLAIN; a challenge after the initial response gets the cancel `*`. */
    private fun authenticatePlain(user: String, password: String, inline: Boolean) =
        authenticate("PLAIN", Sasl.plain(user, password), inline, cancel = "*")

    /**
     * `AUTHENTICATE`. The initial response goes [inline] (SASL-IR) or answers the first continuation; every
     * later continuation is an error report that gets [cancel] (`""` for XOAUTH2, `*` for PLAIN), then NO.
     */
    private fun authenticate(mechanism: String, initialResponse: String, inline: Boolean, cancel: String) {
        var responded = inline
        val command = if (inline) {
            Command.raw("AUTHENTICATE", mechanism, initialResponse)
        } else {
            Command.raw("AUTHENTICATE", mechanism)
        }
        signIn(command) {
            if (responded) {
                cancel
            } else {
                responded = true
                initialResponse
            }
        }
    }

    /** `LOGIN user password` as strings (quoted, or literals for other characters). Logged as `LOGIN` only. */
    private fun login(user: String, password: String) =
        signIn(Command.build { raw("LOGIN"); string(user); string(password) }) { null }

    private fun signIn(command: Command, onContinuation: (String) -> String?) {
        var capsSeen = false
        val ok = conn.execute(
            command,
            onUntagged = { if (it.keyword == "CAPABILITY") capsSeen = true },
            onContinuation = onContinuation,
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
        conn.execute(Command.build { raw(verb); string(wire(mailbox)) }) { r ->
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
        val count = exists ?: throw ImapProtocolException("$verb without EXISTS")
        val validity = uidValidity ?: throw ImapProtocolException("$verb without UIDVALIDITY")
        return SelectResult(
            exists = count,
            uidValidity = validity,
            uidNext = uidNext ?: estimateUidNext(count),
            highestModSeq = highestModSeq,
            noModSeq = noModSeq,
        )
    }

    /**
     * For a server that sent no UIDNEXT: the UID of the last message + 1 (`FETCH * (UID)`), or 1 for an empty
     * mailbox. A lower bound, because a deleted last message can have had a higher UID.
     */
    private fun estimateUidNext(exists: Long): Long {
        if (exists == 0L) return 1
        var max = 0L
        fetch("*", "UID") { max = maxOf(max, it.uid) }
        return max + 1
    }

    private fun search(returns: String, criteria: Command, onResult: (Untagged) -> Unit) {
        val command = Command.build {
            raw(if ("ESEARCH" in caps) "UID SEARCH RETURN ($returns)" else "UID SEARCH")
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

    /** [name] as the server reads it: modified UTF-7, or UTF-8 on an IMAP4rev2-only server. */
    private fun wire(name: String): String = if (rev2Only) name else MailboxName.encode(name)

    /** INBOX in any case is INBOX; other names are decoded from modified UTF-7 (UTF-8 on IMAP4rev2-only servers). */
    private fun nameOf(wire: String): String = when {
        wire.equals("INBOX", ignoreCase = true) -> "INBOX"
        rev2Only -> wire
        else -> MailboxName.decode(wire)
    }

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

    /** `* NAMESPACE personal other shared`: each a list of `(prefix delimiter ...)` or NIL. */
    private fun namespacesOf(values: List<ImapValue>): Namespaces {
        fun group(value: ImapValue?): List<Namespace> = value?.listOrEmpty().orEmpty().mapNotNull { entry ->
            val items = entry.listOrEmpty()
            val prefix = items.getOrNull(0)?.textOrNull() ?: return@mapNotNull null
            Namespace(if (rev2Only) prefix else MailboxName.decode(prefix), items.getOrNull(1)?.textOrNull())
        }
        return Namespaces(group(values.getOrNull(0)), group(values.getOrNull(1)), group(values.getOrNull(2)))
    }

    /** How [authenticatePassword] signs in. */
    internal enum class PasswordAuth { PLAIN_IR, PLAIN, LOGIN }

    companion object {
        /**
         * RFC 9051 §7.2.2: the extensions that an IMAP4rev2 server has without listing them (`Wire` still uses
         * only the advertised set for literals).
         */
        private val REV2_BASE = setOf(
            "IDLE", "MOVE", "UIDPLUS", "ESEARCH", "NAMESPACE", "ENABLE", "SPECIAL-USE", "LIST-EXTENDED",
            "LIST-STATUS", "SASL-IR", "UNSELECT",
        )
        private const val DELETED = "\\Deleted"

        /**
         * Opens the connection, reads the greeting and, for [TlsMode.STARTTLS], upgrades to TLS. The returned
         * client is always encrypted and ready for authentication.
         */
        fun connect(endpoint: Endpoint, hooks: SocketHooks, log: (String) -> Unit = {}): ImapClient =
            connect(endpoint, hooks, log, SSLSocketFactory.getDefault() as SSLSocketFactory)

        internal fun connect(
            endpoint: Endpoint,
            hooks: SocketHooks,
            log: (String) -> Unit,
            factory: SSLSocketFactory,
        ): ImapClient {
            val socket = MailSocket.open(endpoint.host, endpoint.port, hooks, endpoint.tls, factory = factory)
            return start(socket, endpoint, log)
        }

        /**
         * The greeting, then for [TlsMode.STARTTLS]: STARTTLS is always sent (capabilities before TLS are not
         * trusted, so neither a missing STARTTLS nor LOGINDISABLED changes anything); PREAUTH, NO or BAD ->
         * [StartTlsException]; no greeting or STARTTLS reply in time -> [PlaintextTimeoutException]; after the switch
         * CAPABILITY is asked again. Any failure closes [stream].
         */
        internal fun start(stream: MailStream, endpoint: Endpoint, log: (String) -> Unit = {}): ImapClient {
            try {
                val conn = ImapConnection(stream, log)
                val starttls = endpoint.tls == TlsMode.STARTTLS
                val greeting = if (starttls) beforeTls { conn.greeting() } else conn.greeting()
                if (starttls) {
                    // PREAUTH before TLS would skip the upgrade: a STARTTLS stripping attack.
                    if (greeting.keyword == "PREAUTH") throw StartTlsException("PREAUTH before STARTTLS")
                    try {
                        conn.startTls()
                    } catch (_: ImapNoException) {
                        throw StartTlsException("STARTTLS refused")
                    } catch (_: ImapBadException) {
                        throw StartTlsException("STARTTLS refused")
                    }
                    conn.execute(Command.raw("CAPABILITY"))
                }
                check(conn.encrypted) { "No TLS" }
                return ImapClient(conn, endpoint.host)
            } catch (e: Throwable) {
                stream.close()
                throw e
            }
        }

        /** Pure: the password mechanism for [caps]. */
        internal fun passwordAuth(caps: Set<String>): PasswordAuth = when {
            "AUTH=PLAIN" in caps && "SASL-IR" in caps -> PasswordAuth.PLAIN_IR
            "AUTH=PLAIN" in caps -> PasswordAuth.PLAIN
            "LOGINDISABLED" !in caps -> PasswordAuth.LOGIN
            else -> throw ImapMissingCapabilityException(setOf("AUTH=PLAIN"))
        }

        private val INTERNAL_DATE =
            Regex("""(\d{1,2})-([A-Za-z]{3})-(\d{4}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})""")
        private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private const val ATOM_SPECIALS = "(){%*\"]\\"
        private const val NOSELECT = "\\Noselect"
        private const val NONEXISTENT = "\\NonExistent"

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
