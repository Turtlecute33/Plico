package app.plico.sync

import android.os.SystemClock
import app.plico.PlicoApp
import app.plico.account.AndroidSocketHooks
import app.plico.account.Credentials
import app.plico.account.MailError
import app.plico.account.MailErrors
import app.plico.account.Providers
import app.plico.account.credentialsFor
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.mail.imap.IdleEvent
import app.plico.mail.imap.IdleHooks
import app.plico.mail.imap.ImapBadException
import app.plico.mail.imap.ImapByeException
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapNoException
import app.plico.push.policy.Backoff
import app.plico.push.policy.Coalescing
import app.plico.push.policy.DueTimes
import app.plico.push.policy.KeepalivePolicy
import app.plico.push.policy.KeepalivePolicy.Action
import app.plico.push.policy.KeyValueStore
import app.plico.push.policy.NetworkRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One per account: owns at most one IMAP connection and one worker loop on Dispatchers.IO (DESIGN.md §7.1, §8.3).
 * All IMAP work of the account runs on the loop, in order. No public call touches a socket on the caller's thread.
 *
 * Loop: connect → authenticate → capabilities → COMPRESS → ENABLE CONDSTORE → work → IDLE (mode [Mode.IDLE]) or
 * LOGOUT (the other modes; after 2 min without work while the UI is visible). A kick sets the work-pending flag
 * that IDLE checks before it starts and right after `+` (push PB1).
 */
class AccountSession(val app: PlicoApp, val accountId: Long, private val host: SessionHost) {

    enum class Mode {
        /** Keep a connection and IDLE on the IDLE mailbox. */
        IDLE,

        /** Connect at each poll, sync, log out. */
        POLL,

        /** No background work; connect only for [request] and queued work, then log out. */
        OFF,
    }

    enum class Reason {
        /** Queued work: an op, a send, a request. */
        WORK,

        /** Pull to refresh or app opened: every folder, and one attempt now even during a backoff. */
        USER_REFRESH,

        /** A wake from the alarm. */
        ALARM,

        /** The network changed: one attempt now. */
        NETWORK,

        /** The UI waits for a result: one attempt now. */
        OPEN_FOLDER,
    }

    sealed interface Status {
        data object Offline : Status
        data object Connecting : Status
        data object Syncing : Status

        /** Connected and waiting: in IDLE, or kept open for a while after work. */
        data object Idle : Status

        /** The next attempt is at [retryAt] (elapsedRealtime). */
        data class Waiting(val retryAt: Long) : Status

        /** The user must act (sign-in, setup), or a secure connection failed. */
        data class Problem(val error: MailError) : Status
        data object NoNetwork : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Offline)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Set by the push engine. Changing it starts, reconfigures or winds down the loop. */
    @Volatile
    var mode: Mode = Mode.OFF
        set(value) {
            if (field == value) return
            field = value
            if (value == Mode.POLL) requestPoll(elapsed()) else wake()
            host.reschedule()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val workPending = AtomicBoolean()

    /** Short in-process waits (debounce, DONE watchdog, soon-due work) that keep the wake lock during IDLE. */
    private val timers = AtomicInteger()
    private val debouncing = AtomicBoolean()
    private val requests = ConcurrentLinkedQueue<Request<*>>()
    private val wakeLock: WakeHolder by lazy { host.wakeHolder("sync:$accountId") }
    private val backoff = Backoff()

    @Volatile private var policy: KeepalivePolicy? = null
    @Volatile private var connection: Connection? = null
    @Volatile private var idleRun: IdleRun? = null
    @Volatile private var loop: Job? = null
    @Volatile private var closed = false
    @Volatile private var authStopped = false
    @Volatile private var relist = false
    @Volatile private var networkChangedDuringIdle = false

    /** Earliest op run_at or send_at, as elapsedRealtime; null when none can run. */
    @Volatile private var workDueAt: Long? = null
    @Volatile private var lastPollAt = 0L
    @Volatile private var pollMs = DEFAULT_POLL_MS

    // Loop-only state.
    private var lastListAt: Long? = null
    private var lastSweepAt: Long? = null
    private var lastWorkRunAt = 0L
    private var soonTimer: Job? = null
    private var problemReported = false
    private var savedCadence: Int? = null

    // Triggers, guarded by [lock].
    private val lock = Any()
    private var changed = false
    private var userRefresh = false
    private var immediate = false
    private var poll = false
    private var reconnect = false
    private var check: Action? = null

    /** Server message counts per folder, kept across connections. */
    internal val knownMessages = ConcurrentHashMap<Long, Long>()

    internal val notes = object : KeyValueStore {
        override fun get(key: String) = app.prefs.getString(key)
        override fun put(key: String, value: String?) = app.prefs.putString(key, value)
    }

    internal val isWorkPending: Boolean get() = workPending.get()

    /** Marks work pending and ends IDLE soon. Never writes to a socket on the caller's thread. */
    fun kick(reason: Reason = Reason.WORK) {
        synchronized(lock) {
            when (reason) {
                Reason.USER_REFRESH -> {
                    userRefresh = true
                    immediate = true
                }
                Reason.NETWORK, Reason.OPEN_FOLDER -> immediate = true
                Reason.WORK, Reason.ALARM -> Unit
            }
        }
        wake()
    }

    /** Engine wake (alarm, server byte on any session, network, app visible): runs due refresh/poll/reconnect. */
    fun onWake(now: Long) {
        if (closed) return
        scope.launch {
            when (val action = policy?.decide(now) ?: Action.NONE) {
                Action.NONE -> Unit
                Action.RECONNECT -> {
                    synchronized(lock) { reconnect = true }
                    wake()
                }
                else -> requestCheck(action)
            }
            val pollDue = Coalescing.timing(now, lastPollAt + pollMs, pollMs, Coalescing.Kind.POLL)
            if (mode == Mode.POLL && pollDue != Coalescing.Timing.LATER) requestPoll(now)
            if (backoff.isDue(now) || workDueAt?.let { it <= now } == true) wake()
        }
    }

    fun onNetworkEvent(event: NetworkRules.Event, network: NetState?) {
        if (closed) return
        if (idleRun != null) networkChangedDuringIdle = true
        if (network != null) backoff.onNetworkChanged(network.handle)
        val c = connection
        val wants = mode == Mode.IDLE || synchronized(lock) { poll } || backoff.retryAt != null || requests.isNotEmpty()
        when (NetworkRules.action(event, c?.network, network?.handle, wants)) {
            NetworkRules.Action.RECONNECT -> {
                c?.let { drop(it, Close.NETWORK) }
                kick(Reason.NETWORK)
            }
            NetworkRules.Action.PARK -> {
                c?.let { drop(it, Close.NETWORK) }
                _status.value = Status.NoNetwork
            }
            NetworkRules.Action.NONE -> Unit
        }
    }

    /** Earliest elapsedRealtime this session needs a wake: keepalive, expiry, backoff, poll, op run_at, send_at. */
    fun nextDueAt(): Long? {
        val now = elapsed()
        val pollAt = if (mode == Mode.POLL && !synchronized(lock) { poll }) lastPollAt + pollMs else null
        // Past times are left out: the wake that is due now handles them, and an alarm in the past would repeat.
        return DueTimes.min(
            listOf(policy?.nextDueAt(), backoff.retryAt, pollAt, workDueAt).map { it?.takeIf { t -> t > now } },
        )
    }

    /**
     * Runs [block] on the loop with a connected, authenticated client (connects on demand), after due ops and
     * sends. Exceptions of [block] reach the caller unchanged; a failed connect gives a [MailError]. Cancelling
     * the caller while [block] runs closes the connection (DESIGN §7.7); the loop reconnects without a backoff.
     */
    suspend fun <T> request(block: suspend SyncContext.() -> T): T {
        if (closed) throw CancellationException("Session closed")
        val request = Request(block)
        requests.add(request)
        kick(Reason.OPEN_FOLDER)
        try {
            return request.result.await()
        } catch (e: CancellationException) {
            if (request.result.completeExceptionally(e)) {
                requests.remove(request)
                request.running?.let { drop(it, Close.CANCEL) }
            }
            throw e
        }
    }

    /** The user fixed the sign-in: clear AuthRequired and its alert, and reconnect. */
    fun signedIn() {
        authStopped = false
        backoff.reset()
        relist = true
        host.onAccountProblem(accountId, null)
        kick(Reason.USER_REFRESH)
    }

    fun close() {
        closed = true
        scope.cancel()
        connection?.let { drop(it, Close.SHUTDOWN) }
        failRequests(CancellationException("Session closed"))
        policy?.onIdleEnded()
        wakeLock.release()
        _status.value = Status.Offline
    }

    internal fun progress() = wakeLock.renew()

    /** The server announced new mail outside a SELECT: the loop runs the fast path again before IDLE. */
    internal fun onServerNewMail() {
        synchronized(lock) { changed = true }
        workPending.set(true)
    }

    /** Hands a commit to the engine: new mail to notify, changed rows, an outdated folder list. */
    internal fun publish(sync: SyncAccount, report: SyncReport) {
        val ids = sync.claimNew()
        if (ids.isNotEmpty()) host.onNewMail(accountId, ids)
        if (report.changed) {
            report.changed = false
            host.onMessagesChanged(accountId)
        }
        if (report.relist) relist = true
    }

    // ---- Loop

    private fun ensureStarted() {
        if (loop != null || closed) return
        synchronized(lock) {
            if (loop == null && !closed) loop = scope.launch { run() }
        }
    }

    private fun wake() {
        if (closed) return
        workPending.set(true)
        wakeLock.renew()
        signal.trySend(Unit)
        ensureStarted()
        idleRun?.let { run ->
            scope.launch {
                run.conn.client.stopIdle()
                watch(run)
            }
        }
    }

    private fun requestCheck(action: Action) {
        synchronized(lock) {
            if (check != null) return
            check = action
        }
        wake()
    }

    private fun requestPoll(now: Long) {
        synchronized(lock) { poll = true }
        lastPollAt = now
        wake()
    }

    private suspend fun run() {
        val first = app.accounts.get(accountId) ?: return
        policy = KeepalivePolicy(Providers.of(first.provider).idleStartMinutes, first.provider.id, app.learnedIntervals)
        authStopped = first.state == AccountState.SIGN_IN_NEEDED
        while (true) {
            currentCoroutineContext().ensureActive()
            val c = connection
            try {
                val account = app.accounts.get(accountId) ?: return
                pollMs = account.pollMinutes * MINUTE_MS
                when {
                    c == null -> if (!connectIfWanted(account)) awaitWork()
                    takeReconnect() -> logout(c)
                    else -> {
                        cycle(c, account)
                        if (!workPending.get()) after(c)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                fail(IOException("Work took too long", e), c, first.isGmail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e, c, first.isGmail)
            }
        }
    }

    /** Connects when there is a reason to; false when the loop must wait for one. */
    private suspend fun connectIfWanted(account: Account): Boolean {
        workPending.set(false)
        val now = elapsed()
        refreshWorkDue()
        val wanted = mode == Mode.IDLE || requests.isNotEmpty() || synchronized(lock) { userRefresh || poll } ||
            workDueAt?.let { it <= now } == true
        val network = host.network()
        val retryAt = backoff.retryAt
        val error: MailError? = when {
            !wanted -> null.also { _status.value = Status.Offline }
            network == null -> MailError.Network(IOException("No network")).also { _status.value = Status.NoNetwork }
            authStopped -> MailError.AuthRequired(account.stateText.orEmpty()).also { _status.value = Status.Problem(it) }
            else -> {
                val byUser = synchronized(lock) { immediate.also { immediate = false } }
                if (retryAt != null && now < retryAt && !byUser) {
                    _status.value = Status.Waiting(retryAt)
                    MailError.Network(IOException("Waiting to retry"))
                } else if (backoff.onAttempt(byUser)) {
                    try {
                        connect(account, network)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        fail(e, null, account.isGmail)
                    }
                    return true
                } else {
                    null
                }
            }
        }
        error?.let(::failRequests)
        scheduleSoonWork()
        host.reschedule()
        return false
    }

    private suspend fun connect(account: Account, network: NetState) {
        _status.value = Status.Connecting
        wakeLock.renew()
        val credentials = credentialsFor(app, account)
        val server = Providers.of(account.provider).imap
        val client = ImapClient.connect(server.host, server.port, AndroidSocketHooks, debugLog("imap"))
        try {
            credentials.authImap(client)
            client.requireCapabilities()
            client.compress()
            if ("CONDSTORE" in client.caps) client.enable("CONDSTORE")
        } catch (e: Throwable) {
            client.close()
            throw e
        }
        app.accounts.setCaps(accountId, client.caps.sorted().joinToString(" "))
        val ctx = SyncContext(app, account, client, credentials, this)
        connection = Connection(client, ctx, credentials, network.handle, network.key)
        // Gmail setup states are decided by the folder list; a Riseup one could only be a missing capability.
        val cleared = account.state == AccountState.SIGN_IN_NEEDED || account.state == AccountState.TLS_PROBLEM ||
            (account.state == AccountState.SETUP_PROBLEM && !account.isGmail)
        if (cleared) app.accounts.setState(accountId, AccountState.OK, null)
        if (cleared || problemReported) clearProblem()
    }

    /** One pass of the work order of DESIGN.md §7.1. */
    private suspend fun cycle(c: Connection, account: Account) {
        val trigger = takeTriggers(c)
        _status.value = Status.Syncing
        val now = elapsed()
        val visible = app.sessions.appVisible
        val plan = SyncPlan.of(
            trigger, account.isGmail, now, lastListAt, lastSweepAt, relist,
            unmetered = host.network()?.unmeteredWifi == true, appVisible = visible,
        )
        val sync = c.ctx.sync
        val report = SyncReport()
        wakeLock.hold(STEP_MAX_MS) {
            if (plan.listFolders) {
                relist = false
                sync.refreshFolders()
                lastListAt = now
            }
            if (plan.fastPath) {
                sync.fastPath(report)
                publish(sync, report)
            }
            lastWorkRunAt = elapsed()
            Ops.runDue(c.ctx)
            Sender.runDue(c.ctx)
        }
        runRequests(c)
        wakeLock.hold(STEP_MAX_MS) {
            if (plan.fastPath) sync.idleMailboxFlags(plan.fullFlags, report)
            if (plan.notifySweep) sync.notifySweep(report)
            if (plan.allMailStatus) sync.checkAllMail(report)
            if (plan.fullSweep) {
                sync.fullSweep(report)
                lastSweepAt = now
            }
            publish(sync, report)
            bodies(c.ctx, report)
            if (plan.backfill && !workPending.get()) sync.backfillStep(report)
            if (mode == Mode.IDLE && sync.missedMail(report)) publish(sync, report)
        }
        if (trigger.poll) backoff.markHealthy()
        refreshWorkDue()
        scheduleSoonWork()
        if (problemReported && app.accounts.get(accountId)?.state == AccountState.OK) clearProblem()
        host.reschedule()
    }

    private fun takeTriggers(c: Connection): SyncTrigger = synchronized(lock) {
        workPending.set(false)
        SyncTrigger(c.fresh, changed, userRefresh, poll, check).also {
            c.fresh = false
            changed = false
            userRefresh = false
            poll = false
            immediate = false
            check = null
        }
    }

    private fun takeReconnect(): Boolean = synchronized(lock) { reconnect.also { reconnect = false } }

    private suspend fun runRequests(c: Connection) {
        while (true) {
            val request = requests.poll() ?: return
            if (request.result.isCompleted) continue
            wakeLock.renew()
            request.running = c
            try {
                request.run(c.ctx)
            } catch (e: CancellationException) {
                request.result.cancel(e)
                currentCoroutineContext().ensureActive()
                if (c.closedBy != null) throw IOException("Request cancelled", e)
            } catch (e: Throwable) {
                request.result.completeExceptionally(e)
                if (c.closedBy != null || breaksConnection(e)) throw e
            } finally {
                request.running = null
            }
        }
    }

    /** Bodies in the window for new rows (§7.2 step 7), a few batches per cycle. */
    private suspend fun bodies(ctx: SyncContext, report: SyncReport) {
        val folders = (report.newRows + listOfNotNull(ctx.sync.idleMailbox()?.id)).mapNotNull(app.folders::get)
        for (folder in folders) {
            var batches = 0
            while (batches++ < BODY_BATCHES && !workPending.get() && Bodies.fetchWindow(ctx, folder) >= BODY_BATCH) {
                ctx.progress()
            }
        }
    }

    /** After a cycle without new work: IDLE, or stay connected for a while and log out. */
    private suspend fun after(c: Connection) {
        if (mode == Mode.IDLE) {
            idle(c)
            return
        }
        _status.value = Status.Idle
        val linger = if (app.sessions.appVisible) LINGER_MS else 0L
        if (linger > 0 && withTimeoutOrNull(linger) { awaitWork() } != null) return
        logout(c)
    }

    /** One IDLE on INBOX. Returns after DONE and the tagged OK; a broken connection throws. */
    private fun idle(c: Connection) {
        c.ctx.sync.prepareIdle()
        if (workPending.get()) return
        val run = IdleRun(c)
        networkChangedDuringIdle = false
        idleRun = run
        c.inIdle = true
        try {
            c.client.idle(hooks(run), ::onIdleEvent)
        } finally {
            idleRun = null
            run.ended.complete(Unit)
        }
        c.inIdle = false
        val p = policy ?: return
        p.onIdleEnded()
        synchronized(lock) { check }?.let { action ->
            p.onChecked(action, elapsed(), alive = true)
            backoff.markHealthy()
        }
    }

    private fun hooks(run: IdleRun) = object : IdleHooks {
        override fun beforeBlockingRead() {
            if (!workPending.get() && timers.get() == 0) wakeLock.release()
        }

        override fun afterRead() {
            wakeLock.renew()
            host.onServerActivity()
        }

        // ImapConnection asks this once, right after the server's '+'.
        override fun stopRequested(): Boolean {
            if (!run.started) {
                run.started = true
                onIdleStarted(run.conn)
            }
            val stop = workPending.get()
            if (stop) scope.launch { watch(run) }
            return stop
        }
    }

    private fun onIdleStarted(c: Connection) {
        val now = elapsed()
        policy?.onIdleStarted(now, c.networkKey, c.credentials.expiresAt)
        backoff.onIdle(now)
        _status.value = Status.Idle
        host.reschedule()
    }

    private fun onIdleEvent(event: IdleEvent) {
        when (event) {
            IdleEvent.Changed -> {
                synchronized(lock) { changed = true }
                debounce()
            }
            IdleEvent.Keepalive -> {
                val p = policy ?: return
                if (p.onKeepalive(elapsed()) == Action.RE_IDLE) requestCheck(Action.RE_IDLE)
                val seconds = p.cadenceMs?.let { (it / SECOND_MS).toInt() }
                if (seconds != savedCadence) {
                    savedCadence = seconds
                    app.accounts.setServerKeepalive(accountId, seconds)
                }
            }
        }
    }

    /** Bursts of changes become one kick, 1 s after the first, under the wake lock. */
    private fun debounce() {
        if (!debouncing.compareAndSet(false, true)) return
        timers.incrementAndGet()
        wakeLock.renew()
        scope.launch {
            try {
                delay(DEBOUNCE_MS)
            } finally {
                debouncing.set(false)
                timers.decrementAndGet()
            }
            wake()
        }
    }

    /** DONE must bring the tagged OK within 20 s, else the socket is closed (§8.3, apis AF9). */
    private suspend fun watch(run: IdleRun) {
        if (!run.watched.compareAndSet(false, true)) return
        timers.incrementAndGet()
        wakeLock.renew()
        try {
            if (withTimeoutOrNull(DONE_TIMEOUT_MS) { run.ended.await() } == null) drop(run.conn, Close.DEAD)
        } finally {
            timers.decrementAndGet()
        }
    }

    private suspend fun fail(e: Throwable, c: Connection?, gmail: Boolean) {
        val now = elapsed()
        val p = policy
        val pendingCheck = synchronized(lock) { check.also { check = null } }
        if (c != null) {
            connection = null
            c.client.close()
        }
        val closedBy = c?.closedBy
        when {
            closedBy == Close.NETWORK || closedBy == Close.CANCEL || closedBy == Close.SHUTDOWN -> {
                p?.onIdleEnded()
                return
            }
            // Gmail ends a session when its token or its 24 h are over: a normal reconnect (§6, push PB17).
            c != null && gmail && isSessionExpiry(e) && now - c.connectedAt >= MINUTE_MS -> {
                c.credentials.invalidate()
                p?.onIdleEnded()
                synchronized(lock) { immediate = true }
                return
            }
            c?.inIdle == true && p != null -> when {
                pendingCheck != null -> p.onChecked(pendingCheck, now, alive = false)
                closedBy == null -> p.onServerClosed(now, networkChangedDuringIdle)
            }
        }
        p?.onIdleEnded()
        val error = MailErrors.classify(e, host.network()?.validated ?: false)
        when (error) {
            is MailError.AuthRequired -> {
                authStopped = true
                backoff.onFailure(now, Backoff.Kind.AUTH)
                app.accounts.setState(accountId, AccountState.SIGN_IN_NEEDED, error.message)
                reportProblem(error)
                failRequests(error)
                _status.value = Status.Problem(error)
            }
            is MailError.Tls -> {
                app.accounts.setState(accountId, AccountState.TLS_PROBLEM, null)
                // Every failure goes to the engine: it alerts after 1 h or on a second network (push PB9).
                problemReported = true
                host.onAccountProblem(accountId, error)
                retry(error, Backoff.Kind.TLS, now)
                _status.value = Status.Problem(error)
            }
            is MailError.Setup -> {
                relist = true
                if (app.accounts.get(accountId)?.state != AccountState.SETUP_PROBLEM) {
                    app.accounts.setState(accountId, AccountState.SETUP_PROBLEM, error.message)
                }
                reportProblem(error)
                retry(error, Backoff.Kind.THROTTLED, now)
                _status.value = Status.Problem(error)
            }
            is MailError.Throttled -> retry(error, Backoff.Kind.THROTTLED, now)
            else -> retry(error, Backoff.Kind.NETWORK, now)
        }
    }

    /** Backoff: the first steps wait here under the wake lock, later ones wait for the alarm (push PB10). */
    private suspend fun retry(error: MailError, kind: Backoff.Kind, now: Long) {
        val retry = backoff.onFailure(now, kind) ?: return
        if (!retry.inProcess) {
            failRequests(error)
            _status.value = Status.Waiting(retry.at)
            host.reschedule()
            return
        }
        _status.value = Status.Connecting
        val wait = retry.at - now
        if (wait <= 0) return
        // A kick from before the failure must not cut the wait short; queued requests stay queued.
        workPending.set(false)
        timers.incrementAndGet()
        wakeLock.renew(wait + SECOND_MS)
        try {
            withTimeoutOrNull(wait) { awaitWork() }
        } finally {
            timers.decrementAndGet()
        }
    }

    private fun reportProblem(error: MailError) {
        if (problemReported) return
        problemReported = true
        host.onAccountProblem(accountId, error)
    }

    private fun clearProblem() {
        problemReported = false
        host.onAccountProblem(accountId, null)
    }

    private fun logout(c: Connection) {
        connection = null
        c.closedBy = Close.SHUTDOWN
        try {
            c.client.logout()
        } catch (_: IOException) {
            // The connection is gone either way.
        }
        policy?.onIdleEnded()
        _status.value = Status.Offline
    }

    /** Closes [c] from a background thread; the loop sees the error and acts on [why]. */
    private fun drop(c: Connection, why: Close) {
        if (c.closedBy == null) c.closedBy = why
        closer.launch { c.client.close() }
    }

    /** Waits for a kick; the wake lock is free unless a short timer runs. */
    private suspend fun awaitWork() {
        if (timers.get() == 0) wakeLock.release()
        while (!workPending.get()) signal.receive()
    }

    /** Op run_at and draft send_at (epoch ms) as elapsedRealtime. Times that the last run could not use are dropped. */
    private fun refreshWorkDue() {
        val wall = System.currentTimeMillis()
        val now = elapsed()
        val due = DueTimes.min(Ops.nextRunAt(app, accountId), Sender.nextSendAt(app, accountId))
            ?.let { now + (it - wall) }
        // An op that was due at the last run but did not run (e.g. its folder is guarded) must not loop.
        workDueAt = due?.takeIf { lastWorkRunAt == 0L || it > lastWorkRunAt }
    }

    /** Work due within a minute waits here under the wake lock; later work waits for the alarm (push PB7). */
    private fun scheduleSoonWork() {
        val delayMs = (workDueAt ?: return) - elapsed()
        if (delayMs <= 0 || delayMs > SOON_MS) return
        soonTimer?.cancel()
        timers.incrementAndGet()
        wakeLock.renew(delayMs + SECOND_MS)
        soonTimer = scope.launch {
            try {
                delay(delayMs)
            } finally {
                timers.decrementAndGet()
            }
            wake()
        }
    }

    private fun failRequests(error: Throwable) {
        while (true) {
            val request = requests.poll() ?: return
            request.result.completeExceptionally(error)
        }
    }

    private class Request<T>(private val block: suspend SyncContext.() -> T) {
        val result = CompletableDeferred<T>()

        @Volatile
        var running: Connection? = null

        suspend fun run(ctx: SyncContext) {
            result.complete(ctx.block())
        }
    }

    private class Connection(
        val client: ImapClient,
        val ctx: SyncContext,
        val credentials: Credentials,
        /** Network.getNetworkHandle() of the default network at connect time. */
        val network: Long,
        val networkKey: String,
    ) {
        val connectedAt = elapsed()

        @Volatile var closedBy: Close? = null
        var fresh = true
        var inIdle = false
    }

    private class IdleRun(val conn: Connection) {
        val ended = CompletableDeferred<Unit>()
        val watched = AtomicBoolean()

        @Volatile var started = false
    }

    /** Why the app closed a connection. */
    private enum class Close {
        /** Network change or loss: reconnect or park, without a backoff step. */
        NETWORK,

        /** DONE got no tagged OK in time: a dead connection. */
        DEAD,

        /** The caller of [request] gave up. */
        CANCEL,

        /** LOGOUT or [close]. */
        SHUTDOWN,
    }

    private companion object {
        const val SECOND_MS = 1_000L
        const val MINUTE_MS = 60 * SECOND_MS
        const val DEFAULT_POLL_MS = 15 * MINUTE_MS
        const val DEBOUNCE_MS = SECOND_MS
        const val DONE_TIMEOUT_MS = 20 * SECOND_MS
        const val LINGER_MS = 2 * MINUTE_MS
        const val SOON_MS = MINUTE_MS
        const val STEP_MAX_MS = 10 * MINUTE_MS
        const val BODY_BATCH = 25
        const val BODY_BATCHES = 8

        val SESSION_EXPIRED = Regex("expired", RegexOption.IGNORE_CASE)

        /** Socket closes from other threads; outlives a closed session. */
        val closer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun elapsed() = SystemClock.elapsedRealtime()

        fun isSessionExpiry(e: Throwable) = e is ImapByeException && SESSION_EXPIRED.containsMatchIn(e.message.orEmpty())

        /** A NO or BAD ends one command; every other I/O error leaves the connection unusable. */
        fun breaksConnection(e: Throwable) = e is IOException && e !is ImapNoException && e !is ImapBadException
    }
}
