package app.plico.account

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.plico.R
import app.plico.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Google account on this phone" through AccountManager (DESIGN.md §6, apis AF5, privacy PS10). Google
 * gives tokens only when the user's Cloud project has an Android OAuth client for this package name and
 * signing SHA-1, so the method is offered only after the user entered a client ID.
 */
object DeviceGoogleAuth {
    const val ACCOUNT_TYPE = "com.google"
    private const val TOKEN_TYPE = "oauth2:" + Providers.GMAIL_SCOPE
    private const val UNREGISTERED = "UNREGISTERED_ON_API_CONSOLE"
    private const val TIMEOUT_MS = 60_000L

    /** True when a `com.google` authenticator exists and the user set a Google OAuth client ID. */
    fun isAvailable(context: Context): Boolean =
        context.app.prefs.googleClientId.isNotBlank() &&
            AccountManager.get(context).authenticatorTypes.any { it.type == ACCOUNT_TYPE }

    /** System account chooser. Picking an account grants visibility; no GET_ACCOUNTS permission is needed. */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf(ACCOUNT_TYPE), null, null, null, null)

    /** The account name from the chooser result, or null when the user cancelled. */
    fun accountName(result: Intent?): String? =
        result?.takeIf { it.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) == ACCOUNT_TYPE }
            ?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)

    /**
     * Gets the first token with the Activity overload, so that Google's consent screen shows during setup.
     * Throws [MailError.Setup] when the app is not registered and [MailError.AuthRequired] when the user
     * declines.
     */
    suspend fun consent(activity: Activity, accountName: String) {
        val manager = AccountManager.get(activity)
        token(activity) { callback ->
            manager.getAuthToken(Account(accountName, ACCOUNT_TYPE), TOKEN_TYPE, null, activity, callback, null)
        }
    }

    /** Background token without UI. A needed consent comes back as [MailError.AuthRequired] with its intent. */
    internal suspend fun backgroundToken(context: Context, accountName: String): String {
        val manager = AccountManager.get(context)
        return withTimeoutOrNull(TIMEOUT_MS) {
            token(context) { callback ->
                manager.getAuthToken(Account(accountName, ACCOUNT_TYPE), TOKEN_TYPE, null, false, callback, null)
            }
        } ?: throw SocketTimeoutException("No token from AccountManager")
    }

    internal suspend fun clearToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        AccountManager.get(context).invalidateAuthToken(ACCOUNT_TYPE, token)
    }

    // The callback runs when the future is done, so reading its result never blocks.
    private suspend fun token(
        context: Context,
        request: (AccountManagerCallback<Bundle>) -> AccountManagerFuture<Bundle>,
    ): String {
        val result = suspendCancellableCoroutine { continuation ->
            val callback = AccountManagerCallback<Bundle> { done ->
                try {
                    continuation.resume(done.result)
                } catch (e: Exception) {
                    continuation.resumeWithException(failure(context, e))
                }
            }
            try {
                val future = request(callback)
                continuation.invokeOnCancellation { future.cancel(true) }
            } catch (e: RuntimeException) {
                // A missing account visibility comes back as a SecurityException from the call itself.
                continuation.resumeWithException(failure(context, e))
            }
        }
        result.getString(AccountManager.KEY_AUTHTOKEN)?.let { return it }
        throw MailError.AuthRequired(
            context.getString(R.string.account_error_device_consent),
            intent = result.getParcelable(AccountManager.KEY_INTENT, Intent::class.java),
        )
    }

    private fun failure(context: Context, e: Exception): Exception = when (e) {
        is AuthenticatorException ->
            if (e.message.orEmpty().contains(UNREGISTERED)) {
                MailError.Setup(context.getString(R.string.account_error_device_unregistered))
            } else {
                MailError.AuthRequired(context.getString(R.string.account_error_device_sign_in), cause = e)
            }
        // Cancelled consent, or the account is gone or no longer visible to Plico.
        is OperationCanceledException, is SecurityException, is IllegalArgumentException ->
            MailError.AuthRequired(context.getString(R.string.account_error_device_sign_in), cause = e)
        else -> e // IOException: network, classified by the caller
    }
}
