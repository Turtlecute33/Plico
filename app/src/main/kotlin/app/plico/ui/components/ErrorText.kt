package app.plico.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.plico.R
import app.plico.account.TlsReason

/**
 * A user-facing error (DESIGN §14): a string resource, the server's own text when it has one, and whether
 * "Sign in" can fix it. Each screen maps errors to its own texts: the mailbox for actions, the message for loads.
 */
data class ErrorText(@param:StringRes val res: Int, val detail: String? = null, val signIn: Boolean = false)

/** The cause of a TLS problem, for the mailbox banner and the account settings (PLICO-UX-009). */
@StringRes
fun tlsCause(reason: TlsReason): Int = when (reason) {
    TlsReason.EXPIRED -> R.string.ui_tls_expired
    TlsReason.NOT_YET_VALID -> R.string.ui_tls_not_yet_valid
    TlsReason.NAME_MISMATCH -> R.string.ui_tls_name
    TlsReason.UNTRUSTED -> R.string.ui_tls_untrusted
    TlsReason.NO_TLS -> R.string.ui_tls_no_tls
}

/** The text, with [ErrorText.detail] in its placeholder when it has one. */
fun ErrorText.text(resources: Resources): String =
    if (detail == null) resources.getString(res) else resources.getString(res, detail)

@Composable
fun ErrorText.text(): String = text(LocalResources.current)
