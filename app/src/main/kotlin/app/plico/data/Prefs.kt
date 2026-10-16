package app.plico.data

import android.content.Context
import android.content.SharedPreferences
import app.plico.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Non-secret settings (SharedPreferences). Defaults follow DESIGN.md §5 and §12. */
class Prefs(context: Context) {
    enum class Swipe { ARCHIVE, DELETE, READ, STAR, NONE }

    private val sp: SharedPreferences = context.getSharedPreferences("plico", Context.MODE_PRIVATE)

    /** Swipe from start to end. */
    var swipeStart: Swipe by swipe("swipe_start", Swipe.ARCHIVE)

    /** Swipe from end to start. */
    var swipeEnd: Swipe by swipe("swipe_end", Swipe.DELETE)

    /** 0 = undo send off. Default 5. */
    var undoSendSeconds: Int by int("undo_send_seconds", 5)

    /** Default false: remote content blocked. */
    var remoteContentDefault: Boolean by bool("remote_content_default", false)
    var confirmLinks: Boolean by bool("confirm_links", true)
    var darkEmail: Boolean by bool("dark_email", true)
    var appLock: Boolean by bool("app_lock", false)

    /** Minutes; 0 = lock at once. */
    var appLockTimeoutMinutes: Int by int("app_lock_timeout_minutes", 0)
    var hideInRecents: Boolean by bool("hide_in_recents", true)
    var blockScreenshots: Boolean by bool("block_screenshots", false)
    var incognitoKeyboard: Boolean by bool("incognito_keyboard", true)
    var hideNotificationContent: Boolean by bool("hide_notification_content", false)

    /** The user's Google OAuth client ID; empty = OAuth methods hidden. Debug builds preset it from BuildConfig. */
    var googleClientId: String
        get() = sp.getString(GOOGLE_CLIENT_ID, null) ?: if (BuildConfig.DEBUG) BuildConfig.GOOGLE_CLIENT_ID else ""
        set(value) = edit { putString(GOOGLE_CLIENT_ID, value.trim()) }

    /** Paused by the user in the app; StartReceiver is disabled. */
    var pushPaused: Boolean by bool("push_paused", false)

    /** Stopped from the Task Manager; a reboot or the Resume action clears it. */
    var pushStoppedBySystemUi: Boolean by bool("push_stopped_by_system_ui", false)
    var gmailIpNoteShown: Boolean by bool("gmail_ip_note_shown", false)

    /** Raw string storage for components that own their own format (e.g. learned keepalive intervals). */
    fun getString(key: String): String? = sp.getString(key, null)

    /** Stores [value]; null removes the key. */
    fun putString(key: String, value: String?): Unit = edit { if (value == null) remove(key) else putString(key, value) }

    /** Emits once now and after every change of any setting. */
    fun changes(): Flow<Unit> = callbackFlow {
        // SharedPreferences keeps listeners weakly; this flow holds the strong reference while collected.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        sp.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    private fun edit(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    private fun bool(key: String, default: Boolean) = pref({ sp.getBoolean(key, default) }) { edit { putBoolean(key, it) } }

    private fun int(key: String, default: Int) = pref({ sp.getInt(key, default) }) { edit { putInt(key, it) } }

    private fun swipe(key: String, default: Swipe) = pref(
        { sp.getString(key, null)?.let { name -> Swipe.entries.firstOrNull { it.name == name } } ?: default },
    ) { edit { putString(key, it.name) } }

    private fun <T> pref(get: () -> T, set: (T) -> Unit) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)
    }

    private companion object {
        const val GOOGLE_CLIENT_ID = "google_client_id"
    }
}
