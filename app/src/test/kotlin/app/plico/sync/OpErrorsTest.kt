package app.plico.sync

import app.plico.account.MailError
import app.plico.mail.imap.ImapBadException
import app.plico.mail.imap.ImapByeException
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapConnection
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.ImapProtocolException
import app.plico.mail.imap.RespCode
import app.plico.mail.net.MailStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpErrorsTest {
    private fun no(code: String?) = ImapNoException(code?.let { RespCode(it, emptyList()) }, "server text")

    @Test
    fun transientCodesRetry() {
        for (code in listOf("UNAVAILABLE", "INUSE", "SERVERBUG", "THROTTLED")) {
            assertEquals(Failure.TRANSIENT, OpErrors.classify(no(code)), code)
        }
        assertEquals(Failure.TRANSIENT, OpErrors.classify(ImapByeException(null, "bye")))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(ImapProtocolException("broken")))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(SocketTimeoutException()))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(IOException("reset")))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(MailError.Network(IOException())))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(MailError.Throttled("slow down")))
    }

    @Test
    fun gmailLimitsRetryByTheirText() {
        val limits = ImapNoException(null, "Account exceeded command or bandwidth limits.")
        assertEquals(Failure.TRANSIENT, OpErrors.classify(limits))
        val coded = ImapNoException(RespCode("OVERQUOTA", emptyList()), "Account exceeded command or bandwidth limits.")
        assertEquals(Failure.TRANSIENT, OpErrors.classify(coded))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(ImapNoException(null, "Too many simultaneous connections")))
        // Riseup: the mailbox is full. Retrying for 24 h would only hide it (protocol PS14).
        assertEquals(Failure.PERMANENT, OpErrors.classify(ImapNoException(RespCode("OVERQUOTA", emptyList()), "Quota exceeded")))
    }

    @Test
    fun everyOtherRefusalIsPermanent() {
        // protocol PS14: OVERQUOTA, TOOBIG, CANNOT, a NO without a code and every BAD.
        for (code in listOf("OVERQUOTA", "TOOBIG", "LIMIT", "CANNOT", "NONEXISTENT", null)) {
            assertEquals(Failure.PERMANENT, OpErrors.classify(no(code)), code)
        }
        assertEquals(Failure.PERMANENT, OpErrors.classify(ImapBadException("Could not parse command")))
        assertEquals(Failure.PERMANENT, OpErrors.classify(OpRefused("too large")))
        assertEquals(Failure.PERMANENT, OpErrors.classify(MailError.Server("no")))
        assertEquals(Failure.PERMANENT, OpErrors.classify(IllegalArgumentException("Invalid To address")))
    }

    @Test
    fun tryCreateIsItsOwnClass() {
        assertEquals(Failure.TRYCREATE, OpErrors.classify(no("TRYCREATE")))
    }

    @Test
    fun onlyConnectionErrorsEndTheCycle() {
        val open = client()
        assertFalse(OpErrors.endsConnection(no("UNAVAILABLE"), open))
        assertFalse(OpErrors.endsConnection(ImapBadException("bad"), open))
        assertFalse(OpErrors.endsConnection(UidValidityChanged(), open))
        assertFalse(OpErrors.endsConnection(OpRefused("rule"), open))
        assertTrue(OpErrors.endsConnection(MailError.Network(IOException()), open))

        // `:mail` closes the connection on every wire or protocol error.
        val closed = client().also { it.close() }
        assertTrue(OpErrors.endsConnection(ImapByeException(null, "bye"), closed))
        assertTrue(OpErrors.endsConnection(IOException("reset"), closed))
    }

    @Test
    fun anAppErrorOnAnOpenConnectionKeepsIt() {
        // E.g. an attachment of a message that another device expunged, or a local file that is gone.
        assertFalse(OpErrors.endsConnection(FileNotFoundException("Message is gone"), client()))
    }

    /** A client after the greeting; nothing is sent to its server. */
    private fun client(): ImapClient {
        val stream = object : MailStream {
            override val input = ByteArrayInputStream("* OK ready\r\n".toByteArray())
            override val output = ByteArrayOutputStream()
            override var readTimeoutMs = 0
            override val encrypted = true
            override fun startCompression() = Unit
            override fun startTls() = Unit
            override fun close() = Unit
        }
        return ImapClient(ImapConnection(stream)).also { it.conn.greeting() }
    }

    @Test
    fun backoffSteps() {
        assertEquals(listOf(5_000L, 30_000L, 120_000L, 600_000L, 3_600_000L, 3_600_000L), (1..6).map(OpErrors::backoffMs))
        assertEquals(5_000L, OpErrors.backoffMs(0))
    }

    @Test
    fun anOpGivesUpAfterADayOfBackoffNotADayOfAge() {
        // 5 s + 30 s + 2 m + 10 m, then 1 h steps: the 29th failure follows 24 h 12 m of backoff.
        assertFalse(OpErrors.givesUp(1))
        assertFalse(OpErrors.givesUp(28))
        assertTrue(OpErrors.givesUp(29))
    }

    @Test
    fun textIsTheServerReply() {
        assertEquals("server text", OpErrors.text(no("OVERQUOTA")))
        assertEquals("IOException", OpErrors.text(IOException()))
    }

    @Test
    fun appendLimitIsTheSmallerOne() {
        val gmail = setOf("IMAP4REV1", "APPENDLIMIT=35651584")
        assertEquals(35_651_584L, Ops.appendLimit(gmail, 35_882_577))
        assertEquals(25_600_000L, Ops.appendLimit(setOf("IMAP4REV1"), 25_600_000))
        assertEquals(1_000L, Ops.appendLimit(setOf("APPENDLIMIT=2000"), 1_000))
        assertEquals(1_000L, Ops.appendLimit(setOf("APPENDLIMIT=junk"), 1_000))
    }
}
