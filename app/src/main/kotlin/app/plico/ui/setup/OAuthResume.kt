package app.plico.ui.setup

import app.plico.PlicoApp
import app.plico.account.BrowserOAuth
import app.plico.account.OAuthIssuer
import app.plico.data.Account
import app.plico.data.AuthKind
import app.plico.ui.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Continues browser OAuth after OAuthRedirectActivity stored the result in Prefs (DESIGN §6). The setup
 * screen that waits for the browser listens to [signals] and then calls BrowserOAuth.completePending().
 */
object OAuthResume {
    private val results = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits each time the browser came back with a result. */
    internal val signals: SharedFlow<Unit> = results.asSharedFlow()

    /**
     * Called by MainActivity for the action `app.plico.action.OAUTH_DONE`; reads nothing from the intent.
     * Wakes the setup screen that waits for the browser. When no setup screen is open (Android stopped
     * Plico while the browser was in front), returns the route that continues the sign-in, else null.
     */
    suspend fun onResult(app: PlicoApp): Route? {
        if (results.subscriptionCount.value > 0) {
            results.emit(Unit)
            return null
        }
        val pending = pendingResult(app) ?: return null
        return routeFor(withContext(Dispatchers.IO) { app.accounts.byEmail(pending.email) }, pending.issuer)
    }

    /** True when the browser came back and the sign-in waits to be completed. */
    internal fun hasResult(app: PlicoApp): Boolean = pendingResult(app) != null

    /**
     * "Sign in again" when the login hint was an OAuth account of Plico that signs in with [issuer] (the issuer of
     * the pending sign-in), else setup.
     */
    internal fun routeFor(account: Account?, issuer: OAuthIssuer = OAuthIssuer.GOOGLE): Route =
        if (account?.auth == AuthKind.OAUTH && OAuthIssuer.of(account.provider) == issuer) {
            Route.Reauth(account.id)
        } else {
            Route.Setup
        }

    /** A sign-in whose redirect arrived (its login hint is "" without a hint), or null. */
    private fun pendingResult(app: PlicoApp): BrowserOAuth.Pending? = BrowserOAuth.pending(app.prefs)?.takeIf { it.done }
}
