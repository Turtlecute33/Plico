package app.plico.push

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.plico.app
import app.plico.push.policy.DueTimes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Runs queued sends and ops while push does not run (paused, or stopped from the Task Manager; DESIGN §8.4,
 * PLICO-UX-003). Without the service, Android can freeze or kill the cached process before an undo window
 * ends. The job kicks every session and keeps the process running while a session holds its wake lock: it
 * connects, sends, or waits for work due within 1 min. Not exported; only the system can bind it.
 */
class QueueJob : JobService() {
    @Volatile private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = app
        val engine = app.push
        // The service runs the queue itself.
        if (engine.running) return false
        val queue = engine.queue
        queue.started()
        work = engine.launch {
            val later: Long?
            try {
                // After a process start no session exists yet: a kick makes each one read its queue. A session that
                // works already needs none (a kick in a cycle runs one more cycle).
                app.accounts.all().map { app.sessions[it.id] }.filterNot { it.holdsWakeLock }.forEach { it.kick() }
                do {
                    delay(CHECK_MS)
                } while (!engine.running && app.sessions.all().any { it.holdsWakeLock })
                val at = if (engine.running) null else DueTimes.min(app.sessions.all().map { it.workAt() })
                val now = SystemClock.elapsedRealtime()
                later = at?.takeIf { it > now }
                // Due work that no session could run (no connection): the job backs off. Later work gets a new job.
                jobFinished(params, at != null && at <= now)
            } finally {
                queue.ended()
            }
            later?.let(queue::schedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private companion object {
        const val CHECK_MS = 1_000L
    }
}

/** Schedules the one [QueueJob]; the engine calls it in place of the alarm while push does not run. Thread-safe. */
internal class QueueJobs internal constructor(private val jobs: Jobs) {
    constructor(context: Context) : this(AndroidJobs(context))

    /** The platform calls, replaced in unit tests. */
    internal interface Jobs {
        /** True when the job is scheduled. */
        fun schedule(delayMs: Long, expedited: Boolean): Boolean
        fun cancel()
    }

    private var scheduledAt: Long? = null

    // A schedule with the ID of a running job stops it, so a running job schedules the next one at its end.
    @Volatile private var active = false

    @Synchronized
    fun started() {
        active = true
        scheduledAt = null
    }

    fun ended() {
        active = false
    }

    /**
     * A job for work at [at] (elapsedRealtime; a past time means now), or none when [at] is null. Work within
     * 1 min gets an expedited job at once (a regular one when the expedited quota is used up); later work a
     * regular job at its time. Both need a network.
     */
    @Synchronized
    fun schedule(at: Long?, now: Long = SystemClock.elapsedRealtime()) {
        if (active) return
        val last = scheduledAt
        if (at == null) {
            if (last != null) cancel()
            return
        }
        if (last != null && abs(last - at) < SAME_MS) return
        val delay = at - now
        try {
            val ok = if (delay <= SOON_MS) {
                runCatching { jobs.schedule(0, expedited = true) }.getOrDefault(false) ||
                    jobs.schedule(0, expedited = false)
            } else {
                jobs.schedule(delay, expedited = false)
            }
            scheduledAt = if (ok) at else null
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "queue job: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun cancel() {
        scheduledAt = null
        jobs.cancel()
    }

    private class AndroidJobs(private val context: Context) : Jobs {
        private val scheduler = context.getSystemService(JobScheduler::class.java)

        override fun schedule(delayMs: Long, expedited: Boolean): Boolean {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, QueueJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .apply { if (expedited) setExpedited(true) else if (delayMs > 0) setMinimumLatency(delayMs) }
                .build()
            // RESULT_FAILURE for an expedited job when its quota is used up.
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }

        override fun cancel() = scheduler.cancel(JOB_ID)
    }

    private companion object {
        const val JOB_ID = 1

        /** As in AccountSession: work due within 1 min waits in the process, under the session wake lock. */
        const val SOON_MS = 60_000L

        /** Due times of one queue item differ by a few ms from one reading to the next. */
        const val SAME_MS = 1_000L
    }
}
