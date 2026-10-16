package app.plico.mail.imap

import app.plico.mail.net.MailStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImapIdleTest {

    private val fake = FakeStream()
    private val logs = ArrayList<String>()
    private val conn = ImapConnection(fake) { logs += it }
    private val events = ArrayList<IdleEvent>()

    init {
        fake.server("* OK [CAPABILITY IMAP4rev1 IDLE] Dovecot ready.")
        conn.greeting()
    }

    /** Records hook calls and the read timeout seen before each blocking read. */
    private open inner class Hooks(private val stop: Boolean = false) : IdleHooks {
        var before = 0
        var after = 0
        var stopChecks = 0
        val timeouts = ArrayList<Int>()

        override fun beforeBlockingRead() {
            before++
            timeouts += fake.readTimeoutMs
        }

        override fun afterRead() {
            after++
        }

        override fun stopRequested(): Boolean {
            stopChecks++
            return stop
        }
    }

    @Test
    fun eventsUntilDone() {
        val unsolicited = ArrayList<String>()
        conn.onUnsolicited = { unsolicited += it.keyword }
        fake.client("A1 IDLE")
            .server("+ idling", "* 3 EXISTS", "* OK Still here", "* 2 EXPUNGE", "* 1 FETCH (FLAGS ())", "* 1 RECENT")
            .client("DONE")
            .server("* OK [HIGHESTMODSEQ 9] ignored", "* FLAGS (\\Seen)")
            .server("A1 OK Idle completed (0.001 + 9.512 + 9.511 secs).")
        val hooks = Hooks()
        conn.idle(hooks) { event ->
            events += event
            if (events.size == 5) conn.done()
        }
        assertEquals(
            listOf(IdleEvent.Changed, IdleEvent.Keepalive, IdleEvent.Changed, IdleEvent.Changed, IdleEvent.Changed),
            events,
        )
        assertEquals(listOf("EXISTS", "EXPUNGE", "FETCH", "RECENT"), unsolicited)
        assertEquals(9, hooks.before)
        assertEquals(hooks.before, hooks.after)
        assertEquals(1, hooks.stopChecks)
        assertEquals(listOf(60_000) + List(8) { 0 }, hooks.timeouts)
        assertEquals(listOf(0, 60_000), fake.timeouts)
        assertEquals(listOf("<<< * OK [CAPABILITY]", ">>> A1 IDLE", ">>> DONE", "<<< A1 OK"), logs)
        fake.finish()
    }

    @Test
    fun stopRequestedSendsDoneRightAfterContinuation() {
        fake.client("A1 IDLE").server("+ idling").client("DONE").server("A1 OK Idle completed.")
        conn.idle(Hooks(stop = true)) { events += it }
        assertEquals(emptyList<IdleEvent>(), events)
        fake.finish()
    }

    @Test
    fun doneBeforeContinuationIsSentAfterIt() {
        fake.client("A1 IDLE").server("+ idling").client("DONE").server("A1 OK Idle completed.")
        val hooks = object : Hooks() {
            override fun beforeBlockingRead() {
                super.beforeBlockingRead()
                conn.done() // first call: IDLE is written, '+' not read yet
            }
        }
        conn.idle(hooks) { events += it }
        assertEquals(2, hooks.before)
        fake.finish() // exactly one DONE
    }

    @Test
    fun existsBeforeContinuationIsAChange() {
        fake.client("A1 IDLE").server("* 4 EXISTS", "+ idling").client("DONE").server("A1 OK Idle completed.")
        conn.idle(Hooks()) { event ->
            events += event
            conn.done()
        }
        assertEquals(listOf<IdleEvent>(IdleEvent.Changed), events)
        fake.finish()
    }

    @Test
    fun byeDuringIdleThrowsAndRestoresTheTimeout() {
        fake.client("A1 IDLE").server("+ idling", "* BYE Server shutting down.")
        val e = assertFailsWith<ImapByeException> { conn.idle(Hooks()) { events += it } }
        assertEquals("Server shutting down.", e.message)
        assertEquals(60_000, fake.readTimeoutMs)
        conn.done() // no IDLE runs: nothing is written
        fake.finish()
    }

    @Test
    fun rejectedIdleThrows() {
        fake.client("A1 IDLE").server("A1 BAD Unknown command")
        assertFailsWith<ImapBadException> { conn.idle(Hooks()) { events += it } }
        conn.done()
        assertEquals(60_000, fake.readTimeoutMs)
        fake.finish()
    }

    @Test
    fun doneOutsideIdleWritesNothing() {
        conn.done()
        fake.client("A1 NOOP").server("A1 OK done")
        conn.execute(Command.raw("NOOP"))
        fake.finish()
    }

    @Test
    fun commandsContinueAfterIdle() {
        fake.client("A1 IDLE").server("+ idling").client("DONE").server("A1 OK Idle completed.")
        fake.client("A2 NOOP").server("A2 OK NOOP completed.")
        conn.idle(Hooks(stop = true)) { events += it }
        conn.execute(Command.raw("NOOP"))
        fake.finish()
    }

    @Test
    fun doneFromOtherThreadsIsSentOnce() {
        fake.client("A1 IDLE").server("+ idling").client("DONE").server("A1 OK Idle completed.")
        val continued = CountDownLatch(1)
        val hooks = object : Hooks() {
            override fun afterRead() {
                super.afterRead()
                continued.countDown()
            }
        }
        var error: Throwable? = null
        val worker = thread { error = runCatching { conn.idle(hooks) { events += it } }.exceptionOrNull() }
        assertTrue(continued.await(5, TimeUnit.SECONDS))
        val kickers = List(4) { thread { conn.done() } }
        kickers.forEach { it.join() }
        worker.join(TimeUnit.SECONDS.toMillis(5))
        error?.let { throw it }
        conn.done()
        fake.finish()
    }

    @Test
    fun errorDuringIdleClosesTheConnectionWithoutDone() {
        fake.client("A1 IDLE").server("+ idling", "* 3 EXISTS")
        assertFailsWith<IllegalStateException> { conn.idle(Hooks()) { error("hook failed") } }
        assertTrue(fake.closed)
        assertEquals(60_000, fake.readTimeoutMs)
        // Never a command line that the server, still in IDLE, would take for a bad DONE.
        assertFailsWith<IOException> { conn.execute(Command.raw("NOOP")) }
        conn.done()
        fake.finish()
    }

    @Test
    fun failedDoneClosesTheConnectionInsteadOfThrowing() {
        val server = FakeStream().server("* OK ready").client("A1 IDLE").server("+ idling").client("DONE")
        val pipe = Pipe(server)
        val c = ImapConnection(pipe)
        c.greeting()
        val active = CountDownLatch(1)
        val hooks = object : IdleHooks {
            var reads = 0

            override fun beforeBlockingRead() {
                if (++reads == 2) active.countDown() // '+' was handled
            }
        }
        var error: Throwable? = null
        val worker = thread { error = runCatching { c.idle(hooks) {} }.exceptionOrNull() }
        assertTrue(active.await(5, TimeUnit.SECONDS))
        pipe.failWrites = true
        c.done() // the kicker thread: must not throw
        worker.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(worker.isAlive)
        assertIs<IOException>(error)
        assertTrue(server.closed)
        assertFailsWith<IOException> { c.execute(Command.raw("NOOP")) }
    }

    @Test
    fun doneDoesNotWaitBehindACommandWrite() {
        val server = FakeStream().server("* OK [CAPABILITY IMAP4rev1 LITERAL+] ready")
            .client("A1 APPEND \"Sent\" {5+}\r\nhello").server("A1 OK done")
        val pipe = Pipe(server)
        val c = ImapConnection(pipe)
        c.greeting()
        val gate = CountDownLatch(1)
        pipe.gate = gate
        var error: Throwable? = null
        val worker = thread {
            error = runCatching {
                c.execute(Command.build { raw("APPEND"); string("Sent"); bytes("hello".toByteArray()) })
            }.exceptionOrNull()
        }
        assertTrue(pipe.blocked.await(5, TimeUnit.SECONDS)) // a slow upload holds the write
        val kicker = thread { c.done() }
        kicker.join(1_000)
        val waited = kicker.isAlive
        gate.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(5))
        kicker.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(waited, "done() waited for the command write")
        error?.let { throw it }
        server.finish()
    }

    /** [FakeStream] whose writes can wait at a [gate] (a slow upload) or fail (a lost network). */
    private class Pipe(private val fake: FakeStream) : MailStream by fake {
        @Volatile
        var failWrites = false

        @Volatile
        var gate: CountDownLatch? = null
        val blocked = CountDownLatch(1)

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                before()
                fake.output.write(b, off, len)
            }

            override fun flush() {
                before()
                fake.output.flush()
            }

            private fun before() {
                gate?.let {
                    blocked.countDown()
                    it.await()
                }
                if (failWrites) throw SocketException("Broken pipe")
            }
        }
    }
}
