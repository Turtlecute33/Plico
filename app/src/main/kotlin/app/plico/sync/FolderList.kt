package app.plico.sync

import app.plico.R
import app.plico.account.MailError
import app.plico.data.AccountState
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewFolder
import app.plico.mail.imap.Command
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.MailboxInfo
import app.plico.mail.imap.MailboxName
import app.plico.mail.imap.MailboxStatus
import app.plico.mail.imap.listOrEmpty
import app.plico.mail.imap.longOrNull
import app.plico.mail.imap.textOrNull

/** The folder list of one account (DESIGN.md §7.2 step 1, §7.3) and the STATUS of its mailboxes (step 2). */
internal class FolderList(private val env: SyncEnv) {

    /**
     * LIST, then writes the folder rows: new and changed ones are upserted, mailboxes that LIST no longer shows
     * are deleted, and Gmail user labels that it no longer shows are hidden. Returns the Gmail label views that
     * had no row before (candidates for the one-time fill). A Gmail account without All Mail throws
     * [MailError.Setup] after it has set `account.state = 3`.
     */
    fun refresh(): List<Folder> {
        val list = env.client.list()
        val before = env.folders.byAccount(env.accountId)
        return if (env.gmail) gmail(FolderRules.gmail(env.accountId, list), before) else riseup(list, before)
    }

    private fun riseup(list: List<MailboxInfo>, before: List<Folder>): List<Folder> {
        val chosen = env.accounts.get(env.accountId)?.archiveFolder
        val archive = chosen?.let { id -> before.firstOrNull { it.id == id }?.path }
        val rows = FolderRules.riseup(env.accountId, list, archive)
        write(rows, before)
        clearSetupProblem()
        return emptyList()
    }

    private fun gmail(listed: FolderRules.Gmail, before: List<Folder>): List<Folder> {
        if (FolderRole.ALL in listed.missing) {
            // No sync, but the local rows stay: All Mail usually comes back with the same UIDVALIDITY.
            val text = env.text(R.string.account_gmail_all_mail_hidden)
            env.accounts.setState(env.accountId, AccountState.SETUP_PROBLEM, text)
            throw MailError.Setup(text)
        }
        val known = before.filter { it.kind == FolderKind.LABEL }.map { it.path }.toSet()
        write(listed.folders, before)
        val notes = listed.missing.mapNotNull { MISSING_NOTES[it] }.map(env::text)
        if (notes.isEmpty()) {
            clearSetupProblem()
        } else {
            env.accounts.setState(env.accountId, AccountState.SETUP_PROBLEM, notes.joinToString("\n"))
        }
        return env.folders.byAccount(env.accountId).filter {
            it.kind == FolderKind.LABEL && it.role == null && !it.hidden && it.path !in known
        }
    }

    private fun write(rows: List<NewFolder>, before: List<Folder>) {
        val listed = rows.map { it.kind to it.path }.toSet()
        rows.forEach(env.folders::upsert)
        for (folder in before) {
            if (folder.kind to folder.path in listed) continue
            when {
                folder.kind == FolderKind.MAILBOX -> env.folders.delete(folder.id)
                folder.role == null && !folder.hidden -> env.folders.setHidden(folder.id, true)
            }
        }
    }

    private fun clearSetupProblem() {
        if (env.accounts.get(env.accountId)?.state == AccountState.SETUP_PROBLEM) {
            env.accounts.setState(env.accountId, AccountState.OK, null)
        }
    }

    /**
     * STATUS of [folders] by id: one LIST-STATUS when the server has it and more than one mailbox is asked for,
     * else one STATUS each. Mailboxes that the server does not report (deleted) are left out.
     */
    fun statuses(folders: List<Folder>): Map<Long, MailboxStatus> {
        if (folders.size > 1 && "LIST-STATUS" in env.client.caps) {
            val byPath = listStatus(env.client)
            return folders.mapNotNull { f -> byPath[f.path]?.let { f.id to it } }.toMap()
        }
        return folders.mapNotNull { folder ->
            try {
                folder.id to env.client.status(folder.path)
            } catch (_: ImapNoException) {
                null // Deleted on the server; the caller lists again.
            }
        }.toMap()
    }

    companion object {
        private val MISSING_NOTES = mapOf(
            FolderRole.TRASH to R.string.account_note_gmail_trash_hidden,
            FolderRole.JUNK to R.string.account_note_gmail_spam_hidden,
        )

        /** `LIST "" "*" RETURN (STATUS (...))` (RFC 5819): every mailbox's status in one round trip, by decoded name. */
        fun listStatus(client: ImapClient): Map<String, MailboxStatus> {
            val items = "MESSAGES UIDNEXT UIDVALIDITY" + if ("CONDSTORE" in client.caps) " HIGHESTMODSEQ" else ""
            val out = HashMap<String, MailboxStatus>()
            val command = Command.build {
                raw("LIST")
                string("")
                string("*")
                raw("RETURN (STATUS ($items))")
            }
            client.conn.execute(command) { r ->
                if (r.keyword != "STATUS") return@execute
                val wire = r.values.getOrNull(0)?.textOrNull() ?: return@execute
                val values = r.values.getOrNull(1)?.listOrEmpty().orEmpty().chunked(2).mapNotNull { pair ->
                    val name = pair.getOrNull(0)?.textOrNull()?.uppercase() ?: return@mapNotNull null
                    pair.getOrNull(1)?.longOrNull()?.let { name to it }
                }.toMap()
                val name = if (wire.equals(FolderRules.INBOX, ignoreCase = true)) FolderRules.INBOX else MailboxName.decode(wire)
                out[name] = MailboxStatus(
                    messages = values["MESSAGES"] ?: return@execute,
                    uidNext = values["UIDNEXT"] ?: return@execute,
                    uidValidity = values["UIDVALIDITY"] ?: return@execute,
                    highestModSeq = values["HIGHESTMODSEQ"]?.takeIf { it > 0 },
                )
            }
            return out
        }
    }
}
