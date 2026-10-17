package app.plico.sync

import app.plico.PlicoApp
import app.plico.R
import app.plico.account.MailError
import app.plico.account.traits
import app.plico.data.Db
import app.plico.data.Draft
import app.plico.data.DraftState
import app.plico.data.Files
import app.plico.data.FolderRole
import app.plico.data.Tables
import app.plico.mail.smtp.SmtpException
import app.plico.mail.smtp.SmtpRecipientException
import app.plico.mail.smtp.SmtpUncertainException
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends queued drafts (DESIGN.md §7.5). The draft table is the only outbox: a send claims its row (queued ->
 * sending), and the final 250 commits sent state and the follow-up ops in one transaction, so a later failure
 * never sends the message twice (protocol PS1).
 */
object Sender {
    /** Databases whose interrupted sends were failed in this process. */
    private val checked: MutableSet<Db> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /**
     * At process start: drafts left in SENDING become UNCERTAIN ("may have been sent"), sent drafts whose cleanup
     * did not run are deleted, and so are outbox dirs without a draft (a process that died after the draft was
     * deleted). Runs once per process; call it off the main thread.
     */
    fun onProcessStart(app: PlicoApp) {
        val store = OpStore.of(app)
        // "Message not sent" is posted here, not through Ops.failures: that flow keeps nothing, and at process
        // start its collector may not run yet.
        failInterrupted(store).map { it.second }.distinct().forEach(app.notifier::sendFailed)
        // Draft ids are never used again (AUTOINCREMENT), and a draft row exists before its dir.
        store.files.outboxDraftIds().filter { store.drafts.get(it) == null }.forEach(store.files::deleteOutbox)
    }

    /**
     * The start-up rule; [run] calls it before its first claim, so no send of this process is touched. Returns
     * the drafts it failed, as (draft id, account id), once per process: the caller tells the user.
     */
    internal fun failInterrupted(store: OpStore): List<Pair<Long, Long>> = synchronized(checked) {
        if (!checked.add(store.db)) return@synchronized emptyList()
        val failed = store.drafts.failInterrupted(store.text(R.string.data_error_may_have_been_sent))
        // A process that died between the sent transaction and its cleanup left the row: its Message-ID would
        // hide the sent message from every list (protocol PS7).
        store.drafts.sentIds().forEach { cleanup(store, it) }
        failed
    }

    /** Sends every due draft of the account (DESIGN §7.5). */
    internal suspend fun run(ctx: OpContext) {
        val store = ctx.store
        // Only when this runs before onProcessStart: the failures are reported as every failed send is.
        for ((id, accountId) in failInterrupted(store)) {
            Ops.report(accountId, store.text(R.string.data_error_may_have_been_sent), id)
        }
        for (due in store.drafts.dueSends(ctx.account.id, store.now())) {
            if (!store.drafts.markSending(due.id)) continue
            send(ctx, store.drafts.get(due.id) ?: continue)
            ctx.progress()
        }
    }

    private suspend fun send(ctx: OpContext, draft: Draft) {
        val store = ctx.store
        val traits = ctx.account.traits
        // The server would send it, but from an address that the user did not set up for this account.
        val own = store.accounts.identities(ctx.account.id).map { it.email } + ctx.account.email
        if (own.none { it.equals(draft.fromEmail, ignoreCase = true) }) {
            return fail(ctx, draft, store.text(R.string.ops_error_unknown_sender, draft.fromEmail))
        }
        val date = store.now()
        val message: ByteArray
        val sentCopy: ByteArray?
        try {
            val mime = DraftMime.builder(store, ctx.account, draft, strict = true, date = date)
            message = mime.build(includeBcc = false)
            // Riseup keeps no copy of submitted mail; the copy records the Bcc recipients (protocol PS27).
            sentCopy = if (traits.clientSavesSent) mime.build(includeBcc = true) else null
        } catch (_: IllegalArgumentException) {
            return fail(ctx, draft, store.text(R.string.ops_error_invalid_address))
        } catch (_: IOException) {
            return fail(ctx, draft, store.text(R.string.ops_error_attachment_missing))
        }
        val recipients = DraftMime.recipients(draft)
        if (recipients.isEmpty()) return fail(ctx, draft, store.text(R.string.ops_error_no_recipient))
        if (message.size > traits.maxMessageBytes) {
            return fail(ctx, draft, store.text(R.string.ops_error_too_large, Ops.formatSize(store, traits.maxMessageBytes)))
        }
        try {
            store.files.outboxMessage(draft.id).writeBytes(message)
            val smtp = ctx.smtp()
            try {
                val limit = minOf(smtp.maxSize ?: Long.MAX_VALUE, traits.maxMessageBytes)
                if (message.size > limit) {
                    return fail(ctx, draft, store.text(R.string.ops_error_too_large, Ops.formatSize(store, limit)))
                }
                // One blocking write: the lock must last for the whole upload.
                ctx.progress(uploadMs(message.size))
                smtp.send(draft.fromEmail, recipients, message)
            } finally {
                smtp.quit()
            }
        } catch (e: CancellationException) {
            store.drafts.retryLater(draft.id, draft.attempts, store.now(), draft.error)
            throw e
        } catch (_: IllegalArgumentException) {
            // SmtpClient checks every address before it writes a byte.
            return fail(ctx, draft, store.text(R.string.ops_error_invalid_address))
        } catch (e: SmtpRecipientException) {
            return fail(ctx, draft, store.text(R.string.ops_error_send_rejected, e.rejected.keys.joinToString(", ")))
        } catch (_: SmtpUncertainException) {
            return fail(ctx, draft, store.text(R.string.data_error_may_have_been_sent), uncertain = true)
        } catch (e: SmtpException) {
            return if (e.transient) retry(ctx, draft, e) else refused(ctx, draft, e)
        } catch (e: MailError.AuthRequired) {
            // Not this message's fault: it waits, unchanged, until the user signs in again.
            store.drafts.retryLater(draft.id, draft.attempts, store.now() + OpErrors.backoffMs(1), draft.error)
            throw e
        } catch (e: MailError) {
            return if (OpErrors.classify(e) == Failure.TRANSIENT) retry(ctx, draft, e) else refused(ctx, draft, e)
        } catch (e: IOException) {
            return retry(ctx, draft, e)
        }
        sent(ctx, draft, sentCopy)
    }

    /** The final 250: one transaction records the send and queues the follow-ups (DESIGN §7.5). */
    private fun sent(ctx: OpContext, draft: Draft, sentCopy: ByteArray?) {
        val store = ctx.store
        val accountId = ctx.account.id
        val copy = sentCopy?.let { bytes ->
            try {
                store.files.newOutboxFile(draft.id, SENT_FILE).also { it.writeBytes(bytes) }
            } catch (e: IOException) {
                Ops.report(accountId, store.text(R.string.ops_error_sent_not_saved, OpErrors.text(e)))
                null
            }
        }
        val now = store.now()
        val recorded = store.db.tx(Tables.DRAFT, Tables.OP, Tables.MESSAGE, Tables.CONTACT) {
            // The row is gone (the account was removed during the send): nothing to follow up.
            if (!store.drafts.markSent(draft.id)) return@tx false
            // Revisions that were never uploaded are not needed any more.
            Drafts.dropUploads(store, accountId, draft.id)
            if (copy != null) {
                store.ops.insert(OpArg.Append(draft.id, FolderRole.SENT, copy.name).toNewOp(accountId, now))
            }
            if (store.drafts.get(draft.id)?.onServer == true) Drafts.queueDelete(store, accountId, draft.messageId)
            draft.sourceMessage?.let(store.messages::get)?.let { source ->
                store.messages.setAnswered(listOf(source.id), true)
                store.ops.insert(OpArg.Flags(ANSWERED, true).toNewOp(source.accountId, now, listOf(source.id)))
            }
            store.contacts.touch(DraftMime.addresses(draft), now)
            true
        }
        // The files of a removed account must not stay (the Sent copy has the Bcc recipients).
        if (!recorded) return store.files.deleteOutbox(draft.id)
        cleanup(store, draft.id)
    }

    /** Runs an [OpArg.Append] of a Sent copy (Riseup only). */
    internal fun uploadSentCopy(ctx: OpContext, arg: OpArg.Append) {
        val store = ctx.store
        val name = arg.file ?: return
        val file = Files.inside(store.files.outboxDir(arg.draftId), name)
        if (!file.isFile) return
        val bytes = file.readBytes()
        Ops.checkAppendSize(ctx, bytes.size)
        val path = store.mailbox(ctx.account.id, FolderRole.SENT)?.path ?: DEFAULT_SENT
        ctx.progress(uploadMs(bytes.size))
        Ops.creating(ctx, path, FolderRole.SENT) { ctx.client.append(path, listOf(SEEN), bytes) }
    }

    /** Deletes a sent draft and its outbox dir once no op needs them any more. */
    internal fun cleanup(store: OpStore, draftId: Long) {
        val draft = store.drafts.get(draftId) ?: return
        if (draft.state != DraftState.SENT) return
        val needed = store.ops.byAccount(draft.accountId).any {
            (OpArg.decode(it.kind, it.arg) as? OpArg.Append)?.draftId == draftId
        }
        if (needed) return
        store.drafts.delete(draftId)
        store.files.deleteOutbox(draftId)
    }

    /** A transient failure: back off, or give up after [OpErrors.MAX_SEND_ATTEMPTS] tries. */
    private fun retry(ctx: OpContext, draft: Draft, e: Exception) {
        val store = ctx.store
        val attempts = draft.attempts + 1
        if (attempts >= OpErrors.MAX_SEND_ATTEMPTS) {
            fail(ctx, draft, store.text(R.string.ops_error_send_gave_up, OpErrors.text(e)))
        } else {
            store.drafts.retryLater(draft.id, attempts, store.now() + OpErrors.backoffMs(attempts), OpErrors.text(e))
        }
    }

    private fun refused(ctx: OpContext, draft: Draft, e: Exception) =
        fail(ctx, draft, ctx.store.text(R.string.ops_error_send_refused, OpErrors.text(e)))

    private fun fail(ctx: OpContext, draft: Draft, text: String, uncertain: Boolean = false) {
        ctx.store.drafts.markFailed(draft.id, text, uncertain)
        Ops.report(ctx.account.id, text, draft.id)
    }

    private const val SENT_FILE = "sent.eml"
    private const val DEFAULT_SENT = "Sent"
    private const val SEEN = "\\Seen"
    private const val ANSWERED = "\\Answered"
}
