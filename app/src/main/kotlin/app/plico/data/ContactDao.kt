package app.plico.data

import app.plico.mail.mime.Address

/**
 * Addresses learned from sent recipients and from senders the user replied to (DESIGN.md §5); no
 * contacts permission. Addresses are stored in lower case.
 */
class ContactDao(private val db: Db) {
    /** Counts one use of each address. A non-empty name replaces the stored one. */
    fun touch(addresses: List<Address>, now: Long = System.currentTimeMillis()): Unit = db.tx(Tables.CONTACT) {
        addresses.filter { it.email.isNotBlank() }.distinctBy { it.email.lowercase() }.forEach { a ->
            write(
                """
                INSERT INTO contact(email, name, last_used, uses) VALUES(?, ?, ?, 1)
                ON CONFLICT(email) DO UPDATE SET
                  name = CASE WHEN excluded.name != '' THEN excluded.name ELSE name END,
                  last_used = excluded.last_used, uses = uses + 1
                """,
                a.email.lowercase(), a.name.orEmpty().trim(), now,
            )
        }
    }

    /** True when [email] (any case) is a learned contact. */
    fun isKnown(email: String): Boolean =
        db.sql.long("SELECT 1 FROM contact WHERE email = ?", email.lowercase()) != null

    /** Contacts whose address, or a word of whose name, starts with [prefix]; most used, then most recent first. */
    fun suggest(prefix: String, limit: Int = 8): List<Contact> {
        val like = likePrefix(prefix.trim()) ?: return emptyList()
        return db.sql.list(
            """
            SELECT email, name, last_used, uses FROM contact
            WHERE email LIKE ? ESCAPE '\' OR name LIKE ? ESCAPE '\' OR name LIKE ? ESCAPE '\'
            ORDER BY uses DESC, last_used DESC LIMIT $limit
            """,
            like, like, "% $like",
        ) { c -> Contact(c.str(0), c.str(1), c.getLong(2), c.getInt(3)) }
    }

    internal companion object {
        /** A LIKE pattern (escape `\`) that matches values starting with [prefix]; null for an empty prefix. */
        fun likePrefix(prefix: String): String? = prefix.takeIf { it.isNotEmpty() }
            ?.replace("\\", "\\\\")?.replace("%", "\\%")?.replace("_", "\\_")?.plus("%")
    }
}
