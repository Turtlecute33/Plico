package app.plico.data

/** Table names, as emitted on [Db.changes]. */
object Tables {
    const val ACCOUNT = "account"
    const val IDENTITY = "identity"
    const val FOLDER = "folder"
    const val MESSAGE = "message"
    const val MESSAGE_FOLDER = "message_folder"
    const val BODY = "body"
    const val ATTACHMENT = "attachment"
    const val MESSAGE_FTS = "message_fts"
    const val OP = "op"
    const val DRAFT = "draft"
    const val CONTACT = "contact"
    const val TRUSTED_SENDER = "trusted_sender"

    /** Tables that deleting message rows touches through cascades and the FTS trigger. */
    internal val MESSAGE_OWNED = arrayOf(MESSAGE, MESSAGE_FOLDER, BODY, ATTACHMENT, MESSAGE_FTS)

    /** Tables that deleting an account touches through cascades and the FTS trigger. */
    internal val ACCOUNT_OWNED = arrayOf(ACCOUNT, IDENTITY, FOLDER, OP, DRAFT, *MESSAGE_OWNED)
}

/** Schema v1, statement by statement as written in DESIGN.md §5. */
internal object Schema {
    const val VERSION = 1

    val V1 = listOf(
        """
        CREATE TABLE account(
          id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL,      -- 'riseup' | 'gmail'
          email TEXT NOT NULL UNIQUE, sender_name TEXT NOT NULL DEFAULT '', label TEXT NOT NULL,   -- label default 'Riseup'/'Gmail' (+ n)
          color INTEGER NOT NULL DEFAULT 0,
          auth TEXT NOT NULL,                       -- 'password' | 'oauth' | 'device'
          secret BLOB, device_account TEXT,
          body_days INTEGER NOT NULL DEFAULT 30,    -- 0 = all mail offline
          push_mode INTEGER NOT NULL DEFAULT 0,     -- 0 instant, 1 instant on Wi-Fi/charging + poll on mobile data, 2 poll
          poll_minutes INTEGER NOT NULL DEFAULT 15,
          gmail_categories TEXT NOT NULL DEFAULT 'primary',
          archive_folder INTEGER,                   -- Riseup: chosen archive folder id (NULL = auto)
          signature TEXT NOT NULL DEFAULT '',
          state INTEGER NOT NULL DEFAULT 0, state_text TEXT,   -- 0 ok, 1 sign-in needed, 2 TLS problem, 3 setup problem (e.g. Gmail All Mail hidden)
          caps TEXT, server_keepalive_s INTEGER, quota_used INTEGER, quota_limit INTEGER, sort INTEGER NOT NULL DEFAULT 0)
        """,
        """
        CREATE TABLE identity(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
          email TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', UNIQUE(account_id, email))
        """,
        """
        CREATE TABLE folder(
          id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
          path TEXT NOT NULL,                       -- decoded mailbox name, or Gmail label (system labels keep the backslash)
          name TEXT NOT NULL, role TEXT,            -- inbox sent drafts trash junk archive all important | NULL
          kind INTEGER NOT NULL DEFAULT 0,          -- 0 IMAP mailbox, 1 Gmail label view
          delimiter TEXT, hidden INTEGER NOT NULL DEFAULT 0,   -- hidden kind-0 = not synced (e.g. Gmail [Gmail]/Drafts); hidden kind-1 = label not in LIST
          notify INTEGER NOT NULL DEFAULT 0, guard INTEGER NOT NULL DEFAULT 0,   -- guard = frozen by the mass-delete guard
          uidvalidity INTEGER NOT NULL DEFAULT 0, uidnext INTEGER NOT NULL DEFAULT 0, modseq INTEGER NOT NULL DEFAULT 0,
          backfill_uid INTEGER NOT NULL DEFAULT 0,  -- headers synced for UIDs >= this; 1 = complete; 0 = never synced
          synced_at INTEGER NOT NULL DEFAULT 0, UNIQUE(account_id, kind, path))
        """,
        """
        CREATE TABLE message(
          id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
          folder_id INTEGER NOT NULL REFERENCES folder(id) ON DELETE CASCADE,  -- source mailbox (kind 0)
          uid INTEGER NOT NULL, gm_msgid INTEGER,
          message_id TEXT, in_reply_to TEXT, refs TEXT,
          subject TEXT NOT NULL DEFAULT '', from_name TEXT NOT NULL DEFAULT '', from_email TEXT NOT NULL DEFAULT '',
          to_list TEXT NOT NULL DEFAULT '', cc_list TEXT NOT NULL DEFAULT '', bcc_list TEXT NOT NULL DEFAULT '', reply_to TEXT NOT NULL DEFAULT '',
          date INTEGER NOT NULL,                    -- INTERNALDATE (sort, body window, notify rule)
          size INTEGER NOT NULL DEFAULT 0,
          seen INTEGER NOT NULL DEFAULT 0, flagged INTEGER NOT NULL DEFAULT 0, answered INTEGER NOT NULL DEFAULT 0,
          draft INTEGER NOT NULL DEFAULT 0, modseq INTEGER NOT NULL DEFAULT 0,
          attachments INTEGER NOT NULL DEFAULT 0, snippet TEXT NOT NULL DEFAULT '',
          auth INTEGER NOT NULL DEFAULT 0,          -- 0 unknown, 1 dmarc=pass, 2 fail (Gmail mx.google.com only)
          unsubscribe TEXT, unsubscribe_post INTEGER NOT NULL DEFAULT 0,
          body INTEGER NOT NULL DEFAULT 0, notified INTEGER NOT NULL DEFAULT 1, hidden INTEGER NOT NULL DEFAULT 0,
          UNIQUE(folder_id, uid))
        """,
        "CREATE INDEX message_account_date ON message(account_id, date DESC)",
        "CREATE INDEX message_gm ON message(account_id, gm_msgid)",
        "CREATE INDEX message_mid ON message(account_id, message_id)",
        """
        CREATE TABLE message_folder(folder_id INTEGER NOT NULL REFERENCES folder(id) ON DELETE CASCADE,
          message_id INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE, PRIMARY KEY(folder_id, message_id)) WITHOUT ROWID
        """,
        "CREATE INDEX message_folder_message ON message_folder(message_id)",
        "CREATE TABLE body(message_id INTEGER PRIMARY KEY REFERENCES message(id) ON DELETE CASCADE, html TEXT, text TEXT)",
        """
        CREATE TABLE attachment(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,
          section TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, encoding TEXT NOT NULL,
          cid TEXT, inline INTEGER NOT NULL DEFAULT 0)      -- file path is derived: cacheDir/att/<message_id>/<id>
        """,
        "CREATE VIRTUAL TABLE message_fts USING fts4(subject, people, body, tokenize=unicode61)   -- rowid = message.id",
        "CREATE TRIGGER message_ad AFTER DELETE ON message BEGIN DELETE FROM message_fts WHERE rowid = old.id; END",
        """
        CREATE TABLE op(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
          kind TEXT NOT NULL, message_ids TEXT NOT NULL DEFAULT '', arg TEXT NOT NULL DEFAULT '{}',
          attempts INTEGER NOT NULL DEFAULT 0, run_at INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, error TEXT)
        """,
        """
        CREATE TABLE draft(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
          message_id TEXT NOT NULL,                 -- stable for every revision and the final send
          from_email TEXT NOT NULL, to_list TEXT NOT NULL DEFAULT '', cc_list TEXT NOT NULL DEFAULT '', bcc_list TEXT NOT NULL DEFAULT '',
          subject TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', in_reply_to TEXT, refs TEXT,
          source_message INTEGER, attachments TEXT NOT NULL DEFAULT '[]',   -- JSON [{file,name,mime,size}], file = safe name in filesDir/outbox/<id>/
          on_server INTEGER NOT NULL DEFAULT 0,     -- a revision was APPENDed
          state INTEGER NOT NULL DEFAULT 0,         -- 0 editing, 1 queued, 2 sending, 3 failed, 4 sent (follow-up ops pending)
          send_at INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, error TEXT, updated INTEGER NOT NULL)
        """,
        "CREATE TABLE contact(email TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', last_used INTEGER NOT NULL DEFAULT 0, uses INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE trusted_sender(email TEXT PRIMARY KEY)",
    ).map { it.trimIndent() }
}
