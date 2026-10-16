package app.plico.sync

import app.plico.data.Account
import app.plico.data.Draft
import app.plico.data.Files
import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses
import app.plico.mail.mime.MessageBuilder
import app.plico.mail.mime.OutgoingAttachment
import java.io.File

/** Turns a draft row and its outbox files into an RFC 5322 message (DESIGN.md §10). */
internal object DraftMime {
    /** Header and base64 overhead used for the size estimate. */
    private const val OVERHEAD = 4_096L

    /** The builder for [draft] from [account], reading the attachment files of its outbox dir. */
    fun builder(store: OpStore, account: Account, draft: Draft, strict: Boolean, date: Long): MessageBuilder =
        builder(draft, senderName(store, account, draft.fromEmail), store.files.outboxDir(draft.id), strict, date)

    /**
     * The builder for [draft]. With [strict] off (a draft upload), addresses that are not valid yet are left
     * out instead of failing; the local draft keeps them.
     *
     * @throws java.io.IOException when an attachment file cannot be read.
     */
    fun builder(draft: Draft, fromName: String?, dir: File, strict: Boolean, date: Long): MessageBuilder {
        fun list(value: String) = Addresses.parseList(value).filter { strict || Addresses.isValidAddrSpec(it.email) }
        return MessageBuilder(
            from = Address(fromName?.takeIf { it.isNotBlank() }, draft.fromEmail),
            to = list(draft.toList),
            cc = list(draft.ccList),
            bcc = list(draft.bccList),
            subject = draft.subject,
            text = draft.body,
            messageId = draft.messageId,
            inReplyTo = draft.inReplyTo,
            references = listOfNotNull(draft.refs),
            attachments = draft.attachments.map { a ->
                OutgoingAttachment(a.name, a.mime, Files.inside(dir, a.file).readBytes())
            },
            date = date,
        )
    }

    /** To, Cc and Bcc of [draft], each address once. */
    fun addresses(draft: Draft): List<Address> = listOf(draft.toList, draft.ccList, draft.bccList)
        .flatMap(Addresses::parseList)
        .distinctBy { it.email.lowercase() }

    /** The SMTP envelope recipients of [draft]. */
    fun recipients(draft: Draft): List<String> = addresses(draft).map { it.email }

    /**
     * Size on the wire of a message with [rawBytes] of subject, body and attachment files: (raw + headers) × 1.37
     * (base64). The one estimate of the compose warning and of Send (DESIGN §13).
     */
    fun estimatedSize(rawBytes: Long): Long = (OVERHEAD + rawBytes) * 137 / 100

    /** Subject, body and attachment bytes of [draft], for [estimatedSize]. */
    fun rawSize(draft: Draft): Long = listOf(draft.subject, draft.body).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
        draft.attachments.sumOf { it.size }

    /** The display name for [email]: its identity's name, else the account's sender name. */
    fun senderName(store: OpStore, account: Account, email: String): String? {
        val identity = store.accounts.identities(account.id).firstOrNull { it.email.equals(email, ignoreCase = true) }
        return identity?.name?.takeIf { it.isNotBlank() } ?: account.senderName.takeIf { it.isNotBlank() }
    }
}
