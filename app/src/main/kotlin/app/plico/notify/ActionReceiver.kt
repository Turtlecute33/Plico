package app.plico.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.plico.app
import app.plico.push.PushService
import app.plico.push.StartPolicy.Source
import app.plico.push.runAsync
import app.plico.sync.NotificationAction

/**
 * Archive / Delete / Mark read from a new-mail notification (DESIGN §9; push PB7). The change is local at
 * once and its op runs now; when push is not running, the service starts (a notification action may
 * start it) so that the op reaches the server.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE, 0)
        val accountId = intent.getLongExtra(EXTRA_ACCOUNT, 0)
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { name ->
            NotificationAction.entries.firstOrNull { it.name == name }
        }
        if (messageId <= 0 || accountId <= 0 || action == null) return

        val app = context.app
        app.power.holder("action").renew(LOCK_MS)
        val engine = app.push
        runAsync(engine) {
            if (!engine.running && !app.prefs.pushPaused) PushService.start(app, Source.ACTION)
            app.mail.notificationAction(messageId, action)
            app.notifier.refresh(accountId)
        }
    }

    internal companion object {
        private const val EXTRA_MESSAGE = "app.plico.message"
        private const val EXTRA_ACCOUNT = "app.plico.account"
        private const val EXTRA_ACTION = "app.plico.action"
        private const val LOCK_MS = 10_000L

        /** Explicit and unique per message and action (apis AF6). */
        fun intent(context: Context, accountId: Long, messageId: Long, action: NotificationAction): Intent =
            Intent(context, ActionReceiver::class.java)
                .setIdentifier("msg:$messageId:${action.name.lowercase()}")
                .putExtra(EXTRA_MESSAGE, messageId)
                .putExtra(EXTRA_ACCOUNT, accountId)
                .putExtra(EXTRA_ACTION, action.name)
    }
}
