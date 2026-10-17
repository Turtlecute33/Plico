package app.plico.sync

import android.content.res.Resources
import app.plico.data.Account
import app.plico.data.AccountDao
import app.plico.data.FolderDao
import app.plico.data.MessageDao
import app.plico.data.OpDao
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.SelectResult
import app.plico.mail.imap.Untagged
import app.plico.push.policy.KeyValueStore
import java.util.concurrent.ConcurrentHashMap

/**
 * What mailbox sync works with on one connection: the client, the account's rows and a few callbacks.
 * AccountSession builds it from the PlicoApp singletons; tests build it over a private database.
 */
internal class SyncEnv(
    val account: Account,
    val client: ImapClient,
    val accounts: AccountDao,
    val folders: FolderDao,
    val messages: MessageDao,
    val ops: OpDao,
    val resources: Resources,
    /** Small persistent markers (Prefs), e.g. the one-time Gmail size-limit note. */
    val notes: KeyValueStore,
    /** Message ids that pending ops reference (Ops.protectedIds); sync leaves their local state alone. */
    val protectedIds: () -> Set<Long>,
    /** Folder id -> server message count seen at its last sync, kept for the life of the session. */
    val knownMessages: MutableMap<Long, Long> = ConcurrentHashMap(),
    /** Renews the wake lock; called after every batch. */
    val progress: () -> Unit = {},
    /**
     * The server announced new mail outside a SELECT, or IDLE could miss mail: the loop runs the fast path
     * again before IDLE.
     */
    val onNewMail: () -> Unit = {},
    /** Wall-clock time. */
    val clock: () -> Long = System::currentTimeMillis,
    /** Messages that a MOVE without COPYUID took into a mailbox on this connection (shared with the ops). */
    val movedIn: MovedIn = MovedIn(),
) {
    val accountId: Long get() = account.id
    val gmail: Boolean get() = account.isGmail
    val condstore: Boolean get() = "CONDSTORE" in client.caps
    val esearch: Boolean get() = "ESEARCH" in client.caps
    val selection = SyncSelection(client, onNewMail)

    fun text(id: Int): String = resources.getString(id)
}

/**
 * Tracks the selected mailbox and its live message count. Every SELECT of the sync goes through here, so that
 * EXISTS responses from a SELECT are not mistaken for new mail.
 */
internal class SyncSelection(private val client: ImapClient, private val onNewMail: () -> Unit) {
    private var path: String? = null
    private var result: SelectResult? = null
    private var readOnly = false
    private var opening = false

    init {
        client.conn.onUnsolicited = ::unsolicited
    }

    /** The mailbox selected now (read-write), or null. */
    val selected: String? get() = path?.takeIf { !readOnly && client.selected == it }

    /** SELECTs [path] again, even when it is selected, for a fresh UIDNEXT. */
    fun select(path: String): SelectResult = open(path, readOnly = false)

    fun examine(path: String): SelectResult = open(path, readOnly = true)

    /** The live SELECT result of [path] when it is still selected, else null. */
    fun current(path: String): SelectResult? = result?.takeIf { selected == path }

    /** [current], or a SELECT. */
    fun ensure(path: String): SelectResult = current(path) ?: select(path)

    private fun open(path: String, readOnly: Boolean): SelectResult {
        this.path = null
        result = null
        opening = true
        try {
            val r = if (readOnly) client.examine(path) else client.select(path)
            this.path = path
            this.readOnly = readOnly
            result = r
            return r
        } finally {
            opening = false
        }
    }

    private fun unsolicited(r: Untagged) {
        if (opening) return
        // A mailbox that was selected without this class (client.select) is unknown here.
        val current = result?.takeIf { client.selected == path } ?: return
        when (r.keyword) {
            "EXISTS" -> {
                val exists = r.number ?: return
                result = current.copy(exists = exists)
                if (exists > current.exists) onNewMail()
            }
            "EXPUNGE" -> result = current.copy(exists = maxOf(0, current.exists - 1))
        }
    }
}

/**
 * Message-IDs of rows that a MOVE without COPYUID took into a mailbox, by the decoded target path. The new-UID fetch
 * of that mailbox stores such a row as notified, so that "Not spam" or a restore into INBOX never notifies
 * (DESIGN §7.4). Kept for one connection; thread-safe.
 */
internal class MovedIn {
    private val byPath = HashMap<String, MutableSet<String>>()

    @Synchronized
    fun add(path: String, messageIds: Collection<String>) {
        if (messageIds.isNotEmpty()) byPath.getOrPut(path) { HashSet() } += messageIds
    }

    /** True once for each [messageId] that [add] recorded for [path]. */
    @Synchronized
    fun take(path: String, messageId: String): Boolean {
        val ids = byPath[path] ?: return false
        val found = ids.remove(messageId)
        if (ids.isEmpty()) byPath.remove(path)
        return found
    }

    @Synchronized
    fun clear() = byPath.clear()
}

/** What a cycle changed; the session turns it into notifications and the next steps. */
internal class SyncReport {
    /** Folders that got new rows (their bodies are fetched next). */
    val newRows = LinkedHashSet<Long>()

    /** Gmail: new rows with the `\Inbox` label (the UIDNEXT check). */
    var newInbox = 0

    /** Rows became seen, lost a label or were deleted: shown notifications need a refresh. */
    var changed = false

    /** The folder list is out of date: a label without a view row, or a mailbox that is gone (LIST again). */
    var relist = false
}
