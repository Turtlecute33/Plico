package app.plico.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DbTest : DbFixture() {

    @Test
    fun createsTheCurrentSchema() {
        val names = db.sql.list("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") { it.str(0) }.toSet()
        val expected = setOf(
            "account", "identity", "folder", "message", "message_folder", "body", "attachment", "message_fts",
            "op", "draft", "contact", "trusted_sender", "message_account_date", "message_gm", "message_mid",
            "message_folder_message", "message_ad",
        )
        assertTrue(names.containsAll(expected), "missing: ${expected - names}")
        assertEquals(3, db.sql.version)
        // A fresh database also runs the migration statements.
        val accountColumns = db.sql.list("PRAGMA table_info(account)") { it.str(1) }
        val v2 = listOf("imap_host", "imap_port", "imap_tls", "smtp_host", "smtp_port", "smtp_tls", "login", "idle")
        assertEquals(v2, accountColumns.takeLast(v2.size))
        assertEquals("deleted", db.sql.list("PRAGMA table_info(message)") { it.str(1) }.last())
        assertEquals("own_deleted", db.sql.list("PRAGMA table_info(folder)") { it.str(1) }.last())
        val fts = db.sql.one("SELECT sql FROM sqlite_master WHERE name = 'message_fts'") { it.str(0) }!!
        assertTrue("fts4" in fts && "unicode61" in fts)
        val trigger = db.sql.one("SELECT type FROM sqlite_master WHERE name = 'message_ad'") { it.str(0) }
        assertEquals("trigger", trigger)
    }

    @Test
    fun usesWalAndForeignKeys() {
        assertEquals("wal", db.sql.one("PRAGMA journal_mode") { it.str(0) }?.lowercase())
        assertEquals(1L, db.sql.long("PRAGMA foreign_keys"))
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            db.tx(Tables.FOLDER) { write("INSERT INTO folder(account_id, path, name) VALUES(999, 'x', 'x')") }
        }
    }

    @Test
    fun autoincrementNeverReusesIds() {
        val acc = account()
        val inbox = mailbox(acc, "INBOX", FolderRole.INBOX)
        val (first, newest) = insert(header(acc, inbox, 1), header(acc, inbox, 2))
        messages.delete(listOf(newest))
        val next = insert(header(acc, inbox, 3)).single()
        assertTrue(next > newest, "id $next reused")
        assertTrue(first < newest)

        accounts.delete(acc)
        val again = account()
        assertTrue(again > acc, "account id reused")
    }

    @Test
    fun ftsTriggerFiresOnDeleteAndCascade() {
        val acc = account()
        val inbox = mailbox(acc, "INBOX", FolderRole.INBOX)
        val (a, b, c) = insert(header(acc, inbox, 1), header(acc, inbox, 2), header(acc, inbox, 3))
        messages.saveBody(a, null, "secret text", "secret text", "secret", emptyList())
        assertEquals(3, count("SELECT count(*) FROM message_fts"))

        messages.delete(listOf(b))
        assertEquals(2, count("SELECT count(*) FROM message_fts"))
        assertEquals(0, count("SELECT count(*) FROM message_fts WHERE rowid = ?", b))

        // Folder cascade, then account cascade.
        folders.delete(inbox)
        assertEquals(0, count("SELECT count(*) FROM message_fts"))
        val inbox2 = mailbox(acc, "INBOX", FolderRole.INBOX)
        insert(header(acc, inbox2, 4))
        accounts.delete(acc)
        assertEquals(0, count("SELECT count(*) FROM message_fts"))
        assertEquals(0, count("SELECT count(*) FROM message_fts WHERE message_fts MATCH 'secret'"))
        assertTrue(c > 0)
    }

    @Test
    fun nestedTxJoinsAndEmitsOnceAfterOutermostCommit(): Unit = runBlocking {
        val events = collectChanges()
        db.tx(Tables.CONTACT) {
            write("INSERT INTO contact(email) VALUES('a@b.c')")
            db.tx(Tables.TRUSTED_SENDER) {
                write("INSERT INTO trusted_sender(email) VALUES('a@b.c')")
            }
            // Another thread reads through another connection: the joined inner write is not committed yet.
            val outside = runBlocking(Dispatchers.IO) { db.sql.long("SELECT count(*) FROM trusted_sender") }
            assertEquals(0L, outside)
        }
        db.tx(Tables.OP) { }
        assertEquals(listOf(setOf(Tables.CONTACT, Tables.TRUSTED_SENDER), setOf(Tables.OP)), events.until(Tables.OP))
    }

    @Test
    fun failedTxRollsBackAndEmitsNothing(): Unit = runBlocking {
        val events = collectChanges()
        assertFailsWith<IllegalStateException> {
            db.tx(Tables.CONTACT) {
                write("INSERT INTO contact(email) VALUES('a@b.c')")
                db.tx(Tables.TRUSTED_SENDER) { error("boom") }
            }
        }
        assertEquals(0, count("SELECT count(*) FROM contact"))
        db.tx(Tables.OP) { }
        assertEquals(listOf(setOf(Tables.OP)), events.until(Tables.OP))
    }

    @Test
    fun observeEmitsInitialReadAndAfterEachMatchingTx(): Unit = runBlocking {
        val values = Channel<Long>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            db.observe(Tables.CONTACT) { long("SELECT count(*) FROM contact")!! }.collect { values.send(it) }
        }
        // The initial read runs only once the subscription is active.
        withTimeout(5_000) { assertEquals(0L, values.receive()) }
        // An unrelated table does not trigger a read.
        db.tx(Tables.TRUSTED_SENDER) { write("INSERT INTO trusted_sender(email) VALUES('x@y.z')") }
        db.tx(Tables.CONTACT) { write("INSERT INTO contact(email) VALUES('a@b.c')") }
        withTimeout(5_000) { assertEquals(1L, values.receive()) }
        db.tx(Tables.CONTACT) { write("INSERT INTO contact(email) VALUES('d@e.f')") }
        withTimeout(5_000) { assertEquals(2L, values.receive()) }
        delay(300)
        assertTrue(values.tryReceive().isFailure, "unexpected extra reads")
        job.cancel()
    }

    @Test
    fun observeNeverMissesACommitMadeWhileSubscribing(): Unit = runBlocking {
        repeat(20) { i ->
            val latest = async(Dispatchers.IO) {
                withTimeout(5_000) {
                    db.observe(Tables.CONTACT) { long("SELECT count(*) FROM contact")!! }.first { it == i + 1L }
                }
            }
            db.tx(Tables.CONTACT) { write("INSERT INTO contact(email) VALUES(?)", "u$i@x.y") }
            assertEquals(i + 1L, latest.await())
        }
    }

    @Test
    fun observeIsConflatedForSlowCollectors(): Unit = runBlocking {
        val seen = mutableListOf<Long>()
        val done = async(Dispatchers.IO) {
            withTimeout(10_000) {
                db.observe(Tables.CONTACT) { long("SELECT count(*) FROM contact")!! }
                    .onEach { delay(100) }
                    .first { seen += it; it == 50L }
            }
        }
        repeat(50) { i -> db.tx(Tables.CONTACT) { write("INSERT INTO contact(email) VALUES(?)", "u$i@x.y") } }
        assertEquals(50L, done.await())
        assertTrue(seen.size < 50, "not conflated: ${seen.size} reads")
    }

    /** Collects [Db.changes] from now on; [Changes.until] waits for a set containing a sentinel table. */
    private suspend fun CoroutineScope.collectChanges(): Changes {
        val subscribed = CompletableDeferred<Unit>()
        val channel = Channel<Set<String>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            db.changes.onSubscription { subscribed.complete(Unit) }.collect { channel.send(it) }
        }
        subscribed.await()
        return Changes(channel, job)
    }

    private class Changes(val channel: Channel<Set<String>>, val job: Job) {
        suspend fun until(sentinel: String): List<Set<String>> = withTimeout(5_000) {
            val all = mutableListOf<Set<String>>()
            do {
                all += channel.receive()
            } while (sentinel !in all.last())
            job.cancel()
            all
        }
    }
}
