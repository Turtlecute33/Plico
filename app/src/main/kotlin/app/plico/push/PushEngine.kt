package app.plico.push

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import app.plico.PlicoApp
import app.plico.account.MailError
import app.plico.data.Account
import app.plico.push.StartPolicy.Source
import app.plico.push.policy.DueTimes
import app.plico.push.policy.NetworkRules
import app.plico.push.policy.PushModes
import app.plico.sync.AccountSession
import app.plico.sync.NetState
import app.plico.sync.SessionHost
import app.plico.sync.WakeHolder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the foreground service, the alarm, the network watcher and the sessions' modes (DESIGN §8), and is
 * the [SessionHost] of every session. Push runs while [PushService] runs: at least one account, not paused.
 */
class PushEngine(private val app: PlicoApp) : SessionHost {
    // A failure in one task must not end push; only the class name is logged (DESIGN §12).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(LOG_TAG, "push: ${e.javaClass.simpleName}")
        },
    )
    private val scheduler = Scheduler(app)
    private val watcher = NetworkWatcher(app, app.power.holder("net"), scope, ::onNetwork)
    private val tls = TlsAlerts()
    private val tlsErrors = ConcurrentHashMap<Long, MailError.Tls>()
    private val modes = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val wakePending = AtomicBoolean()
    private val firstStart = AtomicBoolean(true)
    private val sessions = MutableStateFlow<List<AccountSession>>(emptyList())
    private val serviceLock = Any()
    private var textJob: Job? = null

    /** True while [PushService] runs in the foreground. */
    @Volatile
    var running = false
        private set

    /** The app UI is on screen (sessions run a backfill step on metered networks only then). */
    @Volatile
    var appVisible = false
        private set

    private val state = MutableStateFlow(readStatus())

    /** Grants and push state for the UI; updated on start, stop, pause, network changes and [refreshStatus]. */
    val status: StateFlow<PushStatus> = state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                signal.receive()
                try {
                    process()
                } catch (e: RuntimeException) {
                    Log.e(LOG_TAG, "push wake: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Starts the service when accounts exist and push is not paused. Call it from the foreground UI. */
    fun ensureStarted() {
        if (running || paused()) return
        scope.launch { if (app.accounts.all().isNotEmpty()) PushService.start(app, Source.UI) }
    }

    /** Accounts added or removed, or push settings changed. */
    fun onAccountsChanged() {
        scope.launch {
            val accounts = app.accounts.all()
            app.notifier.ensureChannels(accounts)
            app.notifier.removeStaleChannels(accounts.mapTo(HashSet()) { it.id })
            applyModes(accounts)
            when {
                accounts.isEmpty() -> {
                    scheduler.cancel()
                    stopService()
                }
                running -> reschedule()
                else -> ensureStarted()
            }
        }
    }

    /** The user paused push in the app: no service, no alarm, no start at boot. */
    fun pauseByUser() {
        app.prefs.pushPaused = true
        setStartReceiver(enabled = false)
        scheduler.cancel()
        stopService()
        updateStatus()
    }

    /** Ends a pause by the user or a Task Manager stop, and starts push. Call it from the foreground UI. */
    fun resume() {
        app.prefs.pushPaused = false
        app.prefs.pushStoppedBySystemUi = false
        setStartReceiver(enabled = true)
        app.notifier.clearPushStopped()
        updateStatus()
        ensureStarted()
    }

    /** The app UI became visible or hidden: a coalescing point, and grants are read again (push PB23). */
    fun onAppVisible(visible: Boolean) {
        appVisible = visible
        // The change to visible syncs every account (a user action); hidden ends the linger and backfill rule.
        app.sessions.appVisible = visible
        if (!visible) return
        refreshStatus()
        ensureStarted()
        onWake()
    }

    /** Reads the grants again (app start, the "Push & battery" screen, an exact-alarm grant). */
    fun refreshStatus() {
        updateStatus()
        reschedule()
    }

    override fun network(): NetState? = watcher.network()

    override fun wakeHolder(tag: String): WakeHolder = app.power.holder(tag)

    override fun reschedule() {
        signal.trySend(Unit)
    }

    override fun onServerActivity() = onWake()

    /** Blocking: posts on the caller's (session) thread, under the session's wake lock. */
    override fun onNewMail(accountId: Long, messageIds: List<Long>) = app.notifier.postNew(accountId, messageIds)

    override fun onMessagesChanged(accountId: Long) = app.notifier.refresh(accountId)

    /**
     * Sign-in needed alerts at once. A TLS failure alerts only after 1 h or on a second network (push PB9);
     * the app shows the account state at once. Network and throttling errors never alert.
     */
    override fun onAccountProblem(accountId: Long, error: MailError?) {
        scope.launch {
            val account = app.accounts.get(accountId) ?: return@launch
            if (error !is MailError.Tls) {
                tls.clear(accountId)
                tlsErrors.remove(accountId)
            }
            when (error) {
                null -> app.notifier.clearProblem(accountId)
                is MailError.AuthRequired -> app.notifier.signInNeeded(account, error.intent)
                is MailError.Tls -> {
                    tlsErrors[accountId] = error
                    if (tls.onFailure(accountId, SystemClock.elapsedRealtime(), network()?.handle)) {
                        app.notifier.problem(account, error)
                    }
                    reschedule()
                }
                is MailError.Network, is MailError.Throttled -> Unit
                else -> app.notifier.problem(account, error)
            }
        }
    }

    /** A coalescing point (DESIGN §8.3, push PB13): alarm, server byte, network change, app visible. */
    internal fun onWake() {
        wakePending.set(true)
        signal.trySend(Unit)
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    /** True only for the first service start in this process (the exit-reason check, DESIGN §8.1). */
    internal fun takeFirstStart(): Boolean = firstStart.getAndSet(false)

    internal fun serviceText(): ServiceText = ServiceText.of(network() != null, state.value.reliable, delayed = false)

    /** The service is in the foreground and may run push. Main thread. */
    internal fun onServiceStarted() {
        app.notifier.clearPushStopped()
        if (!running) {
            synchronized(serviceLock) { running = true }
            watcher.start()
            startTextUpdates()
        }
        scope.launch {
            val accounts = app.accounts.all()
            if (accounts.isEmpty() || paused()) {
                stopService()
                return@launch
            }
            app.notifier.ensureChannels(accounts)
            app.notifier.start()
            applyModes(accounts)
            updateStatus()
            onWake()
        }
    }

    /** The service was destroyed. Main thread. The alarm stays, so that it restarts push. */
    internal fun onServiceStopped() {
        synchronized(serviceLock) { running = false }
        textJob?.cancel()
        watcher.stop()
        scope.launch {
            applyModes()
            updateStatus()
        }
    }

    /** The service could not enter the foreground: try again in 1 min (an exact alarm may start it). */
    internal fun retryStartSoon() {
        scheduler.arm(SystemClock.elapsedRealtime() + RETRY_START_MS)
    }

    internal fun cancelAlarm() = scheduler.cancel()

    /** Runs a pending wake, then arms the alarm for the earliest due time (push PB4: armed before any lock ends). */
    private fun process() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        try {
            if (wakePending.getAndSet(false)) {
                app.sessions.all().forEach { it.onWake(now) }
                for (id in tls.due(now)) {
                    val account = app.accounts.get(id) ?: continue
                    tlsErrors[id]?.let { app.notifier.problem(account, it) }
                }
            }
        } finally {
            val dues = app.sessions.all().map { it.nextDueAt() } + tls.nextDueAt()
            scheduler.arm(DueTimes.alarmAt(now, dues, network() != null))
        }
    }

    private suspend fun onNetwork(event: NetworkRules.Event?, net: NetState?) {
        applyModes()
        if (event != null) app.sessions.all().forEach { it.onNetworkEvent(event, net) }
        updateStatus()
        onWake()
    }

    /** Creates, removes and sets the mode of the sessions: IDLE or POLL while push runs, else OFF. */
    private suspend fun applyModes(accounts: List<Account>? = null) = modes.withLock {
        val all = accounts ?: app.accounts.all()
        val ids = all.mapTo(HashSet()) { it.id }
        for (session in app.sessions.all()) {
            if (session.accountId in ids) continue
            app.sessions.remove(session.accountId)
            app.notifier.cancelAccount(session.accountId)
            tls.clear(session.accountId)
        }
        if (running) {
            val net = network()
            for (account in all) app.sessions[account.id].mode = modeOf(account, net)
        } else {
            app.sessions.all().forEach { it.mode = AccountSession.Mode.OFF }
        }
        sessions.value = app.sessions.all()
    }

    private fun modeOf(account: Account, net: NetState?) =
        when (PushModes.effective(account.pushMode, net?.unmeteredWifi == true, net?.charging == true)) {
            PushModes.Effective.IDLE -> AccountSession.Mode.IDLE
            PushModes.Effective.POLL -> AccountSession.Mode.POLL
        }

    /** The service text follows the network, the grants and the sessions, at most every 5 s. */
    private fun startTextUpdates() {
        textJob?.cancel()
        textJob = scope.launch {
            serviceTexts().distinctUntilChanged().conflate().collect { text ->
                synchronized(serviceLock) { if (running) app.notifier.updateService(app.getString(text.text)) }
                delay(TEXT_GAP_MS)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun serviceTexts(): Flow<ServiceText> {
        val delayed = sessions.flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(false)
            } else {
                combine(list.map { it.status }) { all ->
                    all.any { it is AccountSession.Status.Waiting || it is AccountSession.Status.Problem }
                }
            }
        }
        return combine(watcher.current, state, delayed) { net, status, late ->
            ServiceText.of(net != null, status.reliable, late)
        }
    }

    private fun readStatus(): PushStatus {
        val grants = Grants.read(app)
        return PushStatus(
            running = running,
            pausedByUser = app.prefs.pushPaused,
            stoppedBySystem = app.prefs.pushStoppedBySystemUi,
            hasNetwork = network() != null,
            notificationsAllowed = grants.notifications,
            batteryUnrestricted = grants.batteryUnrestricted,
            exactAlarms = grants.exactAlarms,
            backgroundRestricted = grants.backgroundRestricted,
        )
    }

    private fun updateStatus() {
        state.value = readStatus()
    }

    private fun paused() = app.prefs.pushPaused || app.prefs.pushStoppedBySystemUi

    // Only a service in the foreground is stopped here; a start in flight stops itself (push PB6).
    private fun stopService() {
        if (running) app.stopService(Intent(app, PushService::class.java))
    }

    private fun setStartReceiver(enabled: Boolean) = app.packageManager.setComponentEnabledSetting(
        ComponentName(app, StartReceiver::class.java),
        if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        },
        PackageManager.DONT_KILL_APP,
    )

    private companion object {
        const val TEXT_GAP_MS = 5_000L
        const val RETRY_START_MS = 60_000L
    }
}
