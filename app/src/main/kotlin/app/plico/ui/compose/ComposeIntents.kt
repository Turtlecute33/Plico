package app.plico.ui.compose

import android.content.Intent
import app.plico.PlicoApp

/** Turns a share or mailto intent from another app into a local draft (DESIGN §11.6). Placeholder. */
object ComposeIntents {
    /** The new draft id, or null when the intent has nothing usable or no account exists. */
    suspend fun draftFromIntent(app: PlicoApp, intent: Intent): Long? = null
}
