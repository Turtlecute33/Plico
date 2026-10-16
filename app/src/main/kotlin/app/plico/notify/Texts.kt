package app.plico.notify

/** Pure text rules of the notifications (DESIGN §9, privacy PS11). */
internal object Texts {
    private val DEFAULT_LABEL = Regex("(Riseup|Gmail)( [0-9]+)?")

    /**
     * The account label for public (lock screen) versions: only a label the user set, and never one that
     * looks like an address. Null = no label.
     */
    fun publicLabel(label: String): String? =
        label.trim().takeIf { it.isNotEmpty() && !DEFAULT_LABEL.matches(it) && '@' !in it }

    /** Notifications show no content when the app lock is on or the user hid it (DESIGN §9). */
    fun hideContent(appLock: Boolean, hideNotificationContent: Boolean): Boolean = appLock || hideNotificationContent

    /** The sender as the private version shows it: the display name, else the address. */
    fun sender(fromName: String, fromEmail: String): String? =
        fromName.trim().ifEmpty { fromEmail.trim() }.ifEmpty { null }

    /** BigTextStyle text: the subject, then the snippet when known. */
    fun bigText(subject: String, snippet: String): String =
        if (snippet.isBlank()) subject else subject + "\n" + snippet.trim()

    fun childTag(messageId: Long): String = CHILD_PREFIX + messageId

    fun childId(tag: String?): Long? = tag?.removePrefix(CHILD_PREFIX)?.takeIf { it != tag }?.toLongOrNull()

    fun summaryTag(accountId: Long): String = "acct:$accountId"

    private const val CHILD_PREFIX = "msg:"
}
