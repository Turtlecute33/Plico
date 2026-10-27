package app.plico.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Where the user set "Always show images from this sender" (DESIGN.md §11.4, PLICO-PRIV-003): on a [VERIFIED]
 * message (Gmail with `dmarc=pass`, no "A sender can be faked" note), or on a [FROM_ONLY] message, where the note
 * was on screen.
 */
enum class TrustedOn { VERIFIED, FROM_ONLY }

/** Senders whose remote content is always shown (DESIGN.md §11.4). Addresses are stored in lower case. */
class TrustDao(private val db: Db) {
    /** True when [email] has a trust of any kind. */
    fun isTrusted(email: String): Boolean = db.sql.trusted(email) != null

    /** Where the user trusted [email]; null = not trusted. */
    fun observeTrusted(email: String): Flow<TrustedOn?> = db.observe(Tables.TRUSTED_SENDER) { trusted(email) }

    /**
     * Sets or clears the trust of [email]. [verified]: the user set it on a VERIFIED message. A trust that the user
     * set by From only stays one: it is the wider kind.
     */
    fun setTrusted(email: String, trusted: Boolean, verified: Boolean): Unit = db.tx(Tables.TRUSTED_SENDER) {
        if (trusted) {
            write(
                "INSERT INTO trusted_sender(email, verified) VALUES(?, ?) " +
                    "ON CONFLICT(email) DO UPDATE SET verified = min(verified, excluded.verified)",
                email.lowercase(), if (verified) 1 else 0,
            )
        } else {
            write("DELETE FROM trusted_sender WHERE email = ?", email.lowercase())
        }
    }

    private fun SQLiteDatabase.trusted(email: String): TrustedOn? =
        long("SELECT verified FROM trusted_sender WHERE email = ?", email.lowercase())
            ?.let { if (it != 0L) TrustedOn.VERIFIED else TrustedOn.FROM_ONLY }
}
