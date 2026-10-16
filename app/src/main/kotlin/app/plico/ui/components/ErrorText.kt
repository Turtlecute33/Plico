package app.plico.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * A user-facing error (DESIGN §14): a string resource, the server's own text when it has one, and whether
 * "Sign in" can fix it. Each screen maps errors to its own texts: the mailbox for actions, the message for loads.
 */
data class ErrorText(@param:StringRes val res: Int, val detail: String? = null, val signIn: Boolean = false)

/** The text, with [ErrorText.detail] in its placeholder when it has one. */
fun ErrorText.text(resources: Resources): String =
    if (detail == null) resources.getString(res) else resources.getString(res, detail)

@Composable
fun ErrorText.text(): String = text(LocalResources.current)
