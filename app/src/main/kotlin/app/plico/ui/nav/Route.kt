package app.plico.ui.nav

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.plico.data.MailView

/** Screens. The back stack lives in [Navigator] (a ViewModel); it is not saved across process death. */
sealed interface Route {
    data object Setup : Route

    /**
     * Sign in again to an existing account (Settings, "Sign-in needed" notification). [editServers]: start on the
     * server settings ("Change server settings" of an account on other servers).
     */
    data class Reauth(val accountId: Long, val editServers: Boolean = false) : Route
    data class Mailbox(val view: MailView) : Route
    data class Message(val id: Long) : Route
    /**
     * [fresh]: a draft made for this screen (reply, forward, a mailto link, a new message). Leaving it without an
     * edit deletes it; other drafts (shared, from the Drafts view or the server) stay as they are.
     */
    data class Compose(val draftId: Long, val fresh: Boolean = false) : Route

    /** Local drafts of an account without a Drafts mailbox (Riseup creates it on the first upload). */
    data class Drafts(val accountId: Long) : Route
    data object Settings : Route
    data class AccountSettings(val accountId: Long) : Route
    data object About : Route
}

/**
 * Notification taps (DESIGN §9, privacy PS15): an explicit intent to the non-exported activity-alias
 * `.OpenMessage` with the data `plico:message/<id>`. MainActivity opens a message only from this alias.
 */
object MessageLink {
    private const val PREFIX = "plico:message/"

    /** Class name of the alias. It comes from the namespace, so debug builds use the same name. */
    const val ALIAS = "app.plico.OpenMessage"

    fun uri(messageId: Long): String = PREFIX + messageId

    /** The message id when [data] is exactly `plico:message/<positive id>`, else null. */
    fun parse(data: String?): Long? {
        if (data == null || !data.startsWith(PREFIX)) return null
        val digits = data.substring(PREFIX.length)
        if (digits.length !in 1..18 || !digits.all { it in '0'..'9' }) return null
        return digits.toLong().takeIf { it > 0 }
    }

    /** The tap intent for a message. Data and identifier make each message's PendingIntent unique (apis AF6). */
    @SuppressLint("UseKtx") // core-ktx is not a dependency (DESIGN §2)
    fun intent(context: Context, messageId: Long): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri(messageId)))
        .setClassName(context, ALIAS)
        .setIdentifier("msg:$messageId:open")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
