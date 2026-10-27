package app.plico.push

import kotlin.test.Test
import kotlin.test.assertEquals

class QueueJobsTest {
    private class FakeJobs(var quota: Boolean = true) : QueueJobs.Jobs {
        val calls = mutableListOf<String>()

        override fun schedule(delayMs: Long, expedited: Boolean): Boolean {
            calls += if (expedited) "expedited" else "regular $delayMs"
            return !expedited || quota
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    @Test
    fun soonWorkStartsAnExpeditedJobAtOnce() {
        val jobs = FakeJobs()
        QueueJobs(jobs).schedule(at = 20_000, now = 0)
        assertEquals(listOf("expedited"), jobs.calls)
    }

    @Test
    fun overdueWorkAlsoStartsAtOnce() {
        val jobs = FakeJobs()
        QueueJobs(jobs).schedule(at = 1_000, now = 5_000)
        assertEquals(listOf("expedited"), jobs.calls)
    }

    @Test
    fun withoutQuotaARegularJobRunsAtOnce() {
        val jobs = FakeJobs(quota = false)
        QueueJobs(jobs).schedule(at = 5_000, now = 0)
        assertEquals(listOf("expedited", "regular 0"), jobs.calls)
    }

    @Test
    fun laterWorkWaitsInARegularJob() {
        val jobs = FakeJobs()
        QueueJobs(jobs).schedule(at = 120_000, now = 0)
        assertEquals(listOf("regular 120000"), jobs.calls)
    }

    @Test
    fun theSameDueTimeIsScheduledOnce() {
        val jobs = FakeJobs()
        val queue = QueueJobs(jobs)
        queue.schedule(at = 120_000, now = 0)
        queue.schedule(at = 120_400, now = 10)
        queue.schedule(at = 10_000, now = 20)
        assertEquals(listOf("regular 120000", "expedited"), jobs.calls)
    }

    @Test
    fun noWorkCancelsOnlyAScheduledJob() {
        val jobs = FakeJobs()
        val queue = QueueJobs(jobs)
        queue.schedule(at = null, now = 0)
        queue.schedule(at = 120_000, now = 0)
        queue.schedule(at = null, now = 0)
        queue.schedule(at = null, now = 0)
        assertEquals(listOf("regular 120000", "cancel"), jobs.calls)
    }

    @Test
    fun aRunningJobIsNotReplaced() {
        val jobs = FakeJobs()
        val queue = QueueJobs(jobs)
        queue.schedule(at = 20_000, now = 0)
        queue.started()
        queue.schedule(at = 20_000, now = 1_000)
        queue.schedule(at = null, now = 2_000)
        queue.ended()
        // After the job, the next work gets a new job.
        queue.schedule(at = 200_000, now = 80_000)
        assertEquals(listOf("expedited", "regular 120000"), jobs.calls)
    }
}
