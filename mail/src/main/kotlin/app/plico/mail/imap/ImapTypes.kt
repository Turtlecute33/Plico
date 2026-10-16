package app.plico.mail.imap

/** One LIST entry. [name] is decoded from modified UTF-7. [attributes] keep their backslash, e.g. `\Sent`. */
data class MailboxInfo(val name: String, val delimiter: String?, val attributes: Set<String>)

data class SelectResult(
    val exists: Long,
    val uidValidity: Long,
    val uidNext: Long,
    val highestModSeq: Long?,
    val noModSeq: Boolean,
)

data class MailboxStatus(
    val messages: Long,
    val uidNext: Long,
    val uidValidity: Long,
    val highestModSeq: Long?,
)

/**
 * One FETCH response. Only the items that were asked for are set. [bodyStructure] is the raw
 * BODYSTRUCTURE value (parse it with `app.plico.mail.mime.parseBodyStructure`). [sections] is keyed by
 * the section spec as the server echoed it without the `BODY` prefix, e.g. `HEADER.FIELDS (FROM TO)`,
 * `1.2`, `1.2<0>`, `HEADER`, `` (empty = whole message).
 */
data class FetchItem(
    val seq: Long,
    val uid: Long,
    val flags: Set<String>?,
    val modSeq: Long?,
    val internalDate: Long?,
    val size: Long?,
    val gmMsgId: Long?,
    val gmLabels: List<String>?,
    val bodyStructure: ImapValue?,
    val sections: Map<String, ByteArray>,
)

data class Quota(val usedKiB: Long, val limitKiB: Long)

sealed interface IdleEvent {
    /** EXISTS, EXPUNGE, FETCH or RECENT: something changed in the mailbox. */
    data object Changed : IdleEvent

    /** Untagged OK without a response code, e.g. Dovecot `* OK Still here`. */
    data object Keepalive : IdleEvent
}

interface IdleHooks {
    /** Called just before the reader blocks waiting for server data. The app releases its wake lock. */
    fun beforeBlockingRead() {}

    /** Called after each server response was read. The app renews its wake lock. */
    fun afterRead() {}

    /** True when the app has work pending; IDLE then ends as soon as the server allows. */
    fun stopRequested(): Boolean = false
}
