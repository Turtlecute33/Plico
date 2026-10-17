package app.plico.sync

import android.os.SystemClock
import android.util.Log
import app.plico.PlicoApp
import app.plico.R
import app.plico.account.AndroidSocketHooks
import app.plico.account.Credentials
import app.plico.account.MailError
import app.plico.account.MailErrors
import app.plico.account.ProviderTable
import app.plico.account.credentialsFor
import app.plico.account.traits
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.mail.imap.IdleEvent
import app.plico.mail.imap.IdleHooks
import app.plico.mail.imap.ImapByeException
import app.plico.mail.imap.ImapClient
import app.plico.push.policy.Backoff
import app.plico.push.policy.Coalescing
import app.plico.push.policy.DueTimes
import app.plico.push.policy.KeepalivePolicy
import app.plico.push.policy.KeepalivePolicy.Action
import app.plico.push.policy.NetworkRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.withTimeout
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
 * Loop: connect → authenticate → capabilities → COMPRESS → ENABLE CONDSTORE → work → IDLE (mode [Mode.IDLE], on a
 * server with IDLE) or LOGOUT (the other modes; after 2 min without work while the UI is visible). A kick sets the
 * work-pending flag that IDLE checks before it starts and right after `+` (push PB1).
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
            if (value == Mode.POLL) {
                requestPoll(elapsed())
            } else {
                // A failed poll waits for its retry only in POLL mode.
                synchronized(lock) { poll = false }
                wake()
            }
            host.reschedule()
        }

    // A failure in a task must not end the process (and push); only the class name is logged (DESIGN §12).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(LOG_TAG, "session: ${e.javaClass.simpleName}")
        },
    )
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
    @Volatile private var lingering = false

    /** Earliest op run_at or send_at, as elapsedRealtime; null when none can run. */
    @Volatile private var workDueAt: Long? = null
    @Volatile private var lastPollAt = 0L
    @Volatile private var pollMs = DEFAULT_POLL_MS

    // Loop-only state.
    private var lastListAt: Long? = null
    private var lastSweepAt: Long? = null
    private var idleNetwork: Long? = null
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

    /** Moves without COPYUID on the current connection (DESIGN §7.4); a new connection starts empty. */
    internal val movedIn = MovedIn()

    internal val store: OpStore by lazy { OpStore.of(app) }

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

    /**
     * Engine wake (alarm, server byte on any session, network, app visible): runs due refresh/poll/reconnect.
     * Synchronous and without I/O (it only sets flags and renews the session lock), so that the engine's `alarm`
     * lock ends only after this session holds its own.
     */
    fun onWake(now: Long) {
        if (closed) return
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
        // A loop that an error ended starts again: push must not stop until the next kick.
        if (backoff.isDue(now) || workDueAt?.let { it <= now } == true || (mode == Mode.IDLE && loop == null)) wake()
    }

    fun onNetworkEvent(event: NetworkRules.Event, network: NetState?) {
        if (closed) return
        if (idleRun != null) networkChangedDuringIdle = true
        // A connection that ended just before this event (a socket that died with its network) is no NAT timeout.
        policy?.onNetworkChanged()
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
        val p = policy
        // Between two IDLEs (a cycle runs) the policy has no due time; an alarm must stay armed all the same, so
        // that a CPU that sleeps before the next IDLE arms it wakes up again (push PB4). The same alarm starts a
        // loop again that an error ended (onWake).
        val restart = mode == Mode.IDLE && !closed && (connection != null || loop == null)
        val keepalive = p?.nextDueAt() ?: if (restart) now + (p?.intervalMs ?: DEFAULT_POLL_MS) else null
        // Past times are left out: the wake that is due now handles them, and an alarm in the past would repeat.
        return DueTimes.min(
            listOf(keepalive, backoff.retryAt, pollAt, workDueAt).map { it?.takeIf { t -> t > now } },
        )
    }

    /**
     * Runs [block] on the loop with a connected, authenticated client (connects on demand), after due ops and
     * sends. Exceptions of [block] reach the caller unchanged; a failed connect gives a [MailError]. A caller
     * that is cancelled while [block] runs gets no result, and the block runs to its end (a body or an
     * attachment is stored for later); with [closeOnCancel] the connection closes instead (the Cancel of a
     * server search, DESIGN §7.7), and the loop reconnects without a backoff.
     */
    internal suspend fun <T> request(closeOnCancel: Boolean = false, block: suspend SyncContext.() -> T): T {
        if (closed) throw CancellationException("Session closed")
        val request = Request(block)
        requests.add(request)
        kick(Reason.OPEN_FOLDER)
        try {
            return request.result.await()
        } catch (e: CancellationException) {
            if (request.result.completeExceptionally(e)) {
                requests.remove(request)
                if (closeOnCancel) request.running?.let { drop(it, Close.CANCEL) }
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

    internal fun progress(timeoutMs: Long = PROGRESS_MS) = wakeLock.renew(timeoutMs)

    /** The app went to the background: a linger after work ends now, with the CPU awake (push PB4). */
    internal fun onHidden() {
        if (!lingering) return
        wakeLock.renew()
        signal.trySend(Unit)
    }

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
            if (loop != null || closed) return
            // Started after the assignment, so that the end of the loop, which clears it, always comes later.
            loop = scope.launch(start = CoroutineStart.LAZY) { run() }.also { it.start() }
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

    /**
     * The loop. It ends when the session closes, the account is gone, or an error escapes (e.g. the database
     * fails while an error is recorded); the next kick starts it again, and in IDLE mode also the next wake
     * (nextDueAt keeps an alarm for it).
     */
    private suspend fun run() {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failRequests(e)
            throw e
        } finally {
            // A connection that connect() opened while close() ran must not stay logged in.
            connection?.let {
                connection = null
                it.client.close()
            }
            policy?.onIdleEnded()
            wakeLock.release()
            synchronized(lock) { loop = null }
            host.reschedule()
        }
    }

    private suspend fun work() {
        val first = app.accounts.get(accountId) ?: return
        policy = policyFor(first)
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
                fail(IOException("Work took too long", e), c, first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e, c, first)
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
                        // The socket goes out on the default network of now; the debounced one may be gone already.
                        connect(account, host.currentNetwork() ?: network)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        fail(e, null, account)
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
        val scope = KeepalivePolicy.scopeOf(account.provider, account.servers.imap.host)
        if (policy?.scope != scope) {
            // The servers changed (sign-in with new servers): their learned intervals and their folders are others.
            policy?.onIdleEnded()
            policy = policyFor(account)
            relist = true
        }
        movedIn.clear()
        val credentials = credentialsFor(app, account)
        val client = ImapClient.connect(account.servers.imap, AndroidSocketHooks, debugLog("imap"))
        try {
            credentials.authImap(client)
            client.requireCapabilities()
            // The generic MOVE and expunge rules must never run on Gmail's All Mail (X-GM-EXT-1 never promotes, D18).
            if (!account.isGmail && client.isGmail) throw MailError.Setup(app.getString(R.string.account_error_gmail_server))
            // NetEase refuses SELECT until the client sends ID; only the minimal form, only to these hosts (§11.2).
            if (ProviderTable.needsId(account.servers.imap.host)) client.id(listOf("name" to "Mail"))
            client.compress()
            if ("CONDSTORE" in client.caps && "ENABLE" in client.caps) client.enable("CONDSTORE")
        } catch (e: Throwable) {
            client.close()
            throw e
        }
        // A server without IDLE polls (D7); the push engine reads the stored value and sets the mode.
        val idle = "IDLE" in client.caps
        if (idle != account.idle) {
            app.accounts.setIdle(accountId, idle)
            app.push.onAccountsChanged()
        }
        val ctx = SyncContext(app, account, client, credentials, this)
        connection = Connection(client, ctx, credentials, network.handle, network.key)
        // Gmail setup states are decided by the folder list. On other servers a setup problem comes from the
        // connection (a missing capability, a Gmail server) or from a mailbox command (Microsoft "not connected"):
        // a working connection clears it, and the folder list or the next SELECT sets it again when it stays.
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
        // The lock stays held after the steps: only the blocking read of IDLE or the wait for work releases it,
        // so the CPU cannot sleep before IDLE has started and the alarm is armed (push PB4).
        wakeLock.renew()
        withTimeout(STEP_MAX_MS) {
            if (plan.listFolders) {
                relist = false
                listFolders(c)
                lastListAt = now
            }
            if (plan.fastPath) {
                sync.fastPath(report)
                publish(sync, report)
            }
            Ops.run(c.ctx)
            Sender.run(c.ctx)
        }
        runRequests(c)
        wakeLock.renew()
        withTimeout(STEP_MAX_MS) {
            if (plan.fastPath) sync.idleMailboxFlags(plan.fullFlags, report)
            if (plan.notifySweep) sync.notifySweep(report)
            if (plan.allMailStatus) sync.checkAllMail(report)
            if (plan.fullSweep) {
                sync.fullSweep(report, plan.fullFlags)
                lastSweepAt = now
            }
            publish(sync, report)
            bodies(c.ctx, report)
            if (plan.backfill && !workPending.get()) sync.backfillStep(report)
            if (mode == Mode.IDLE && sync.missedMail(report)) publish(sync, report)
        }
        if (trigger.poll) {
            // Only a poll that ran to its end is done: a failed one stays wanted, so the backoff retries it.
            synchronized(lock) { poll = false }
            backoff.markHealthy()
        }
        refreshWorkDue()
        // Work that became due during the cycle (an undo window that ended, the follow-ups of a send) runs now.
        if (workDueAt?.let { it <= elapsed() } == true) workPending.set(true)
        scheduleSoonWork()
        if (problemReported && app.accounts.get(accountId)?.state == AccountState.OK) clearProblem()
        host.reschedule()
    }

    /** LIST (step 1). A Gmail setup problem stops the sync, but SMTP needs no folder: due sends still go. */
    private suspend fun listFolders(c: Connection) {
        try {
            c.ctx.sync.refreshFolders()
        } catch (e: MailError.Setup) {
            Sender.run(c.ctx)
            throw e
        }
    }

    /** The keepalive policy of [account]: its start interval and its scope of learned intervals (§8.3). */
    private fun policyFor(account: Account) = KeepalivePolicy(
        account.traits.idleStartMinutes,
        KeepalivePolicy.scopeOf(account.provider, account.servers.imap.host),
        app.learnedIntervals,
    )

    private fun takeTriggers(c: Connection): SyncTrigger = synchronized(lock) {
        workPending.set(false)
        SyncTrigger(c.fresh, changed, userRefresh, poll, check).also {
            c.fresh = false
            changed = false
            userRefresh = false
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
                if (c.closedBy != null || OpErrors.endsConnection(e, c.client)) throw e
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

    /**
     * After a cycle without new work: IDLE, or log out; while the UI is visible, stay connected for 2 min first.
     * The linger ends when the app goes to the background ([onHidden]). Its wait holds no wake lock and its
     * clock stops while the CPU sleeps: when it ends much later, the socket may be dead, and LOGOUT would wait
     * for the socket timeout, so the socket is only closed.
     */
    private suspend fun after(c: Connection) {
        // Without IDLE the account polls (D7); the engine switches the mode soon after the first connection.
        if (mode == Mode.IDLE && "IDLE" in c.client.caps) {
            idle(c)
            return
        }
        _status.value = Status.Idle
        lingering = true
        val start = elapsed()
        val kicked = try {
            app.sessions.appVisible && withTimeoutOrNull(LINGER_MS) { awaitWork { !app.sessions.appVisible } } == true
        } finally {
            lingering = false
        }
        wakeLock.renew()
        val slept = elapsed() - start > LINGER_MS + SECOND_MS
        if (kicked && !slept) return
        logout(c, polite = !slept)
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
        // A failure of the last IDLE counts only when this one runs on the same network (KeepalivePolicy).
        if (c.network != idleNetwork) policy?.onNetworkChanged()
        idleNetwork = c.network
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
                // The engine may have armed the dead-peer alarm from the keepalive before this one.
                host.reschedule()
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

    /** [account]: the account as the loop read it; it decides the session-expiry and server-close rules. */
    private suspend fun fail(e: Throwable, c: Connection?, account: Account) {
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
            // Gmail ends a session when its token or its 24 h are over, and an OAuth server when its token is over:
            // a normal reconnect (§6, push PB17).
            c != null && sessionExpiry(account) && isSessionExpiry(e) && now - c.connectedAt >= MINUTE_MS -> {
                c.credentials.invalidate()
                p?.onIdleEnded()
                synchronized(lock) { immediate = true }
                return
            }
            c?.inIdle == true && p != null -> when {
                pendingCheck != null -> p.onChecked(pendingCheck, now, alive = false)
                closedBy == null && isIdleTimeout(e, account) -> p.onServerClosed(now, networkChangedDuringIdle)
            }
        }
        p?.onIdleEnded()
        val error = MailErrors.localized(app, MailErrors.classify(e, host.network()?.validated ?: false))
        // The database write comes last: when it fails, the alert and the retry are already done.
        when (error) {
            is MailError.AuthRequired -> {
                authStopped = true
                backoff.onFailure(now, Backoff.Kind.AUTH)
                reportProblem(error)
                failRequests(error)
                _status.value = Status.Problem(error)
                app.accounts.setState(accountId, AccountState.SIGN_IN_NEEDED, error.message)
            }
            is MailError.Tls -> {
                // Every failure goes to the engine: it alerts after 1 h or on a second network (push PB9).
                problemReported = true
                host.onAccountProblem(accountId, error)
                retry(error, Backoff.Kind.TLS, now)
                _status.value = Status.Problem(error)
                app.accounts.setState(accountId, AccountState.TLS_PROBLEM, null)
            }
            is MailError.Setup -> {
                relist = true
                reportProblem(error)
                retry(error, Backoff.Kind.THROTTLED, now)
                _status.value = Status.Problem(error)
                if (app.accounts.get(accountId)?.state != AccountState.SETUP_PROBLEM) {
                    app.accounts.setState(accountId, AccountState.SETUP_PROBLEM, error.message)
                }
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

    /** LOGOUT, or without [polite] only the close of the socket. */
    private fun logout(c: Connection, polite: Boolean = true) {
        connection = null
        c.closedBy = Close.SHUTDOWN
        try {
            if (polite) c.client.logout() else c.client.close()
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

    /** Waits for a kick (true), or until [stop] at a signal; the wake lock is free unless a short timer runs. */
    private suspend fun awaitWork(stop: () -> Boolean = { false }): Boolean {
        while (!workPending.get()) {
            if (stop()) return false
            if (timers.get() == 0) wakeLock.release()
            signal.receive()
        }
        return true
    }

    /**
     * Op run_at and draft send_at (epoch ms) as elapsedRealtime. A past time stays: work that a failed cycle did
     * not run must make the next connection. An op that cannot run (its folder is guarded) is not counted
     * (Ops.nextRunAt), and a cycle that ends runs or reschedules every due op and send, so nothing loops.
     */
    private fun refreshWorkDue() {
        val wall = System.currentTimeMillis()
        workDueAt = DueTimes.min(Ops.nextRunAt(store, accountId), app.drafts.nextSendAt(accountId))
            ?.let { elapsed() + (it - wall) }
    }

    /** Work due within a minute waits here under the wake lock; later work waits for the alarm (push PB7). */
    private fun scheduleSoonWork() {
        val delayMs = (workDueAt ?: return) - elapsed()
        if (delayMs <= 0 || delayMs > SOON_MS) return
        soonTimer?.cancel()
        timers.incrementAndGet()
        wakeLock.renew(delayMs + SECOND_MS)
        soonTimer = scope.launch {
            delay(delayMs)
            wake()
        }.also { job ->
            // Also when the job is cancelled before it starts, which skips its body.
            job.invokeOnCompletion { timers.decrementAndGet() }
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

        /** Gmail, and every OAuth or device sign-in: an "expired" BYE is the end of a token or session. */
        fun sessionExpiry(account: Account) = account.isGmail || account.auth != AuthKind.PASSWORD

        /** A BYE text of an idle or inactivity timeout (other servers also send BYE when they shut down). */
        val IDLE_BYE = Regex("idle|inactiv|autologout|timeout", RegexOption.IGNORE_CASE)

        /**
         * True when the end of an IDLE counts as a NAT or server timeout for the keepalive interval. Riseup and
         * Gmail: every end that the app did not cause. Other servers: an end without BYE (EOF, reset), or a BYE
         * whose text names a timeout; a shutdown BYE must not lock a short interval for 24 h.
         */
        fun isIdleTimeout(e: Throwable, account: Account): Boolean =
            account.provider == Provider.RISEUP || account.provider == Provider.GMAIL ||
                e !is ImapByeException || IDLE_BYE.containsMatchIn(e.message.orEmpty())

        const val LOG_TAG = "Plico"
    }
}
