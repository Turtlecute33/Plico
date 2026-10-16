package app.plico.notify

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.plico.ui.nav.MessageLink

/**
 * The tap of a "Sign-in needed" alert: like [MessageLink], an explicit intent to the non-exported alias
 * (privacy PS15) with the data `plico:reauth/<accountId>`. MainActivity opens `Route.Reauth` for it.
 */
object ReauthLink {
    private const val PREFIX = "plico:reauth/"

    /** `plico:reauth/<accountId>`. */
    fun uri(accountId: Long): String = PREFIX + accountId

    /** The account id when [data] is exactly `plico:reauth/<positive id>`, else null. */
    fun parse(data: String?): Long? {
        if (data == null || !data.startsWith(PREFIX)) return null
        val digits = data.substring(PREFIX.length)
        if (digits.length !in 1..18 || !digits.all { it in '0'..'9' }) return null
        return digits.toLong().takeIf { it > 0 }
    }

    /** The tap intent of a "Sign-in needed" alert; unique per account (apis AF6). */
    @SuppressLint("UseKtx") // core-ktx is not a dependency (DESIGN §2)
    fun intent(context: Context, accountId: Long): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri(accountId)))
        .setClassName(context, MessageLink.ALIAS)
        .setIdentifier("acct:$accountId:reauth")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
