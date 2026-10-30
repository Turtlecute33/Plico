package app.plico

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import app.plico.data.MailView
import app.plico.sync.MailActions
import app.plico.ui.compose.ComposeIntents
import app.plico.ui.lock.LockScreen
import app.plico.ui.nav.AppNav
import app.plico.ui.nav.Entry
import app.plico.ui.nav.Navigator
import app.plico.ui.nav.Route
import app.plico.ui.setup.OAuthResume
import app.plico.ui.theme.PlicoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only activity (singleTop). It opens Setup or the unified inbox, routes entry intents (DESIGN §9,
 * §11.6, privacy PS15), keeps the window private (§12) and reports visibility to the app lock, which
 * covers every route.
 */
class MainActivity : ComponentActivity() {
    private val nav: Navigator by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyWindowPrivacy()
        lifecycleScope.launch { app.prefs.changes().collect { applyWindowPrivacy() } }

        // A new Navigator after process death starts again from the mailbox, so the saved state of the old
        // screens must not come back: a dialog that was open would open again with no tap.
        val oldScreens = savedInstanceState != null && nav.backStack.isEmpty()
        nav.start(app)
        // After a configuration change or process death this intent was handled already.
        if (savedInstanceState == null) Entry.of(intent)?.let { nav.open(app, it, intent, fresh = true) }
        keepSplashUntilStarted()

        setContent {
            PlicoTheme {
                val locked by app.appLock.locked.collectAsStateWithLifecycle()
                // Keeps the screens' saved state while the lock removes them from the composition.
                val screens = rememberSaveableStateHolder()
                remember { oldScreens.also { if (it) screens.removeState("screens") } }
                Surface(Modifier.fillMaxSize()) {
                    if (locked) {
                        LockScreen(onUnlocked = app.appLock::unlock)
                    } else {
                        screens.SaveableStateProvider("screens") { AppNav(nav) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Entry.of(intent)?.let { nav.open(app, it, intent, fresh = false) }
    }

    override fun onStart() {
        app.appLock.onForeground()
        super.onStart()
        // The undo windows follow the Android "Time to take action" setting (DESIGN §7.4, PLICO-UX-008).
        MailActions.actionTimeoutMs = getSystemService(AccessibilityManager::class.java)
            ?.getRecommendedTimeoutMillis(0, FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS)?.toLong() ?: 0L
        // DESIGN §8.2/§8.3: grants are read again, push starts, and the visible UI is a coalescing point.
        app.push.onAppVisible(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            app.appLock.onBackground()
            app.push.onAppVisible(false)
        }
    }

    private fun applyWindowPrivacy() {
        val prefs = app.prefs
        // With the app lock on, a Recents thumbnail would show what the lock hides.
        setRecentsScreenshotEnabled(!prefs.hideInRecents && !prefs.appLock)
        if (prefs.blockScreenshots) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Holds the system splash screen until the first route is known, so that no empty frame shows. */
    private fun keepSplashUntilStarted() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (nav.backStack.isEmpty()) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }
}

// The helpers below run in the Navigator's scope, which outlives a rotation; they hold no activity.

/** The first route: Setup without accounts, else the unified inbox. */
private fun Navigator.start(app: PlicoApp) = startWith {
    val empty = withContext(Dispatchers.IO) { app.accounts.all().isEmpty() }
    if (empty) Route.Setup else Route.Mailbox(MailView.Unified)
}

/**
 * Acts on a trusted [entry] once the first route is set. [fresh] = this intent started the activity:
 * a shared draft is then the only screen, so that Back returns to the sharing app.
 */
private fun Navigator.open(app: PlicoApp, entry: Entry, intent: Intent, fresh: Boolean) {
    viewModelScope.launch {
        snapshotFlow { backStack.isNotEmpty() }.first { it }
        when (entry) {
            is Entry.OpenMessage -> go(Route.Message(entry.messageId))
            is Entry.Reauth -> go(Route.Reauth(entry.accountId))
            Entry.Compose -> {
                val draftId = ComposeIntents.draftFromIntent(app, intent) ?: return@launch
                val route = Route.Compose(draftId, fresh = ComposeIntents.isMailto(intent.action))
                if (fresh) reset(route) else go(route)
            }
            Entry.OAuthDone -> OAuthResume.onResult(app)?.let(::go)
        }
    }
}
