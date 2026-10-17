package app.plico

import android.app.Application
import android.content.Context
import app.plico.account.BrowserOAuth
import app.plico.data.AccountDao
import app.plico.data.ContactDao
import app.plico.data.Db
import app.plico.data.DraftDao
import app.plico.data.Files
import app.plico.data.FolderDao
import app.plico.data.MessageDao
import app.plico.data.OpDao
import app.plico.data.Prefs
import app.plico.data.TrustDao
import app.plico.data.Vault
import app.plico.html.SenderLogos
import app.plico.notify.Notifier
import app.plico.push.Power
import app.plico.push.PushEngine
import app.plico.push.policy.LearnedIntervals
import app.plico.sync.MailActions
import app.plico.sync.Sender
import app.plico.sync.Sessions
import app.plico.ui.lock.AppLock
import kotlin.concurrent.thread

/** Holds the app's singletons. The UI and the push service share them (one process). */
class PlicoApp : Application() {
    val db by lazy { Db(this) }
    val prefs by lazy { Prefs(this) }
    val vault by lazy { Vault() }
    val files by lazy { Files(this) }
    val accounts by lazy { AccountDao(db) }
    val folders by lazy { FolderDao(db) }
    val messages by lazy { MessageDao(db) }
    val ops by lazy { OpDao(db) }
    val drafts by lazy { DraftDao(db) }
    val contacts by lazy { ContactDao(db) }
    val trustedSenders by lazy { TrustDao(db) }

    val power by lazy { Power(this) }
    val notifier by lazy { Notifier(this) }

    /** Foreground service, alarm, network watcher; the host of every session. */
    val push by lazy { PushEngine(this) }
    val sessions by lazy { Sessions(this) }

    /** The UI's single entry point for changes. */
    val mail by lazy { MailActions(this) }

    /** The UI lock; notifications also read it to hide their content. */
    val appLock by lazy { AppLock(prefs) }

    /** Keepalive intervals learned per provider and network, shared by all sessions. */
    val learnedIntervals by lazy { LearnedIntervals(prefs) }

    /** Sender logos, only when the user turned them on (DESIGN §12). */
    val logos by lazy { SenderLogos(this) }

    override fun onCreate() {
        super.onCreate()
        // On the main thread, so that it cannot race with a sign-in that the UI starts at once.
        BrowserOAuth.dropStale(prefs)
        notifier.watchSendFailures()
        thread(name = "start-cleanup") {
            // DESIGN §9: shown notifications follow "Hide content" and the app lock also while push is paused.
            notifier.start()
            // DESIGN §7.5: interrupted sends become failed before the first send of this process.
            Sender.onProcessStart(this)
            // DESIGN §11.5: cached attachment files older than 1 h are deleted at start.
            files.pruneAttachmentCache()
            // DESIGN §12: expired logos go; all of them when logos are off or no account is left.
            logos.prune(prefs.senderLogos, accounts.all().isNotEmpty())
        }
    }
}

val Context.app: PlicoApp get() = applicationContext as PlicoApp
