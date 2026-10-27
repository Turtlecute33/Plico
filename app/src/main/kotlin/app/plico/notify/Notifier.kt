package app.plico.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.plico.MainActivity
import app.plico.PlicoApp
import app.plico.R
import app.plico.account.MailError
import app.plico.data.Account
import app.plico.data.Message
import app.plico.data.MessageDao
import app.plico.data.Tables
import app.plico.notify.NotificationBook.Item
import app.plico.push.PushService
import app.plico.push.StartPolicy
import app.plico.sync.NotificationAction
import app.plico.ui.message.revealBidi
import app.plico.ui.nav.MessageLink
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * All notifications (DESIGN §9; push PB8, privacy PS11, apis AF6/AF7). New mail: per account one group
 * whose summary is posted first and alerts, then silent children for the 5 newest new messages. Later
 * changes (seen, archived, deleted, snippets) are silent and at most one per second per account. The
 * shown notifications follow the database: every change of the message tables re-checks them.
 */
class Notifier(private val app: PlicoApp) {
    private val nm = app.getSystemService(NotificationManager::class.java)
    // A failure in one task must not end the process; only the class name is logged (DESIGN §12).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(LOG_TAG, "notify: ${e.javaClass.simpleName}")
        },
    )

    // Guarded by lock.
    private val lock = Any()
    private val book = NotificationBook()
    private val noSnippet = HashSet<Long>()
    private val lastUpdate = HashMap<Long, Long>()
    private val dirty = HashMap<Long, Boolean>()
    private val jobs = HashMap<Long, Job>()
    private var started = false
    private var hidden = false

    @Volatile
    private var sharedChannelsReady = false

    /** Keeps the CPU awake while a batched update waits for its turn. */
    private val wakeLock by lazy { app.power.holder("notify") }

    private val openApp: PendingIntent by lazy {
        activity(
            Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Creates the shared channels and the new-mail channel of each account, or renames it after its label. */
    fun ensureChannels(accounts: List<Account>) {
        nm.createNotificationChannels(sharedChannels() + accounts.map(::mailChannelOf))
        sharedChannelsReady = true
    }

    /** Deletes the new-mail channels of accounts that are gone. */
    internal fun removeStaleChannels(keep: Set<Long>) {
        nm.notificationChannels
            .filter { it.id.startsWith(MAIL_PREFIX) && it.id.removePrefix(MAIL_PREFIX).toLongOrNull() !in keep }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    /** Rebuilds the state from the shown notifications after a process start and follows the database. */
    fun start(): Unit = synchronized(lock) { startLocked() }

    /**
     * New mail that passed NotifyRule: the summary first (it alerts), then up to 5 children. Nothing is
     * posted when the account's channel is off. Blocking (database): call it on Dispatchers.IO.
     */
    fun postNew(accountId: Long, messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        val account = app.accounts.get(accountId) ?: return
        if (!canPost(account)) return
        val live = app.messages.stillNotifiable(messageIds)
        val rows = Rows(app.messages)
        val items = messageIds.filter { it in live }.mapNotNull { rows[it] }.map { Item(it.id, accountId, it.date) }
        if (items.isEmpty()) return
        synchronized(lock) {
            startLocked()
            val now = SystemClock.elapsedRealtime()
            reconcileLocked(accountId, now)
            val batch = book.add(accountId, items, now)
            renderLocked(account, rows, now, alert = batch.alert, post = batch.post, cancel = batch.cancel)
        }
    }

    /** Re-checks the account's notifications: mail that is seen, archived or deleted goes. Batched, silent. */
    fun refresh(accountId: Long) {
        synchronized(lock) { startLocked() }
        schedule(accountId, rerender = false)
    }

    /** Cancels every notification of the account (account removed). */
    fun cancelAccount(accountId: Long): Unit = synchronized(lock) {
        startLocked()
        clearLocked(accountId)
        clearProblem(accountId)
        nm.cancel(UNSENT_PREFIX + accountId, ALERT_ID)
    }

    /**
     * "Sign-in needed" for [account]; the tap opens the sign-in screen. [intent] (an AccountManager consent
     * screen) is never wrapped in a PendingIntent of this app: the sign-in screen asks again (apis AF5).
     */
    fun signInNeeded(account: Account, intent: Intent?) {
        val tap = activity(ReauthLink.intent(app, account.id))
        postAlert(SIGN_IN_PREFIX + account.id, account, R.string.push_signin_title, R.string.push_signin_text, tap)
    }

    /** An account problem the user must see; the text is generic, never the server's words. */
    fun problem(account: Account, error: MailError) {
        val (title, text) = when (error) {
            is MailError.Tls -> R.string.push_tls_title to R.string.push_tls_text
            is MailError.Setup -> R.string.push_setup_title to R.string.push_setup_text
            else -> R.string.push_problem_title to R.string.push_problem_text
        }
        postAlert(PROBLEM_PREFIX + account.id, account, title, text, openApp)
    }

    /** The account works again: its sign-in and problem alerts go. */
    fun clearProblem(accountId: Long) {
        nm.cancel(SIGN_IN_PREFIX + accountId, ALERT_ID)
        nm.cancel(PROBLEM_PREFIX + accountId, ALERT_ID)
    }

    /**
     * From process start: a send that fails while the app is not on screen posts "Message not sent" (DESIGN
     * §7.5); on screen, the mailbox shows it. One alert per account, with no message content (privacy PS11).
     */
    fun watchSendFailures() {
        scope.launch {
            app.mail.failures.collect { failure -> if (failure.draftId != null) sendFailed(failure.accountId) }
        }
    }

    /** "Message not sent" for the account while the app is not on screen (DESIGN §7.5). Blocking (database). */
    fun sendFailed(accountId: Long) {
        if (app.push.appVisible) return
        val account = app.accounts.get(accountId) ?: return
        postAlert(UNSENT_PREFIX + account.id, account, R.string.push_unsent_title, R.string.push_unsent_text, openApp)
    }

    /** The foreground-service notification with a privacy-neutral [text]. */
    fun serviceNotification(text: String): Notification {
        ensureSharedChannels()
        return base(SERVICE_CHANNEL)
            .setContentTitle(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setLocalOnly(true)
            .build()
    }

    /** Replaces the text of the running service's notification. */
    internal fun updateService(text: String) = nm.notify(SERVICE_ID, serviceNotification(text))

    /**
     * "Push stopped" with a Resume action (a notification action may start the service), after a Task
     * Manager stop ([silent]) or a failed service start.
     */
    fun pushStopped(silent: Boolean = true) {
        ensureSharedChannels()
        val resume = PendingIntent.getForegroundService(
            app, 0, PushService.intent(app, StartPolicy.Source.RESUME), PendingIntent.FLAG_IMMUTABLE,
        )
        val action = Notification.Action.Builder(null as Icon?, app.getString(R.string.push_action_resume), resume)
            .build()
        val builder = base(ALERTS_CHANNEL)
            .setContentTitle(app.getString(R.string.push_stopped_title))
            .setContentText(app.getString(R.string.push_stopped_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .addAction(action)
        // A group child under GROUP_ALERT_SUMMARY makes no sound (the trick of NotificationCompat.setSilent).
        if (silent) builder.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        nm.notify(PUSH_STOPPED_ID, builder.build())
    }

    /** Push runs again. */
    fun clearPushStopped() = nm.cancel(PUSH_STOPPED_ID)

    private fun startLocked() {
        if (started) return
        started = true
        hidden = hideContent()
        restoreLocked()
        // Notifications of an earlier process may show content that is hidden now (DESIGN §9): post them again.
        if (hidden) book.accounts().forEach { schedule(it, rerender = true) }
        scope.launch {
            app.db.changes.collect { tables ->
                if (Tables.MESSAGE in tables || Tables.MESSAGE_FOLDER in tables) {
                    synchronized(lock) { book.accounts() }.forEach { schedule(it, rerender = false) }
                }
            }
        }
        scope.launch {
            app.prefs.changes().collect {
                val hide = hideContent()
                val accounts = synchronized(lock) {
                    if (hide == hidden) emptySet() else book.accounts().also { hidden = hide }
                }
                accounts.forEach { schedule(it, rerender = true) }
            }
        }
    }

    /** At most one silent update per second per account (the system drops faster updates). */
    private fun schedule(accountId: Long, rerender: Boolean): Unit = synchronized(lock) {
        dirty[accountId] = dirty[accountId] == true || rerender
        if (accountId in jobs) return
        jobs[accountId] = scope.launch {
            while (true) {
                val last = synchronized(lock) { lastUpdate[accountId] ?: 0 }
                val wait = last + UPDATE_GAP_MS - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    // A session in IDLE holds no lock, and delay stops while the CPU sleeps. Every wait is at most
                    // 1 s, so a later renew of this shared lock always covers an earlier wait; it ends by timeout.
                    wakeLock.renew(wait + UPDATE_GAP_MS)
                    delay(wait)
                }
                val again = synchronized(lock) {
                    dirty.remove(accountId) ?: run {
                        jobs.remove(accountId)
                        null
                    }
                } ?: return@launch
                update(accountId, again)
            }
        }
    }

    private fun update(accountId: Long, rerender: Boolean) {
        val account = app.accounts.get(accountId)
        synchronized(lock) {
            if (account == null) return clearLocked(accountId)
            val now = SystemClock.elapsedRealtime()
            // Counted before reconcile: children that the user dismissed change the summary too.
            val shownBefore = book.pending(accountId).size
            reconcileLocked(accountId, now)
            val before = book.pending(accountId)
            if (before.isEmpty()) return clearLocked(accountId)
            val gone = book.retain(accountId, app.messages.stillNotifiable(before.map { it.id }))
            // Content shown again after it was hidden: the newest mail gets its children back.
            val shown = if (rerender && !hideContent()) book.showChildren(accountId, now).cancel else emptyList()
            val rows = Rows(app.messages)
            val children = book.children(accountId)
            val repost = if (rerender) {
                children
            } else {
                children.filter { it.id in noSnippet && rows[it.id]?.snippet?.isNotBlank() == true }
            }
            if (book.pending(accountId).size == shownBefore && repost.isEmpty() && !rerender) return
            renderLocked(account, rows, now, alert = false, post = repost, cancel = gone + shown)
        }
    }

    /** Posts the summary first, then [post]; cancels [cancel]. Content-free when content is hidden. */
    private fun renderLocked(
        account: Account,
        rows: Rows,
        now: Long,
        alert: Boolean,
        post: List<Item>,
        cancel: List<Item>,
    ) {
        val id = account.id
        cancel.forEach { cancelChild(it.id) }
        val hide = hideContent()
        if (hide) book.dropChildren(id).forEach { cancelChild(it.id) }
        val summary = book.summary(id)
        if (summary.count == 0 || (!hide && book.children(id).isEmpty())) return clearLocked(id)

        nm.notify(Texts.summaryTag(id), SUMMARY_ID, summaryOf(account, summary, rows, alert, hide))
        book.touch(id, now)
        lastUpdate[id] = now
        if (hide) return
        val canDelete = app.folders.abilities(account).canDelete
        for (item in post) {
            val message = rows[item.id] ?: continue
            nm.notify(Texts.childTag(item.id), CHILD_ID, childOf(account, message, canDelete))
            if (message.snippet.isBlank()) noSnippet += item.id else noSnippet -= item.id
        }
    }

    private fun summaryOf(
        account: Account,
        summary: NotificationBook.Summary,
        rows: Rows,
        alert: Boolean,
        hide: Boolean,
    ): Notification {
        val channel = mailChannel(account.id)
        val title = quantity(R.plurals.push_new_messages, summary.count)
        val label = Texts.publicLabel(account.label)
        val public = base(channel).setContentTitle(title).apply { label?.let(::setSubText) }.build()
        val pending = book.pending(account.id)
        val builder = base(channel)
            .setContentTitle(title)
            .setNumber(summary.count)
            .setWhen(summary.lines.first().date)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setOnlyAlertOnce(!alert)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .addExtras(
                Bundle().apply {
                    putLong(EXTRA_ACCOUNT, account.id)
                    putLongArray(EXTRA_IDS, pending.map { it.id }.toLongArray())
                    putLongArray(EXTRA_DATES, pending.map { it.date }.toLongArray())
                },
            )
        if (hide) return builder.apply { label?.let(::setSubText) }.build()

        val messages = summary.lines.mapNotNull { rows[it.id] }
        val style = Notification.InboxStyle().setBigContentTitle(title)
        messages.forEach { style.addLine(lineOf(it)) }
        if (summary.more > 0) style.setSummaryText(quantity(R.plurals.push_more, summary.more))
        return builder
            .setSubText(labelOf(account))
            .setContentText(messages.map(::senderOf).distinct().joinToString(", "))
            .setStyle(style)
            .setGroup(groupOf(account.id))
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .build()
    }

    private fun childOf(account: Account, message: Message, canDelete: Boolean): Notification {
        val channel = mailChannel(account.id)
        val subject = revealBidi(message.subject).ifBlank { app.getString(R.string.push_no_subject) }
        val public = base(channel)
            .setContentTitle(app.getString(R.string.push_new_message))
            .apply { Texts.publicLabel(account.label)?.let(::setSubText) }
            .setWhen(message.date)
            .build()
        return base(channel)
            .setContentTitle(senderOf(message))
            .setContentText(subject)
            .setStyle(Notification.BigTextStyle().bigText(Texts.bigText(subject, message.snippet)))
            .setSubText(labelOf(account))
            .setWhen(message.date)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setGroup(groupOf(account.id))
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(activity(MessageLink.intent(app, message.id)))
            .setAutoCancel(true)
            .addAction(actionOf(message, NotificationAction.ARCHIVE))
            .apply { if (canDelete) addAction(actionOf(message, NotificationAction.DELETE)) }
            .addAction(actionOf(message, NotificationAction.MARK_READ))
            .addExtras(
                Bundle().apply {
                    putLong(EXTRA_ACCOUNT, account.id)
                    putLong(EXTRA_DATE, message.date)
                    putBoolean(EXTRA_SNIPPET, message.snippet.isNotBlank())
                },
            )
            .build()
    }

    /** Archive and Delete need an unlocked phone (privacy PS11); every intent is unique per message (apis AF6). */
    private fun actionOf(message: Message, action: NotificationAction): Notification.Action {
        val (title, semantic) = when (action) {
            NotificationAction.ARCHIVE -> R.string.push_action_archive to Notification.Action.SEMANTIC_ACTION_ARCHIVE
            NotificationAction.DELETE -> R.string.push_action_delete to Notification.Action.SEMANTIC_ACTION_DELETE
            NotificationAction.MARK_READ ->
                R.string.push_action_mark_read to Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
        }
        val intent = ActionReceiver.intent(app, message.accountId, message.id, action)
        val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null as Icon?, app.getString(title), pending)
            .setSemanticAction(semantic)
            .setAuthenticationRequired(action != NotificationAction.MARK_READ)
            .build()
    }

    private fun postAlert(
        tag: String,
        account: Account,
        @StringRes title: Int,
        @StringRes text: Int,
        tap: PendingIntent,
    ) {
        ensureSharedChannels()
        val body = app.getString(text, labelOf(account))
        val public = base(ALERTS_CHANNEL)
            .setContentTitle(app.getString(title))
            .apply { Texts.publicLabel(account.label)?.let(::setSubText) }
            .build()
        val notification = base(ALERTS_CHANNEL)
            .setContentTitle(app.getString(title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(tag, ALERT_ID, notification)
    }

    private fun base(channel: String) = Notification.Builder(app, channel)
        .setSmallIcon(R.drawable.push_ic_notification)
        .setAllowSystemGeneratedContextualActions(false)

    private fun activity(intent: Intent) = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)

    private fun quantity(@PluralsRes id: Int, count: Int) = app.resources.getQuantityString(id, count, count)

    private fun ensureSharedChannels() {
        if (sharedChannelsReady) return
        nm.createNotificationChannels(sharedChannels())
        sharedChannelsReady = true
    }

    private fun sharedChannels() = listOf(
        channel(SERVICE_CHANNEL, R.string.push_channel_service, NotificationManager.IMPORTANCE_LOW).apply {
            description = app.getString(R.string.push_channel_service_description)
            setShowBadge(false)
        },
        channel(ALERTS_CHANNEL, R.string.push_channel_alerts, NotificationManager.IMPORTANCE_HIGH).apply {
            description = app.getString(R.string.push_channel_alerts_description)
        },
    )

    private fun channel(id: String, @StringRes name: Int, importance: Int) =
        NotificationChannel(id, app.getString(name), importance)

    private fun mailChannelOf(account: Account) =
        NotificationChannel(mailChannel(account.id), labelOf(account), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = app.getString(R.string.push_channel_mail_description) }

    /** Notifications on, and the account's channel not turned off by the user. Creates a missing channel. */
    private fun canPost(account: Account): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(mailChannel(account.id))
            ?: return true.also { ensureChannels(listOf(account)) }
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun hideContent() = Texts.hideContent(app.appLock.enabled, app.prefs.hideNotificationContent)

    /** Applies what the user dismissed since the last post (the system shows the truth). */
    private fun reconcileLocked(accountId: Long, now: Long) {
        val shown = nm.activeNotifications
        val summaryShown = shown.any { it.id == SUMMARY_ID && it.tag == Texts.summaryTag(accountId) }
        val children = shown.filter { it.id == CHILD_ID && it.notification.extras.getLong(EXTRA_ACCOUNT) == accountId }
            .mapNotNull { Texts.childId(it.tag) }
            .toSet()
        book.reconcile(accountId, summaryShown, children, now, GRACE_MS).forEach { cancelChild(it.id) }
    }

    /** After a process restart: the summaries carry the ids of their pending mail, the children their date. */
    private fun restoreLocked() {
        val pending = HashMap<Long, MutableList<Item>>()
        val children = HashMap<Long, MutableList<Item>>()
        for (sbn in nm.activeNotifications) {
            val extras = sbn.notification.extras
            val account = extras.getLong(EXTRA_ACCOUNT)
            if (account <= 0) continue
            when (sbn.id) {
                SUMMARY_ID -> {
                    val ids = extras.getLongArray(EXTRA_IDS) ?: continue
                    val dates = extras.getLongArray(EXTRA_DATES)?.takeIf { it.size == ids.size } ?: continue
                    pending.getOrPut(account, ::ArrayList) += ids.indices.map { Item(ids[it], account, dates[it]) }
                }
                CHILD_ID -> {
                    val id = Texts.childId(sbn.tag) ?: continue
                    children.getOrPut(account, ::ArrayList) += Item(id, account, extras.getLong(EXTRA_DATE))
                    if (!extras.getBoolean(EXTRA_SNIPPET)) noSnippet += id
                }
            }
        }
        (pending.keys + children.keys).forEach { book.restore(it, pending[it].orEmpty(), children[it].orEmpty()) }
    }

    private fun clearLocked(accountId: Long) {
        book.clear(accountId).forEach { cancelChild(it.id) }
        nm.cancel(Texts.summaryTag(accountId), SUMMARY_ID)
    }

    private fun cancelChild(messageId: Long) {
        nm.cancel(Texts.childTag(messageId), CHILD_ID)
        noSnippet -= messageId
    }

    private fun senderOf(message: Message) =
        Texts.sender(message.fromName, message.fromEmail) ?: app.getString(R.string.push_no_sender)

    /** One InboxStyle line: the sender in bold, then the subject. */
    private fun lineOf(message: Message): CharSequence {
        val sender = senderOf(message)
        return SpannableStringBuilder(sender).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, sender.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ")
            append(revealBidi(message.subject).ifBlank { app.getString(R.string.push_no_subject) })
        }
    }

    private fun labelOf(account: Account) = account.label.ifBlank { account.provider.brand }

    /** Rows read once per post. */
    private class Rows(private val messages: MessageDao) {
        private val rows = HashMap<Long, Message?>()

        operator fun get(id: Long): Message? = rows.getOrPut(id) { messages.get(id) }
    }

    companion object {
        /** The foreground-service notification. */
        const val SERVICE_ID = 1
        private const val PUSH_STOPPED_ID = 2
        private const val SUMMARY_ID = 3
        internal const val CHILD_ID = 4
        private const val ALERT_ID = 5

        internal const val SERVICE_CHANNEL = "service"
        internal const val ALERTS_CHANNEL = "alerts"
        private const val MAIL_PREFIX = "mail_"
        private const val SILENT_GROUP = "silent"
        private const val SIGN_IN_PREFIX = "signin:"
        private const val PROBLEM_PREFIX = "problem:"
        private const val UNSENT_PREFIX = "unsent:"
        private const val LOG_TAG = "Plico"

        internal const val EXTRA_ACCOUNT = "app.plico.account"
        private const val EXTRA_IDS = "app.plico.ids"
        private const val EXTRA_DATES = "app.plico.dates"
        private const val EXTRA_DATE = "app.plico.date"
        private const val EXTRA_SNIPPET = "app.plico.snippet"

        private const val UPDATE_GAP_MS = 1_000L
        private const val GRACE_MS = 2_000L

        /** The new-mail channel of an account; the user turns its notifications off there. */
        fun mailChannel(accountId: Long): String = MAIL_PREFIX + accountId

        private fun groupOf(accountId: Long) = "acct_$accountId"
    }
}
