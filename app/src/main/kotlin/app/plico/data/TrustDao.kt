package app.plico.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Senders whose remote content is always shown (DESIGN.md §11.4). Addresses are stored in lower case. */
class TrustDao(private val db: Db) {
    fun isTrusted(email: String): Boolean = db.sql.trusted(email)

    fun observeTrusted(email: String): Flow<Boolean> = db.observe(Tables.TRUSTED_SENDER) { trusted(email) }

    fun setTrusted(email: String, trusted: Boolean): Unit = db.tx(Tables.TRUSTED_SENDER) {
        if (trusted) {
            write("INSERT OR IGNORE INTO trusted_sender(email) VALUES(?)", email.lowercase())
        } else {
            write("DELETE FROM trusted_sender WHERE email = ?", email.lowercase())
        }
    }

    private fun SQLiteDatabase.trusted(email: String) =
        long("SELECT 1 FROM trusted_sender WHERE email = ?", email.lowercase()) != null
}
