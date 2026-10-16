package app.plico.ui.setup

import app.plico.PlicoApp

/** Continues browser OAuth after OAuthRedirectActivity stored the result (DESIGN §6). Placeholder. */
object OAuthResume {
    /** Called by MainActivity for the action `app.plico.action.OAUTH_DONE`; reads nothing from the intent. */
    fun onResult(app: PlicoApp) {}
}
