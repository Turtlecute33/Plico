package app.plico.data

import android.content.Context
import android.content.SharedPreferences
import app.plico.BuildConfig
import app.plico.push.policy.KeyValueStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Non-secret settings (SharedPreferences). Defaults follow DESIGN.md §5 and §12. As a [KeyValueStore] it also
 * holds raw strings for components that own their format (learned keepalive intervals, pending OAuth, notes).
 */
class Prefs(context: Context) : KeyValueStore {
    enum class Swipe { ARCHIVE, DELETE, READ, STAR, NONE }

    /** Where sender logos come from; each source gets only the sender's domain. */
    enum class LogoSource { DUCKDUCKGO, WEBSITE, GOOGLE }

    private val sp: SharedPreferences = context.getSharedPreferences("plico", Context.MODE_PRIVATE)

    /** Swipe from start to end. */
    var swipeStart: Swipe by enum("swipe_start", Swipe.ARCHIVE)

    /** Swipe from end to start. */
    var swipeEnd: Swipe by enum("swipe_end", Swipe.DELETE)

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

    /** Message list: unread rows get a dot and bold text, read rows lighter text. */
    var markUnread: Boolean by bool("mark_unread", true)

    /** Message list: unread rows get a colour per sender. */
    var avatarColors: Boolean by bool("avatar_colors", true)

    /** Message list: in lists of more than one account, a dot in the account colour on each avatar. */
    var accountDots: Boolean by bool("account_dots", true)

    /** Default false: no request for sender logos. */
    var senderLogos: Boolean by bool("sender_logos", false)
    var logoSource: LogoSource by enum("logo_source", LogoSource.DUCKDUCKGO)

    /** The user's Google OAuth client ID; empty = OAuth methods hidden. Debug builds preset it from BuildConfig. */
    var googleClientId: String
        get() = sp.getString(GOOGLE_CLIENT_ID, null) ?: if (BuildConfig.DEBUG) BuildConfig.GOOGLE_CLIENT_ID else ""
        set(value) = edit { putString(GOOGLE_CLIENT_ID, value.trim()) }

    /** The user's Microsoft (Entra) client ID; empty = Microsoft sign-in hidden. Debug builds preset it too. */
    var microsoftClientId: String
        get() = sp.getString(MICROSOFT_CLIENT_ID, null) ?: if (BuildConfig.DEBUG) BuildConfig.MICROSOFT_CLIENT_ID else ""
        set(value) = edit { putString(MICROSOFT_CLIENT_ID, value.trim()) }

    /** Paused by the user in the app; StartReceiver is disabled. */
    var pushPaused: Boolean by bool("push_paused", false)

    /** Stopped from the Task Manager; a reboot or the Resume action clears it. */
    var pushStoppedBySystemUi: Boolean by bool("push_stopped_by_system_ui", false)
    var gmailIpNoteShown: Boolean by bool("gmail_ip_note_shown", false)

    override fun get(key: String): String? = sp.getString(key, null)

    /** Stores [value]; null removes the key. */
    override fun put(key: String, value: String?): Unit = edit { if (value == null) remove(key) else putString(key, value) }

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

    /** Stored by name; an unknown name reads as [default]. */
    private inline fun <reified T : Enum<T>> enum(key: String, default: T) = pref(
        { sp.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default },
    ) { edit { putString(key, it.name) } }

    private fun <T> pref(get: () -> T, set: (T) -> Unit) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)
    }

    private companion object {
        const val GOOGLE_CLIENT_ID = "google_client_id"
        const val MICROSOFT_CLIENT_ID = "microsoft_client_id"
    }
}
