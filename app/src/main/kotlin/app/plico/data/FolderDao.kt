package app.plico.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.plico.mail.imap.UidSet
import kotlinx.coroutines.flow.Flow

/** Mailboxes (kind 0) and Gmail label views (kind 1), their sync state and computed unread counts. */
class FolderDao(private val db: Db) {
    fun get(id: Long): Folder? = db.sql.folder(id)
    fun observe(id: Long): Flow<Folder?> = db.observe(Tables.FOLDER) { folder(id) }

    /** Every folder of the account, hidden ones included. */
    fun byAccount(accountId: Long): List<Folder> =
        db.sql.list("SELECT $FOLDER_COLS FROM folder WHERE account_id = ? ORDER BY id", accountId, row = ::folderOf)

    fun byPath(accountId: Long, kind: Int, path: String): Folder? = db.sql.one(
        "SELECT $FOLDER_COLS FROM folder WHERE account_id = ? AND kind = ? AND path = ?",
        accountId, kind, path, row = ::folderOf,
    )

    /** See [AccountAbilities.of]. */
    fun abilities(account: Account): AccountAbilities = AccountAbilities.of(account, byAccount(account.id))

    fun byRole(accountId: Long, role: FolderRole, kind: Int): Folder? = db.sql.one(
        "SELECT $FOLDER_COLS FROM folder WHERE account_id = ? AND role = ? AND kind = ? ORDER BY id LIMIT 1",
        accountId, role.id, kind, row = ::folderOf,
    )

    /** Folders with hidden = 0 of one account, or of every account when [accountId] is null. */
    fun observeVisible(accountId: Long? = null): Flow<List<Folder>> = db.observe(Tables.FOLDER) {
        if (accountId == null) {
            list("SELECT $FOLDER_COLS FROM folder WHERE hidden = 0 ORDER BY account_id, path", row = ::folderOf)
        } else {
            list(
                "SELECT $FOLDER_COLS FROM folder WHERE hidden = 0 AND account_id = ? ORDER BY path",
                accountId, row = ::folderOf,
            )
        }
    }

    /**
     * Inserts the folder, keyed by (account, kind, path), or updates its name, role, delimiter and hidden
     * flag; returns its id. Sync state, notify and guard are kept.
     */
    fun upsert(folder: NewFolder): Long = db.tx(Tables.FOLDER) {
        write(
            """
            INSERT INTO folder(account_id, kind, path, name, role, delimiter, hidden) VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, kind, path) DO UPDATE SET
              name = excluded.name, role = excluded.role, delimiter = excluded.delimiter, hidden = excluded.hidden
            """,
            folder.accountId, folder.kind, folder.path, folder.name, folder.role?.id, folder.delimiter, folder.hidden,
        )
        id(folder.accountId, folder.kind, folder.path)!!
    }

    /**
     * Id of the Gmail label view [path]. A label that has no row yet gets a hidden one until LIST shows it
     * (`protocol PS6`).
     */
    fun ensureLabel(accountId: Long, path: String, name: String): Long =
        db.sql.id(accountId, FolderKind.LABEL, path) ?: db.tx(Tables.FOLDER) {
            write(
                "INSERT OR IGNORE INTO folder(account_id, kind, path, name, hidden) VALUES(?, ?, ?, ?, 1)",
                accountId, FolderKind.LABEL, path, name,
            )
            id(accountId, FolderKind.LABEL, path)!!
        }

    fun setHidden(id: Long, hidden: Boolean): Unit = set(id, "hidden", hidden)
    fun setNotify(id: Long, notify: Boolean): Unit = set(id, "notify", notify)

    /** The mass-delete guard (DESIGN.md §7.2 step 6). [resync] clears it. */
    fun setGuard(id: Long, guard: Boolean): Unit = set(id, "guard", guard)

    /** Writes the given sync state values; null keeps a value. */
    fun setSyncState(
        id: Long,
        uidValidity: Long? = null,
        uidNext: Long? = null,
        modSeq: Long? = null,
        backfillUid: Long? = null,
        syncedAt: Long? = null,
    ): Unit = db.tx(Tables.FOLDER) {
        write(
            """
            UPDATE folder SET uidvalidity = coalesce(?, uidvalidity), uidnext = coalesce(?, uidnext),
              modseq = coalesce(?, modseq), backfill_uid = coalesce(?, backfill_uid), synced_at = coalesce(?, synced_at)
            WHERE id = ?
            """,
            uidValidity, uidNext, modSeq, backfillUid, syncedAt, id,
        )
    }

    /**
     * Drops the folder's messages and memberships and resets uidvalidity, uidnext, modseq, backfill, the guard
     * and [ownDeleted], so the next sync starts fresh (guard "Resync", UIDVALIDITY change).
     */
    fun resync(id: Long): Unit = db.tx(Tables.FOLDER, *Tables.MESSAGE_OWNED) {
        write("DELETE FROM message WHERE folder_id = ?", id)
        write("DELETE FROM message_folder WHERE folder_id = ?", id)
        write(
            """
            UPDATE folder SET uidvalidity = 0, uidnext = 0, modseq = 0, backfill_uid = 0, guard = 0, own_deleted = NULL
            WHERE id = ?
            """,
            id,
        )
    }

    /**
     * The UIDs that Plico flagged `\Deleted` in mailbox [id] and that a guarded EXPUNGE left on the server (DESIGN.md
     * §7.4). They are Plico's own, so they never stop a later guarded EXPUNGE of this mailbox. Valid for the stored
     * uidvalidity only: [resync] clears them.
     */
    fun ownDeleted(id: Long): UidSet {
        val text = db.sql.one("SELECT own_deleted FROM folder WHERE id = ?", id) { c -> c.strOrNull(0) } ?: return UidSet.EMPTY
        return try {
            UidSet.parse(text)
        } catch (_: IllegalArgumentException) {
            UidSet.EMPTY
        }
    }

    /** Writes [ownDeleted]; an empty set clears it. */
    fun setOwnDeleted(id: Long, uids: UidSet): Unit = db.tx(Tables.FOLDER) {
        write("UPDATE folder SET own_deleted = ? WHERE id = ?", if (uids.isEmpty) null else uids.toString(), id)
        Unit
    }

    /** Deletes the folder with its messages (a mailbox that LIST no longer shows). */
    fun delete(id: Long): Unit = db.tx(Tables.FOLDER, *Tables.MESSAGE_OWNED) {
        write("DELETE FROM folder WHERE id = ?", id)
    }

    /** Unseen, visible messages per folder id (`simplicity EM10`); folders without any are absent. */
    fun observeUnread(): Flow<Map<Long, Int>> = db.observe(Tables.MESSAGE, Tables.MESSAGE_FOLDER) {
        list(
            """
            SELECT mf.folder_id, count(*) FROM message_folder mf JOIN message m ON m.id = mf.message_id
            WHERE m.seen = 0 AND m.hidden = 0 AND m.deleted = 0 GROUP BY mf.folder_id
            """,
        ) { c -> c.getLong(0) to c.getInt(1) }.toMap()
    }

    /** Unseen, visible messages in any inbox (the unified inbox); each message counts once. */
    fun observeUnifiedUnread(): Flow<Int> = db.observe(Tables.MESSAGE, Tables.MESSAGE_FOLDER, Tables.FOLDER) {
        long(
            """
            SELECT count(*) FROM message m WHERE m.seen = 0 AND m.hidden = 0 AND m.deleted = 0 AND m.id IN
              (SELECT mf.message_id FROM message_folder mf JOIN folder f ON f.id = mf.folder_id WHERE f.role = 'inbox')
            """,
        )!!.toInt()
    }

    private fun set(id: Long, column: String, value: Boolean) = db.tx(Tables.FOLDER) {
        write("UPDATE folder SET $column = ? WHERE id = ?", value, id)
        Unit
    }

    private fun SQLiteDatabase.folder(id: Long) =
        one("SELECT $FOLDER_COLS FROM folder WHERE id = ?", id, row = ::folderOf)

    private fun SQLiteDatabase.id(accountId: Long, kind: Int, path: String) =
        long("SELECT id FROM folder WHERE account_id = ? AND kind = ? AND path = ?", accountId, kind, path)
}

internal const val FOLDER_COLS = "id, account_id, path, name, role, kind, delimiter, hidden, notify, guard, " +
    "uidvalidity, uidnext, modseq, backfill_uid, synced_at"

/** Maps a row of [FOLDER_COLS]. */
internal fun folderOf(c: Cursor) = Folder(
    id = c.getLong(0),
    accountId = c.getLong(1),
    path = c.str(2),
    name = c.str(3),
    role = FolderRole.of(c.strOrNull(4)),
    kind = c.getInt(5),
    delimiter = c.strOrNull(6),
    hidden = c.bool(7),
    notify = c.bool(8),
    guard = c.bool(9),
    uidValidity = c.getLong(10),
    uidNext = c.getLong(11),
    modSeq = c.getLong(12),
    backfillUid = c.getLong(13),
    syncedAt = c.getLong(14),
)
