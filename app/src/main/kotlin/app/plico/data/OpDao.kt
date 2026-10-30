package app.plico.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Pending server operations (DESIGN.md §7.4). Rows run in id order once run_at has passed. */
class OpDao(private val db: Db) {
    /** Queues [op]; returns its id. */
    fun insert(op: NewOp): Long = db.tx(Tables.OP) {
        insertRow(
            "INSERT INTO op(account_id, kind, message_ids, arg, run_at, created) VALUES(?, ?, ?, ?, ?, ?)",
            op.accountId, op.kind, formatIds(op.messageIds), op.arg, op.runAt, System.currentTimeMillis(),
        )
    }

    fun get(id: Long): Op? = db.sql.one("SELECT $COLS FROM op WHERE id = ?", id, row = ::opOf)

    /** Every op of the account, in id order. */
    fun byAccount(accountId: Long): List<Op> =
        db.sql.list("SELECT $COLS FROM op WHERE account_id = ? ORDER BY id", accountId, row = ::opOf)

    /** Ops of the account with run_at <= [now], in id order. */
    fun due(accountId: Long, now: Long): List<Op> = db.sql.list(
        "SELECT $COLS FROM op WHERE account_id = ? AND run_at <= ? ORDER BY id",
        accountId, now, row = ::opOf,
    )

    fun delete(id: Long): Unit = db.tx(Tables.OP) {
        write("DELETE FROM op WHERE id = ?", id)
    }

    /** Records a failed attempt: the op runs again at [runAt] (backoff). */
    fun retryLater(id: Long, attempts: Int, runAt: Long, error: String?): Unit = db.tx(Tables.OP) {
        write("UPDATE op SET attempts = ?, run_at = ?, error = ? WHERE id = ?", attempts, runAt, error, id)
    }

    /**
     * Message ids that the account's ops reference, whatever their run_at. Sync does not change the flags
     * or labels of these rows (`protocol PS2`).
     */
    fun referencedMessageIds(accountId: Long): Set<Long> = db.sql.opMessageIds(accountId)

    internal companion object {
        private const val COLS = "id, account_id, kind, message_ids, arg, attempts, run_at, created, error"

        /** `op.message_ids` column format: comma-separated ids. */
        fun formatIds(ids: List<Long>): String = ids.joinToString(",")

        fun parseIds(text: String): List<Long> = text.split(',').mapNotNull { it.trim().toLongOrNull() }

        private fun opOf(c: Cursor) = Op(
            id = c.getLong(0),
            accountId = c.getLong(1),
            kind = c.str(2),
            messageIds = parseIds(c.str(3)),
            arg = c.str(4),
            attempts = c.getInt(5),
            runAt = c.getLong(6),
            created = c.getLong(7),
            error = c.strOrNull(8),
        )
    }
}

/** Message ids referenced by ops of one account, or of all accounts when [accountId] is null. */
internal fun SQLiteDatabase.opMessageIds(accountId: Long? = null): Set<Long> {
    val lists = if (accountId == null) {
        list("SELECT message_ids FROM op") { it.str(0) }
    } else {
        list("SELECT message_ids FROM op WHERE account_id = ?", accountId) { it.str(0) }
    }
    return lists.flatMapTo(HashSet()) { OpDao.parseIds(it) }
}
