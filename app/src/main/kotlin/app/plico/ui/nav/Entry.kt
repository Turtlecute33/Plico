package app.plico.ui.nav

import android.content.Intent
import app.plico.account.GoogleOAuth
import app.plico.notify.ReauthLink

/**
 * What an intent that starts MainActivity may do (DESIGN §11.6, privacy PS15). Other apps can only open
 * Compose; a message opens only through the non-exported alias; nothing else is read from the intent.
 */
sealed interface Entry {
    /** Notification tap through [MessageLink.ALIAS]. */
    data class OpenMessage(val messageId: Long) : Entry

    /** "Sign-in needed" notification tap through the same alias ([ReauthLink]). */
    data class Reauth(val accountId: Long) : Entry

    /** SEND, SEND_MULTIPLE, SENDTO mailto or VIEW mailto from another app. */
    data object Compose : Entry

    /** The browser OAuth redirect is done; its result waits in Prefs, not in the intent. */
    data object OAuthDone : Entry

    companion object {
        /** Sent by OAuthRedirectActivity to the running (singleTop) MainActivity. */
        const val ACTION_OAUTH_DONE = GoogleOAuth.ACTION_DONE

        /** The entry for [intent]; null for the launcher and for anything not trusted. */
        fun of(intent: Intent): Entry? = of(intent.action, intent.component?.className, intent.dataString, intent.flags)

        internal fun of(action: String?, className: String?, data: String?, flags: Int): Entry? {
            // Recents relaunches the task's first intent: a share or a tap that was handled already.
            if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            // Only MainActivity and the alias can receive this intent; other apps cannot target the alias.
            if (className?.endsWith(".OpenMessage") == true) {
                return MessageLink.parse(data)?.let(::OpenMessage) ?: ReauthLink.parse(data)?.let(::Reauth)
            }
            val mailto = data?.startsWith("mailto:", ignoreCase = true) == true
            return when (action) {
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> Compose
                Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> Compose.takeIf { mailto }
                ACTION_OAUTH_DONE -> OAuthDone
                else -> null
            }
        }
    }
}
