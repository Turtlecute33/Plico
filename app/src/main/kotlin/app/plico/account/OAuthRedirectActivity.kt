package app.plico.account

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.plico.MainActivity
import app.plico.app

/**
 * Receives the Google OAuth redirect `${applicationId}:/oauth2redirect` (DESIGN.md §6). It is exported, so it
 * trusts nothing but a URI that matches the pending sign-in; [GoogleOAuth.onRedirect] stores the result and
 * setup continues in MainActivity.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (uri != null && GoogleOAuth(app).onRedirect(uri)) {
            // NEW_TASK: the browser may start this activity in its own task; MainActivity is in Plico's task.
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(Intent(this, MainActivity::class.java).setAction(GoogleOAuth.ACTION_DONE).addFlags(flags))
        }
        finish()
    }
}
