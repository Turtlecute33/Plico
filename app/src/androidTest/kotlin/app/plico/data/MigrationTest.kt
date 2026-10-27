package app.plico.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.plico.account.Providers
import app.plico.mail.imap.UidSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An installed v1 database (release 0.1.0), v2, v4 or v5 database opened by this build. */
class MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val v1Name = "test-v1-${UUID.randomUUID()}.db"
    private val v2Name = "test-v2-${UUID.randomUUID()}.db"
    private val v4Name = "test-v4-${UUID.randomUUID()}.db"
    private val v5Name = "test-v5-${UUID.randomUUID()}.db"
    private val freshName = "test-fresh-${UUID.randomUUID()}.db"
    private val opened = ArrayList<Db>()

    @After
    fun deleteDbs() {
        opened.forEach(Db::close)
        context.deleteDatabase(v1Name)
        context.deleteDatabase(v2Name)
        context.deleteDatabase(v4Name)
        context.deleteDatabase(v5Name)
        context.deleteDatabase(freshName)
    }

    @Test
    fun upgradeKeepsRowsAndFillsInTheServers() {
        val path = context.getDatabasePath(v1Name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v1 ->
            Schema.V1.forEach(v1::execSQL)
            v1.execSQL(
                "INSERT INTO account(id, provider, email, label, auth, secret) " +
                    "VALUES(1, 'riseup', 'a@riseup.net', 'Riseup', 'password', x'01')",
            )
            v1.execSQL(
                "INSERT INTO account(id, provider, email, label, auth, device_account) " +
                    "VALUES(2, 'gmail', 'b@gmail.com', 'Gmail', 'device', 'b@gmail.com')",
            )
            v1.execSQL("INSERT INTO folder(id, account_id, path, name, role) VALUES(1, 1, 'INBOX', 'INBOX', 'inbox')")
            v1.execSQL("INSERT INTO message(id, account_id, folder_id, uid, subject, date) VALUES(1, 1, 1, 7, 'hello', 1000)")
            v1.execSQL("INSERT INTO message_folder(folder_id, message_id) VALUES(1, 1)")
            v1.execSQL("INSERT INTO message_fts(rowid, subject, people, body) VALUES(1, 'hello', '', '')")
            v1.version = 1
        }

        val db = Db(context, v1Name).also(opened::add)
        assertEquals(Schema.VERSION, db.sql.version)
        val accounts = AccountDao(db).all()
        assertEquals(listOf("a@riseup.net", "b@gmail.com"), accounts.map { it.email })
        assertEquals(listOf(Provider.RISEUP, Provider.GMAIL), accounts.map { it.provider })
        assertEquals(listOf(Providers.RISEUP_SERVERS, Providers.GMAIL_SERVERS), accounts.map { it.servers })
        assertTrue(accounts.all { it.idle })
        assertEquals("b@gmail.com", accounts[1].deviceAccount)
        assertEquals("hello", MessageDao(db).get(1)?.subject)
        assertEquals(0L, db.sql.long("SELECT deleted FROM message WHERE id = 1"))
        assertEquals(emptyList(), db.sql.list("PRAGMA foreign_key_check") { it.str(0) })

        // The FTS trigger still runs.
        MessageDao(db).delete(listOf(1L))
        assertEquals(0L, db.sql.long("SELECT count(*) FROM message_fts"))

        // An upgraded database has the same columns as a fresh one.
        val fresh = Db(context, freshName).also(opened::add)
        for (table in listOf("account", "folder", "message")) assertEquals(columns(fresh, table), columns(db, table), table)
    }

    @Test
    fun upgradeFromV2AddsTheOwnDeletedRecord() {
        val path = context.getDatabasePath(v2Name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v2 ->
            (Schema.V1 + Schema.MIGRATIONS[0]).forEach(v2::execSQL)
            v2.execSQL(
                "INSERT INTO account(id, provider, email, label, auth, secret) " +
                    "VALUES(1, 'other', 'a@example.org', 'Mail', 'password', x'01')",
            )
            v2.execSQL("INSERT INTO folder(id, account_id, path, name, role, uidvalidity) VALUES(1, 1, 'INBOX', 'INBOX', 'inbox', 7)")
            v2.version = 2
        }
        val db = Db(context, v2Name).also(opened::add)
        assertEquals(Schema.VERSION, db.sql.version)
        val folders = FolderDao(db)
        assertEquals(7L, folders.get(1)?.uidValidity)
        assertEquals(UidSet.EMPTY, folders.ownDeleted(1))
        folders.setOwnDeleted(1, UidSet.of(3, 4, 9))
        assertEquals(UidSet.of(3, 4, 9), folders.ownDeleted(1))
        val fresh = Db(context, freshName).also(opened::add)
        assertEquals(columns(fresh, "folder"), columns(db, "folder"))
        assertEquals(indexes(fresh), indexes(db))
        assertTrue("attachment_message" in indexes(db))
    }

    @Test
    fun upgradeFromV4KeepsTrustedSendersAsFromOnly() {
        val path = context.getDatabasePath(v4Name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v4 ->
            (Schema.V1 + Schema.MIGRATIONS.take(3).flatten()).forEach(v4::execSQL)
            v4.execSQL("INSERT INTO trusted_sender(email) VALUES('news@example.org')")
            v4.version = 4
        }
        val db = Db(context, v4Name).also(opened::add)
        assertEquals(Schema.VERSION, db.sql.version)
        // A trust of before counts as before: by From only, on every account.
        assertEquals(TrustedOn.FROM_ONLY, runBlocking { TrustDao(db).observeTrusted("news@example.org").first() })
        val fresh = Db(context, freshName).also(opened::add)
        assertEquals(columns(fresh, "trusted_sender"), columns(db, "trusted_sender"))
    }

    @Test
    fun upgradeFromV5AddsTheListIndexes() {
        val path = context.getDatabasePath(v5Name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v5 ->
            (Schema.V1 + Schema.MIGRATIONS.take(4).flatten()).forEach(v5::execSQL)
            v5.execSQL(
                "INSERT INTO account(id, provider, email, label, auth, secret) " +
                    "VALUES(1, 'other', 'a@example.org', 'Mail', 'password', x'01')",
            )
            v5.execSQL("INSERT INTO folder(id, account_id, path, name, role) VALUES(1, 1, 'INBOX', 'INBOX', 'inbox')")
            v5.execSQL("INSERT INTO message(id, account_id, folder_id, uid, subject, date) VALUES(1, 1, 1, 7, 'hello', 1000)")
            v5.execSQL("INSERT INTO message_folder(folder_id, message_id) VALUES(1, 1)")
            v5.version = 5
        }
        val db = Db(context, v5Name).also(opened::add)
        assertEquals(Schema.VERSION, db.sql.version)
        assertEquals(mapOf(1L to 1), runBlocking { FolderDao(db).observeUnread().first() })
        assertEquals(listOf(1L), runBlocking { MessageDao(db).observeList(MailView.InFolder(1), 50).first() }.map { it.id })
        val fresh = Db(context, freshName).also(opened::add)
        assertEquals(indexes(fresh), indexes(db))
        assertTrue(indexes(db).containsAll(listOf("draft_message", "message_date", "message_unread")))
    }

    private fun indexes(db: Db) =
        db.sql.list("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL ORDER BY name") { it.str(0) }

    /** cid, name, type, notnull, dflt_value, pk of each column. */
    private fun columns(db: Db, table: String) =
        db.sql.list("PRAGMA table_info($table)") { c -> (0 until c.columnCount).map { c.getString(it) } }
}
