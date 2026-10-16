package app.plico.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow

data class NewAccount(
    val provider: Provider,
    val email: String,
    val senderName: String,
    val label: String,
    val color: Int,
    val auth: AuthKind,
    /** Vault ciphertext of the password or refresh token; null for [AuthKind.DEVICE]. */
    val secret: ByteArray?,
    val deviceAccount: String?,
    /** 0 = all mail offline; the schema default is [DEFAULT_BODY_DAYS]. */
    val bodyDays: Int = DEFAULT_BODY_DAYS,
) {
    companion object {
        /** Bodies of the last 30 days are offline (DESIGN §1). */
        const val DEFAULT_BODY_DAYS = 30
    }
}

class AccountDao(private val db: Db) {
    /** Every account in display order (sort, then id). */
    fun all(): List<Account> = db.sql.accounts()
    fun observeAll(): Flow<List<Account>> = db.observe(Tables.ACCOUNT) { accounts() }
    fun get(id: Long): Account? = db.sql.account(id)
    fun observe(id: Long): Flow<Account?> = db.observe(Tables.ACCOUNT) { account(id) }

    /** The account with this address, compared without case. */
    fun byEmail(email: String): Account? =
        db.sql.one("SELECT $COLS FROM account WHERE email = ? COLLATE NOCASE", email, row = ::accountOf)

    /** 'Riseup' or 'Gmail'; when that label is taken, the lowest free number is added: 'Riseup 2'. */
    fun defaultLabel(provider: Provider): String =
        nextLabel(provider, db.sql.list("SELECT label FROM account") { it.str(0) }.toSet())

    /** Inserts the account at the end of the sort order and returns its id. */
    fun insert(account: NewAccount): Long = db.tx(Tables.ACCOUNT) {
        insertRow(
            """
            INSERT INTO account(provider, email, sender_name, label, color, auth, secret, device_account, body_days, sort)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT coalesce(max(sort) + 1, 0) FROM account))
            """,
            account.provider.id, account.email, account.senderName, account.label, account.color,
            account.auth.id, account.secret, account.deviceAccount, account.bodyDays,
        )
    }

    /** Writes the user-editable settings of [account] (label, sender name, colour, body days, push, categories, archive folder, signature, sort). */
    fun update(account: Account): Unit = db.tx(Tables.ACCOUNT) {
        write(
            """
            UPDATE account SET label = ?, sender_name = ?, color = ?, body_days = ?, push_mode = ?, poll_minutes = ?,
              gmail_categories = ?, archive_folder = ?, signature = ?, sort = ? WHERE id = ?
            """,
            account.label, account.senderName, account.color, account.bodyDays, account.pushMode.id,
            account.pollMinutes, formatCategories(account.gmailCategories), account.archiveFolder,
            account.signature, account.sort, account.id,
        )
    }

    fun secret(id: Long): ByteArray? =
        db.sql.one("SELECT secret FROM account WHERE id = ?", id) { c -> if (c.isNull(0)) null else c.getBlob(0) }

    fun setSecret(id: Long, secret: ByteArray?): Unit = set(id, "secret", secret)

    fun setAuth(id: Long, auth: AuthKind, secret: ByteArray?, deviceAccount: String?): Unit = db.tx(Tables.ACCOUNT) {
        write(
            "UPDATE account SET auth = ?, secret = ?, device_account = ? WHERE id = ?",
            auth.id, secret, deviceAccount, id,
        )
    }

    fun setState(id: Long, state: AccountState, text: String?): Unit = db.tx(Tables.ACCOUNT) {
        write("UPDATE account SET state = ?, state_text = ? WHERE id = ?", state.id, text, id)
    }

    fun setServerKeepalive(id: Long, seconds: Int?): Unit = set(id, "server_keepalive_s", seconds)

    fun setQuota(id: Long, used: Long?, limit: Long?): Unit = db.tx(Tables.ACCOUNT) {
        write("UPDATE account SET quota_used = ?, quota_limit = ? WHERE id = ?", used, limit, id)
    }

    /** Deletes the account and everything under it (cascade). File and notification cleanup is the caller's job. */
    fun delete(id: Long): Unit = db.tx(*Tables.ACCOUNT_OWNED) {
        write("DELETE FROM account WHERE id = ?", id)
    }

    /**
     * Removes the account so that no copy of its mail stays on disk (`privacy PS8`): the cascade of [delete], the outbox
     * dirs of its drafts and the attachment dirs of its messages, then compacts the search index and
     * truncates the WAL. Contacts and trusted senders belong to no single account; they go with the last
     * account. Blocking; call it off the main thread. The caller deletes the account's notification channel.
     */
    fun purge(id: Long, files: Files) {
        val sql = db.sql
        val drafts = sql.list("SELECT id FROM draft WHERE account_id = ?", id) { it.getLong(0) }
        val messages = sql.list(
            "SELECT DISTINCT message_id FROM attachment WHERE message_id IN (SELECT id FROM message WHERE account_id = ?)",
            id,
        ) { it.getLong(0) }
        db.tx(*Tables.ACCOUNT_OWNED, Tables.CONTACT, Tables.TRUSTED_SENDER) {
            write("DELETE FROM account WHERE id = ?", id)
            if (long("SELECT 1 FROM account LIMIT 1") == null) {
                write("DELETE FROM contact")
                write("DELETE FROM trusted_sender")
            }
        }
        drafts.forEach(files::deleteOutbox)
        messages.forEach(files::deleteAttachments)
        sql.execSQL("INSERT INTO message_fts(message_fts) VALUES('optimize')")
        // This PRAGMA returns a row, so it must run as a query.
        sql.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    fun identities(accountId: Long): List<Identity> = db.sql.identityRows(accountId)

    fun observeIdentities(accountId: Long): Flow<List<Identity>> =
        db.observe(Tables.IDENTITY) { identityRows(accountId) }

    /** Adds the address, or renames it when it exists. */
    fun addIdentity(accountId: Long, email: String, name: String): Unit = db.tx(Tables.IDENTITY) {
        write(
            "INSERT INTO identity(account_id, email, name) VALUES(?, ?, ?) " +
                "ON CONFLICT(account_id, email) DO UPDATE SET name = excluded.name",
            accountId, email, name,
        )
    }

    fun removeIdentity(id: Long): Unit = db.tx(Tables.IDENTITY) {
        write("DELETE FROM identity WHERE id = ?", id)
    }

    private fun set(id: Long, column: String, value: Any?) = db.tx(Tables.ACCOUNT) {
        write("UPDATE account SET $column = ? WHERE id = ?", value, id)
        Unit
    }

    private fun SQLiteDatabase.accounts() = list("SELECT $COLS FROM account ORDER BY sort, id", row = ::accountOf)
    private fun SQLiteDatabase.account(id: Long) = one("SELECT $COLS FROM account WHERE id = ?", id, row = ::accountOf)

    private fun SQLiteDatabase.identityRows(accountId: Long) = list(
        "SELECT id, account_id, email, name FROM identity WHERE account_id = ? ORDER BY email",
        accountId,
    ) { c -> Identity(c.getLong(0), c.getLong(1), c.str(2), c.str(3)) }

    internal companion object {
        private const val COLS = "id, provider, email, sender_name, label, color, auth, device_account, body_days, " +
            "push_mode, poll_minutes, gmail_categories, archive_folder, signature, state, state_text, " +
            "server_keepalive_s, quota_used, quota_limit, sort"

        /** The provider's name, numbered from 2 when [taken] already holds it. */
        fun nextLabel(provider: Provider, taken: Set<String>): String {
            val base = provider.brand
            return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        }

        /** `gmail_categories` column format: comma-separated names. */
        fun formatCategories(categories: Set<String>): String = categories.joinToString(",")

        fun parseCategories(text: String): Set<String> =
            text.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

        private fun accountOf(c: Cursor) = Account(
            id = c.getLong(0),
            provider = Provider.of(c.str(1)),
            email = c.str(2),
            senderName = c.str(3),
            label = c.str(4),
            color = c.getInt(5),
            auth = AuthKind.of(c.str(6)),
            deviceAccount = c.strOrNull(7),
            bodyDays = c.getInt(8),
            pushMode = PushMode.of(c.getInt(9)),
            pollMinutes = c.getInt(10),
            gmailCategories = parseCategories(c.str(11)),
            archiveFolder = c.longOrNull(12),
            signature = c.str(13),
            state = AccountState.of(c.getInt(14)),
            stateText = c.strOrNull(15),
            serverKeepaliveS = c.longOrNull(16)?.toInt(),
            quotaUsed = c.longOrNull(17),
            quotaLimit = c.longOrNull(18),
            sort = c.getInt(19),
        )
    }
}
