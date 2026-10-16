package app.plico

import android.app.Application
import android.content.Context
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
import app.plico.push.policy.KeyValueStore
import app.plico.push.policy.LearnedIntervals
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

    /** The UI lock; notifications also read it to hide their content. */
    val appLock by lazy { AppLock(prefs) }

    /** Keepalive intervals learned per provider and network, shared by all sessions. */
    val learnedIntervals by lazy {
        LearnedIntervals(object : KeyValueStore {
            override fun get(key: String) = prefs.getString(key)
            override fun put(key: String, value: String?) = prefs.putString(key, value)
        })
    }

    override fun onCreate() {
        super.onCreate()
        // DESIGN §11.5: cached attachment files older than 1 h are deleted at start.
        thread(name = "prune-attachments") { files.pruneAttachmentCache() }
    }
}

val Context.app: PlicoApp get() = applicationContext as PlicoApp
